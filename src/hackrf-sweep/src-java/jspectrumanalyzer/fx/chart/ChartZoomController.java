package jspectrumanalyzer.fx.chart;

import java.awt.geom.Rectangle2D;
import java.util.ArrayDeque;
import java.util.Deque;

import javafx.animation.PauseTransition;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.ScrollEvent;
import javafx.scene.paint.Color;
import javafx.util.Duration;
import jspectrumanalyzer.core.FrequencyPlan;
import jspectrumanalyzer.core.FrequencyRange;
import jspectrumanalyzer.fx.engine.SdrController;
import jspectrumanalyzer.fx.frequency.FrequencyRangeValidator;
import jspectrumanalyzer.fx.model.SettingsStore;
import org.jfree.chart.fx.ChartViewer;

/**
 * Restores the legacy fork's "drag on the chart to pick a frequency window /
 * scroll-wheel to zoom" behaviour for the JavaFX rewrite. Setting
 * {@link SettingsStore#getFrequency()} reaches the engine through the existing
 * {@code restartIfRunning} listener, so the sweep speed automatically scales
 * with the new (narrower) span - exactly as it did in v2.21.
 *
 * <p>Zoom is intentionally <strong>disabled while a multi-segment plan is
 * active</strong>: zooming inside one stitched band has three plausible
 * meanings (collapse the plan to that window, narrow only that segment, or
 * narrow all segments) and the right answer needs UX work. Picking the
 * legacy single-range behaviour by default is the safest choice and keeps the
 * Wi-Fi 2.4 + 5 + 6E preset usable as a "monitor everything" mode.
 *
 * <p>Bindings:
 * <ul>
 *   <li>Left-drag horizontally inside the data area &rarr; selects an x range,
 *       commits to {@code settings.frequency} on release.</li>
 *   <li>Scroll wheel inside the data area &rarr; zooms in/out around the
 *       cursor. Each tick changes the span by 20%.</li>
 *   <li>Right-click inside the data area &rarr; pops one level from the
 *       zoom history stack (i.e. goes back to the previous drag/scroll
 *       zoom). When the stack is empty, falls back to the current
 *       baseline preset (the last range applied by the preset combo or
 *       the value at controller construction). This matches the muscle
 *       memory of "browser back" and avoids the surprise where the
 *       previous implementation always jumped to the startup range
 *       regardless of what preset the user had since selected.</li>
 * </ul>
 *
 * <h2>History bookkeeping</h2>
 * Two paths can mutate {@link SettingsStore#getFrequency()}:
 * <ol>
 *   <li>This controller (drag, scroll, right-click). Every such commit
 *       pushes the previous range onto {@link #zoomHistory} and stamps
 *       {@link #lastSelfWrite} with the new value.</li>
 *   <li>External callers - mainly the preset combo in
 *       {@code FrequencyRangeSelector}. The model listener detects these
 *       by comparing the new value against {@link #lastSelfWrite}; on
 *       mismatch the history is cleared and {@link #baseline} is moved
 *       to the new value. This way "preset Wi-Fi 5 -&gt; zoom -&gt; zoom
 *       -&gt; right-click -&gt; right-click" steps back twice and lands
 *       on Wi-Fi 5, never on the startup range.</li>
 * </ol>
 */
public final class ChartZoomController {

    /** Minimum pixel drag width that counts as an intentional zoom. */
    private static final double MIN_DRAG_PIXELS = 6;
    /** Wheel zoom factor per scroll tick (1 tick = 40 deltaY by JavaFX default). */
    private static final double WHEEL_ZOOM_PER_TICK = 0.20;
    /** Cap on history depth so a user that scroll-zooms for minutes
     *  doesn't grow the deque without bound. 32 is well past anything a
     *  human will undo step-by-step. */
    private static final int MAX_HISTORY = 32;
    /**
     * How long to wait after the last scroll tick before committing the
     * accumulated zoom range. Each commit triggers a full radio
     * stop/start cycle (~1-2 s on Windows), so coalescing a wheel spin
     * into one commit is the difference between "responsive" and "the
     * sweep restarts five times in a row and the chart stops painting".
     * 120 ms is just above a typical mouse-wheel inter-tick gap and
     * still feels instant to the user.
     */
    private static final Duration SCROLL_DEBOUNCE = Duration.millis(120);

    private final SpectrumChart spectrumChart;
    private final SettingsStore settings;
    private final SdrController sdrController;
    private final FrequencyRangeValidator validator;
    private final Canvas overlay;

