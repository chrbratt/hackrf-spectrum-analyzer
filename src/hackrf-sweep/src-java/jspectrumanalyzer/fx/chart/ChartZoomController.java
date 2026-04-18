package jspectrumanalyzer.fx.chart;

import java.awt.geom.Rectangle2D;

import javafx.application.Platform;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.ScrollEvent;
import javafx.scene.paint.Color;
import jspectrumanalyzer.core.FrequencyPlan;
import jspectrumanalyzer.core.FrequencyRange;
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
 *   <li>Right-click inside the data area &rarr; resets to the current preset's
 *       full range (i.e. the most recent value the user picked from the
 *       Frequency presets combo).</li>
 * </ul>
 */
public final class ChartZoomController {

    /** Minimum pixel drag width that counts as an intentional zoom. */
    private static final double MIN_DRAG_PIXELS = 6;
    /** Wheel zoom factor per scroll tick (1 tick = 40 deltaY by JavaFX default). */
    private static final double WHEEL_ZOOM_PER_TICK = 0.20;

    private final SpectrumChart spectrumChart;
    private final SettingsStore settings;
    private final FrequencyRangeValidator validator;
    private final Canvas overlay;
    private final FrequencyRange resetRange;

    private double dragStartX = -1;
    private boolean dragging = false;

    public ChartZoomController(ChartViewer viewer,
                                SpectrumChart spectrumChart,
                                SettingsStore settings,
                                FrequencyRangeValidator validator,
                                Canvas dragOverlay) {
        this.spectrumChart = spectrumChart;
        this.settings = settings;
        this.validator = validator;
        this.overlay = dragOverlay;
        // "Reset" goes back to whatever range was active when the controller
        // was installed (effectively the user's last preset choice). Capturing
        // the value here, instead of recomputing on each reset, matches what a
        // user expects from "undo zoom".
        this.resetRange = settings.getFrequency().getValue();

        viewer.addEventFilter(MouseEvent.MOUSE_PRESSED, this::onMousePressed);
        viewer.addEventFilter(MouseEvent.MOUSE_DRAGGED, this::onMouseDragged);
        viewer.addEventFilter(MouseEvent.MOUSE_RELEASED, this::onMouseReleased);
        viewer.addEventFilter(ScrollEvent.SCROLL, this::onScroll);
    }

    private boolean enabledForCurrentPlan() {
        FrequencyPlan plan = settings.getEffectivePlan();
        return !plan.isMultiSegment();
    }

    /**
     * Programmatically restore the captured baseline range. Bound to the Esc
     * keyboard shortcut so the user can undo a zoom without reaching for
     * the mouse. No-op while a multi-segment plan is active because zoom
     * itself is disabled in that mode.
     */
    public void resetZoom() {
        if (!enabledForCurrentPlan()) return;
        if (!resetRange.equals(settings.getFrequency().getValue())) {
            settings.getFrequency().setValue(resetRange);
        }
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
            // Right-click = reset zoom to the captured baseline range.
            settings.getFrequency().setValue(resetRange);
            e.consume();
            return;
        }
        if (e.getButton() != MouseButton.PRIMARY) return;
        if (!isInDataArea(e.getX(), e.getY())) return;
        dragStartX = e.getX();
        dragging = true;
        clearOverlay();
        e.consume();
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
        FrequencyRange current = settings.getFrequency().getValue();
        int span = current.getEndMHz() - current.getStartMHz();
        if (span <= FrequencyRangeValidator.MIN_SPAN_MHZ && e.getDeltaY() > 0) {
            // Already at minimum; nothing to do.
            return;
        }
        double pivotMhz = pixelXToMHz(e.getX());
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
        double frac = (pivotMhz - current.getStartMHz()) / span;
        double newStart = pivotMhz - frac * newSpan;
        double newEnd = newStart + newSpan;
        commitRange(newStart, newEnd);
        e.consume();
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
        // Stay on FX thread; ModelValue listeners will marshal to engine.
        Platform.runLater(() -> settings.getFrequency().setValue(coerced));
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