    /**
     * "Where right-click should land when the history stack is empty."
     * Updated whenever an external write is observed (preset selection),
     * so it always reflects the user's most recent intent rather than
     * the value at app start.
     */
    private FrequencyRange baseline;
    /** Pushed by self-writes (drag/scroll/right-click), popped by right-click. */
    private final Deque<FrequencyRange> zoomHistory = new ArrayDeque<>();
    /**
     * The last value this controller wrote to the model. The preset
     * listener uses it to tell self-writes (no history reset) apart from
     * external writes (history reset, baseline update). {@code null}
     * before the first self-write.
     */
    private FrequencyRange lastSelfWrite;

    private double dragStartX = -1;
    private boolean dragging = false;

    /**
     * Pending scroll-zoom target. Updated on every wheel tick (the
     * zoom math is applied locally to keep the cursor pivoted), then
     * committed once {@link #scrollDebounce} fires. Null means nothing
     * pending.
     */
    private FrequencyRange scrollPendingTarget;
    /**
     * Restarts on every scroll tick, fires once the wheel has been
     * idle for {@link #SCROLL_DEBOUNCE}. Lazily initialised because
     * constructing a {@link PauseTransition} touches the JavaFX toolkit
     * and we want the controller usable from headless tests.
     */
    private PauseTransition scrollDebounce;

    public ChartZoomController(ChartViewer viewer,
                                SpectrumChart spectrumChart,
                                SettingsStore settings,
                                SdrController sdrController,
                                FrequencyRangeValidator validator,
                                Canvas dragOverlay) {
        this.spectrumChart = spectrumChart;
        this.settings = settings;
        this.sdrController = sdrController;
        this.validator = validator;
        this.overlay = dragOverlay;
        // Seed the baseline with the value at construction so the very
        // first right-click before any preset change still has a sane
        // fallback. The preset listener below keeps this in sync.
        this.baseline = settings.getFrequency().getValue();

        settings.getFrequency().addListener(this::onModelFrequencyChanged);

        viewer.addEventFilter(MouseEvent.MOUSE_PRESSED, this::onMousePressed);
        viewer.addEventFilter(MouseEvent.MOUSE_DRAGGED, this::onMouseDragged);
        viewer.addEventFilter(MouseEvent.MOUSE_RELEASED, this::onMouseReleased);
        viewer.addEventFilter(ScrollEvent.SCROLL, this::onScroll);
    }

    /**
     * Detect external writes (preset combo, manual frequency entry) and
     * treat them as a new baseline: the user just told us "I want to be
     * here", so any prior zoom history is no longer meaningful.
     */
    private void onModelFrequencyChanged() {
        FrequencyRange now = settings.getFrequency().getValue();
        if (now == null) return;
        if (lastSelfWrite != null && lastSelfWrite.equals(now)) {
            // Echo of our own setValue from commitRange / resetZoom; the
            // history was already updated there, nothing more to do.
            return;
        }
        // External change -> wipe stale history and rebase. Any in-
        // flight scroll commit is also stale (it would step away from
        // the new preset the moment it fired).
        dropPendingScroll();
        zoomHistory.clear();
        baseline = now;
    }

    private boolean enabledForCurrentPlan() {
        FrequencyPlan plan = settings.getEffectivePlan();
        return !plan.isMultiSegment();
    }

    /**
     * Hard-reset to the current baseline (preset). Bound to the Esc
     * keyboard shortcut as the "out, all the way" action - clears the
     * full zoom history without pushing the current range back onto it
     * (Esc means "throw away, don't save"). No-op while a multi-segment
     * plan is active because zoom itself is disabled in that mode.
     */
    public void resetZoom() {
        if (!enabledForCurrentPlan()) return;
        dropPendingScroll();
        zoomHistory.clear();
        FrequencyRange current = settings.getFrequency().getValue();
        if (baseline != null && !baseline.equals(current)) {
            writeWithoutPushing(baseline);
        }
    }

    /**
     * Step one level back in the zoom history. Pops the most recent
     * pre-zoom range and applies it without pushing the current range
     * (right-click is "go back", not "save then go back"). When the
     * history is empty falls through to the baseline so the user always
     * has a way out without the keyboard shortcut.
     */
    private boolean popZoom() {
        if (!enabledForCurrentPlan()) return false;
        FrequencyRange prev = zoomHistory.pollFirst();
        FrequencyRange current = settings.getFrequency().getValue();
        FrequencyRange target = prev != null ? prev : baseline;
        if (target == null || target.equals(current)) return false;
        writeWithoutPushing(target);
        return true;
    }

    /**
     * Push the current range onto the capped history stack and apply
     * the new range. Used by drag-zoom and scroll-zoom commits where
     * the user expects right-click to return to the pre-zoom view.
     */
    private void pushAndApply(FrequencyRange next) {
        FrequencyRange current = settings.getFrequency().getValue();
        if (current != null && !current.equals(next)) {
            zoomHistory.push(current);
            while (zoomHistory.size() > MAX_HISTORY) {
                zoomHistory.pollLast();
            }
        }
        writeWithoutPushing(next);
    }

    /**
     * Stamp {@link #lastSelfWrite} so the model listener treats the
     * subsequent {@code setValue} as our own write (no history reset),
     * then route through {@link SdrController}. The controller
     * marshals to FX itself so we no longer wrap a Platform.runLater
     * here.
     */
    private void writeWithoutPushing(FrequencyRange next) {
        lastSelfWrite = next;
        sdrController.requestRetune(next);
    }

    private boolean isInDataArea(double x, double y) {
        Rectangle2D area = spectrumChart.getDataArea();
        return area != null
                && x >= area.getX() && x <= area.getX() + area.getWidth()
                && y >= area.getY() && y <= area.getY() + area.getHeight();
    }

    private double pixelXToMHz(double pixelX) {
        Rectangle2D area = spectrumChart.getDataArea();
        FrequencyRange r = settings.getFrequency().getValue();
        int shift = settings.getFreqShift().getValue();
        double frac = (pixelX - area.getX()) / area.getWidth();
        frac = Math.max(0, Math.min(1, frac));
        // The chart axis displays r.start+shift..r.end+shift; mapping back to
        // RF MHz means undoing the shift before we hand it to the validator.
        double axisStart = r.getStartMHz() + shift;
        double axisEnd = r.getEndMHz() + shift;
        double axisMhz = axisStart + frac * (axisEnd - axisStart);
        return axisMhz - shift;
    }

    private void onMousePressed(MouseEvent e) {
        if (!enabledForCurrentPlan()) return;
        if (e.getButton() == MouseButton.SECONDARY && isInDataArea(e.getX(), e.getY())) {
            // Any pending scroll commit is now stale: the user said
            // "go back" before we got around to applying the wheel
            // spin. Drop it so popZoom doesn't fight a delayed write.
            dropPendingScroll();
            popZoom();
            e.consume();
            return;
        }
        if (e.getButton() != MouseButton.PRIMARY) return;
        if (!isInDataArea(e.getX(), e.getY())) return;
        dropPendingScroll();
        dragStartX = e.getX();
        dragging = true;
        clearOverlay();
        e.consume();
    }

    private void dropPendingScroll() {
        if (scrollDebounce != null) scrollDebounce.stop();
        scrollPendingTarget = null;
    }

    private void onMouseDragged(MouseEvent e) {
        if (!dragging) return;
        Rectangle2D area = spectrumChart.getDataArea();
        if (area == null) return;
        double x0 = clampX(dragStartX, area);
        double x1 = clampX(e.getX(), area);
        paintDragRect(Math.min(x0, x1), Math.max(x0, x1), area);
        e.consume();
    }

    private void onMouseReleased(MouseEvent e) {
        if (!dragging) return;
        dragging = false;
        clearOverlay();
        if (e.getButton() != MouseButton.PRIMARY) return;
        Rectangle2D area = spectrumChart.getDataArea();
        if (area == null) return;
        double x0 = clampX(dragStartX, area);
        double x1 = clampX(e.getX(), area);
        if (Math.abs(x1 - x0) < MIN_DRAG_PIXELS) return;

        double mhzA = pixelXToMHz(Math.min(x0, x1));
        double mhzB = pixelXToMHz(Math.max(x0, x1));
        commitRange(mhzA, mhzB);
        e.consume();
    }

    private void onScroll(ScrollEvent e) {
        if (!enabledForCurrentPlan()) return;
        if (!isInDataArea(e.getX(), e.getY())) return;
        // Compute against the latest *pending* target rather than the
        // model value: while the debounce timer is in flight the model
        // hasn't moved yet, so reading from it would make every tick
        // recompute around the pre-spin range and the user's wheel
        // motion would feel sticky / decay back. Accumulating against
        // the pending target gives the natural multi-tick zoom feel.
        FrequencyRange basis = scrollPendingTarget != null
                ? scrollPendingTarget
                : settings.getFrequency().getValue();
        if (basis == null) return;
        int span = basis.getEndMHz() - basis.getStartMHz();
        if (span <= FrequencyRangeValidator.MIN_SPAN_MHZ && e.getDeltaY() > 0) {
            return;
        }
        double pivotMhz = pixelXToMHzFor(basis, e.getX());
        // deltaY > 0 = wheel up = zoom in (smaller span); < 0 = zoom out.
        int ticks = (int) Math.signum(e.getDeltaY());
        if (ticks == 0) return;
        double zoom = (ticks > 0)
                ? (1.0 - WHEEL_ZOOM_PER_TICK)
                : (1.0 / (1.0 - WHEEL_ZOOM_PER_TICK));
        double newSpan = Math.max(FrequencyRangeValidator.MIN_SPAN_MHZ, span * zoom);
        // Keep the cursor's MHz pinned to its pixel position so wheeling feels
        // anchored to the band the user is hovering over (matches the v2.21
        // behaviour).
        double frac = (pivotMhz - basis.getStartMHz()) / span;
        double newStart = pivotMhz - frac * newSpan;
        double newEnd = newStart + newSpan;
        int s = (int) Math.round(newStart);
        int en = (int) Math.round(newEnd);
        if (en - s < FrequencyRangeValidator.MIN_SPAN_MHZ) {
            en = s + FrequencyRangeValidator.MIN_SPAN_MHZ;
        }
        scrollPendingTarget = validator.coerce(s, en);
        scheduleScrollCommit();
        e.consume();
    }

    /**
     * (Re)start the debounce timer. Called from every scroll tick - the
     * timer's {@code playFromStart()} cancels any in-flight wait, so
     * a continuous wheel spin keeps deferring the commit. Once the
     * wheel goes idle for {@link #SCROLL_DEBOUNCE}, the accumulated
     * target is pushed through {@link #pushAndApply(FrequencyRange)}
     * exactly once.
     */
    private void scheduleScrollCommit() {
        if (scrollDebounce == null) {
            scrollDebounce = new PauseTransition(SCROLL_DEBOUNCE);
            scrollDebounce.setOnFinished(ev -> flushScrollCommit());
        }
        scrollDebounce.playFromStart();
    }

    private void flushScrollCommit() {
        FrequencyRange target = scrollPendingTarget;
        scrollPendingTarget = null;
        if (target == null) return;
        FrequencyRange current = settings.getFrequency().getValue();
        if (target.equals(current)) return;
        pushAndApply(target);
    }

    /**
     * Pixel -> MHz for an arbitrary range. {@link #pixelXToMHz(double)}
     * always reads from the model, which is the wrong basis while a
     * wheel spin is mid-flight (the model is still on the pre-spin
     * value). Accept the basis explicitly so the scroll path can keep
     * pivoting against its accumulated target.
     */
    private double pixelXToMHzFor(FrequencyRange basis, double pixelX) {
        Rectangle2D area = spectrumChart.getDataArea();
        int shift = settings.getFreqShift().getValue();
        double frac = (pixelX - area.getX()) / area.getWidth();
        frac = Math.max(0, Math.min(1, frac));
        double axisStart = basis.getStartMHz() + shift;
        double axisEnd = basis.getEndMHz() + shift;
        double axisMhz = axisStart + frac * (axisEnd - axisStart);
        return axisMhz - shift;
    }

    private void commitRange(double startMhz, double endMhz) {
        int s = (int) Math.round(Math.min(startMhz, endMhz));
        int en = (int) Math.round(Math.max(startMhz, endMhz));
        if (en - s < FrequencyRangeValidator.MIN_SPAN_MHZ) {
            en = s + FrequencyRangeValidator.MIN_SPAN_MHZ;
        }
        FrequencyRange coerced = validator.coerce(s, en);
        FrequencyRange current = settings.getFrequency().getValue();
        if (coerced.equals(current)) return;
        // Push the pre-zoom range so the next right-click can return to
        // it. Listeners (engine restart) marshal off the FX thread on
        // their own, so Platform.runLater inside writeWithoutPushing is
        // enough to keep this side effect-free.
        pushAndApply(coerced);
    }

    private void paintDragRect(double x0, double x1, Rectangle2D area) {
        GraphicsContext g = overlay.getGraphicsContext2D();
        g.clearRect(0, 0, overlay.getWidth(), overlay.getHeight());
        g.setFill(Color.color(0.4, 0.7, 1.0, 0.18));
        g.setStroke(Color.color(0.6, 0.85, 1.0, 0.85));
        g.setLineWidth(1);
        double y = area.getY();
        double h = area.getHeight();
        g.fillRect(x0, y, x1 - x0, h);
        g.strokeRect(x0 + 0.5, y + 0.5, x1 - x0 - 1, h - 1);
    }

    private void clearOverlay() {
        GraphicsContext g = overlay.getGraphicsContext2D();
        g.clearRect(0, 0, overlay.getWidth(), overlay.getHeight());
    }

    private static double clampX(double x, Rectangle2D area) {
        double lo = area.getX();
        double hi = area.getX() + area.getWidth();
        if (x < lo) return lo;
        if (x > hi) return hi;
        return x;
    }
}
