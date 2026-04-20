package jspectrumanalyzer.fx.chart;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.util.Arrays;

import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.embed.swing.SwingFXUtils;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.paint.Paint;
import javafx.scene.image.WritableImage;
import javafx.util.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import jspectrumanalyzer.core.DatasetSpectrum;
import jspectrumanalyzer.ui.GraphicsToolkit;
import jspectrumanalyzer.ui.ColorPalette;
import jspectrumanalyzer.ui.HotIronBluePalette;

/**
 * JavaFX waterfall renderer backed by one or more circular ring
 * buffers ("tiers") stacked vertically.
 *
 * <h2>Single-tier mode (default)</h2>
 * Behaves exactly like a classic waterfall: one ring buffer of size
 * {@code chartWidth * canvasHeight}, one new row per spectrum frame.
 * Per-frame cost is {@code O(width)} (one row paint, no full-buffer
 * memcpy) so tall waterfalls stay smooth.
 *
 * <h2>Funnel mode (single-buffer, opt-in)</h2>
 * One tall ring buffer of <em>raw normalised amplitude rows</em>
 * (default depth {@link #FUNNEL_HISTORY_FACTOR}× canvas height) holds
 * the recent history. At paint time each screen row {@code y} is
 * mapped to a source-row interval via the easing function
 * {@code f(t) = t^FUNNEL_EASING_POWER}: the top of the canvas is 1:1
 * with the live row, and as you go down each pixel covers
 * progressively more source rows, max-held together. The result is a
 * single seamless waterfall that brakes smoothly toward the bottom
 * instead of the discrete tiered strips of the legacy
 * implementation. Max-hold across the source interval keeps short
 * bursts from being averaged into the noise floor.
 *
 * <h2>Threading</h2>
 * {@link #addNewData} runs on the engine thread; {@link #paint},
 * resize handlers, palette changes and {@link #clearHistory()} run on
 * the FX thread. Both mutate buffer state and are synchronised on
 * the canvas instance, so a paint and an add cannot interleave
 * halfway through one row's pixels.
 */
public final class WaterfallCanvas extends Canvas {

    private static final Logger LOG = LoggerFactory.getLogger(WaterfallCanvas.class);

    private static final int MIN_BUFFER_WIDTH = 64;
    private static final int MIN_BUFFER_HEIGHT = 32;
    private static final float MINIMUM_DRAW_BUFFER_VALUE = -150f;

    /**
     * Funnel-mode history depth in multiples of the canvas height.
     * Together with {@link #FUNNEL_EASING_POWER} this controls how
     * far back in time the bottom row of the waterfall reaches: 3x
     * means a 600-pixel waterfall holds ~1800 raw frames of
     * amplitude history, of which the upper portion is shown 1:1
     * and the lower portion is max-hold compressed.
     */
    private static final int FUNNEL_HISTORY_FACTOR = 3;
    /**
     * Easing exponent for the screen-row -> source-row mapping. Power
     * curves with {@code p > 1} keep the top of the canvas
     * uncompressed (live, fine detail) while progressively braking
     * the timeline toward the bottom. {@code 2.5} is a soft "hockey
     * stick" - top half stays detailed, bottom half compresses
     * smoothly into seconds-of-history per pixel.
     */
    private static final double FUNNEL_EASING_POWER = 2.5;

    /**
     * Per-stage timing meter, opt-in via {@code -Dwaterfall.perf=true}.
     * Reports rolling avg/max/count once per second (rate-limited to keep
     * log noise predictable). When disabled, the only overhead is one
     * {@code System.nanoTime()} read per call - negligible compared to
     * the BufferedImage work the path already does.
     */
    private static final boolean PERF =
            Boolean.getBoolean("waterfall.perf");
    private static final long PERF_REPORT_NS = 1_000_000_000L;
    private final PerfMeter addPerf  = PERF ? new PerfMeter("addNewData") : null;
    private final PerfMeter paintPerf = PERF ? new PerfMeter("paint")     : null;

    private static final class PerfMeter {
        private final String name;
        private long count;
        private long sumNs;
        private long maxNs;
        private long lastReportNs = System.nanoTime();
        PerfMeter(String name) { this.name = name; }
        void record(long elapsedNs) {
            count++;
            sumNs += elapsedNs;
            if (elapsedNs > maxNs) maxNs = elapsedNs;
            long now = System.nanoTime();
            if (now - lastReportNs >= PERF_REPORT_NS && count > 0) {
                LOG.info("waterfall.{}: {} calls, avg {} us, max {} us",
                        name, count, sumNs / 1000 / count, maxNs / 1000);
                count = 0; sumNs = 0; maxNs = 0;
                lastReportNs = now;
            }
        }
    }

    /**
     * Single ring-buffer image for the flat (non-funnel) waterfall
     * path. Kept as a one-element array (rather than a bare
     * {@code Tier} field) so the resize / clear / paint helpers can
     * stay shape-compatible with the legacy multi-tier code while
     * the funnel path lives in its own data structure.
     */
    private static final class Tier {
        BufferedImage buffer;
        int width;
        int height;
        /** Index of the row currently displayed at the top of the tier. */
        int topRow;
    }

    private Tier flatTier;

    /**
     * {@code true} while funnel mode is active. When set, {@link
     * #flatTier} is released and {@link #funnelHistory} owns the
     * waterfall pixels; when cleared, the inverse holds. The two
     * never coexist, so toggling discards the inactive-mode history
     * (the time scale is incompatible anyway).
     */
    private boolean funnelEnabled = false;

    /**
     * Funnel mode history. Each row is {@code funnelWidth} normalised
     * palette positions in {@code [0,1]}. The newest row is at
     * {@code funnelHistory[funnelTop]}; older rows live at
     * {@code funnelHistory[(funnelTop + k) % funnelDepth]} for
     * increasing {@code k}. {@code funnelCount} tracks how many real
     * rows have been pushed since allocation so the paint path can
     * draw black for "no data yet" rather than reading whatever
     * uninitialised memory the JVM handed us.
     */
    private float[][] funnelHistory;
    private int funnelTop;
    private int funnelCount;
    private int funnelDepth;
    private int funnelWidth;

    /** Reusable destination image for the funnel paint path. */
    private BufferedImage funnelOutImage;
    private int[] funnelOutPixels;
    /** Reusable max-hold accumulator for one screen row in funnel paint. */
    private float[] funnelMaxRow;

    private float[] drawMaxBuffer = new float[MIN_BUFFER_WIDTH];

    /**
     * Active palette. Mutable because the user can swap themes from the
     * Display tab at any time; volatile so the FX-thread paint loop sees the
     * new instance the moment the model listener writes it. We never read this
     * inside the per-pixel hot loop more than once - the first {@code palette}
     * read becomes a local variable - so the volatile read cost is paid once
     * per row, not per pixel.
     */
    private volatile ColorPalette palette = new HotIronBluePalette();
    private double spectrumPaletteSize = 65;
    private double spectrumPaletteStart = -110;

    private int chartXOffset = 0;
    private int chartWidth = MIN_BUFFER_WIDTH;
    private int totalBufferHeight = MIN_BUFFER_HEIGHT;

    private volatile DatasetSpectrum lastSpectrum;

    /**
     * Debounce timer for buffer reallocation. Resize events fire every frame
     * during a SplitPane drag (30+ per second); reallocating + NN-rescaling
     * the history buffer that often compounds rounding errors and tanks
     * frame rate. We instead schedule a single reallocation ~150 ms after
     * the last resize event, and during the pause `paint()` just stretches
     * the existing buffer to fit. Lives on the FX thread (FX-only API).
     */
    private static final Duration RESIZE_DEBOUNCE = Duration.millis(150);
    private final PauseTransition resizeDebouncer = new PauseTransition(RESIZE_DEBOUNCE);
    private int pendingWidth = MIN_BUFFER_WIDTH;
    private int pendingHeight = MIN_BUFFER_HEIGHT;

    public WaterfallCanvas() {
        super(320, 200);
        rebuildBuffers(MIN_BUFFER_WIDTH, MIN_BUFFER_HEIGHT, false);
        widthProperty().addListener((obs, o, n) -> resizeBuffers());
        heightProperty().addListener((obs, o, n) -> resizeBuffers());
        resizeDebouncer.setOnFinished(e -> applyPendingResize());
    }

    @Override
    public boolean isResizable() {
        return true;
    }

    @Override
    public double prefWidth(double height) {
        return getWidth();
    }

    @Override
    public double prefHeight(double width) {
        return getHeight();
    }

    public void setDrawingOffsets(int xOffsetLeft, int chartWidthPx) {
        this.chartXOffset = Math.max(0, xOffsetLeft);
        this.chartWidth = Math.max(MIN_BUFFER_WIDTH, chartWidthPx);
        resizeBuffers();
    }

    public void setSpectrumPaletteSize(int dB) {
        this.spectrumPaletteSize = dB;
    }

    public void setSpectrumPaletteStart(int dB) {
        this.spectrumPaletteStart = dB;
    }

    /**
     * Switch between flat (one row per frame, full canvas) and funnel
     * (single seamless waterfall whose timeline brakes smoothly
     * toward the bottom) rendering. Existing history is dropped on
     * toggle because the time scale changes - stretching the old
     * pixels into the new layout would mislead the eye.
     *
     * @param funnelEnabled {@code true} to enable the seamless funnel
     *                      view, {@code false} for the classic flat view
     */
    public synchronized void setFunnelEnabled(boolean funnelEnabled) {
        if (funnelEnabled == this.funnelEnabled) return;
        this.funnelEnabled = funnelEnabled;
        rebuildBuffers(chartWidth, totalBufferHeight, true);
        requestPaint();
    }

    public boolean isFunnelEnabled() {
        return funnelEnabled;
    }

    /**
     * Push one spectrum into the waterfall. Safe to call off the FX
     * thread. Trigger {@link #requestPaint()} from any thread to
     * schedule a repaint on the FX thread afterwards.
     *
     * <p>Both modes are O(width): flat mode paints one ring-buffer
     * row, funnel mode does an array copy into the float-row ring.
     */
    public synchronized void addNewData(DatasetSpectrum spectrum) {
        if (spectrum == null) return;
        long t0 = PERF ? System.nanoTime() : 0L;
        this.lastSpectrum = spectrum;

        int width = funnelEnabled ? funnelWidth
                : (flatTier != null ? flatTier.width : 0);
        if (width <= 0) return;
        if (drawMaxBuffer.length != width) {
            drawMaxBuffer = new float[width];
        }
        buildNormalizedRow(spectrum, width);

        if (funnelEnabled) {
            pushFunnelRow(drawMaxBuffer);
        } else {
            // Snapshot the volatile palette once so the tier paints
            // with a consistent colour set - prevents a mid-frame
            // palette swap from striping across rows.
            writeRowToTier(flatTier, drawMaxBuffer, palette);
        }
        if (PERF) addPerf.record(System.nanoTime() - t0);
    }

    /**
     * Append one normalised amplitude row to the funnel ring. The
     * ring grows backward in source-row order (newest at index 0
     * relative to {@link #funnelTop}) so the paint path can map
     * "screen row 0 = age 0" without subtracting indices.
     */
    private void pushFunnelRow(float[] row) {
        if (funnelHistory == null || funnelDepth <= 0) return;
        funnelTop = (funnelTop - 1 + funnelDepth) % funnelDepth;
        float[] dst = funnelHistory[funnelTop];
        if (dst == null || dst.length != funnelWidth) {
            dst = new float[funnelWidth];
            funnelHistory[funnelTop] = dst;
        }
        int n = Math.min(row.length, funnelWidth);
        System.arraycopy(row, 0, dst, 0, n);
        if (n < funnelWidth) {
            Arrays.fill(dst, n, funnelWidth, MINIMUM_DRAW_BUFFER_VALUE);
        }
        if (funnelCount < funnelDepth) funnelCount++;
    }

    /**
     * Convert {@code spectrum} into normalised palette positions in
     * {@link #drawMaxBuffer}. Same algorithm as the original
     * implementation: per pixel column, take the max normalised power
     * of all bins that round to that column.
     */
    private void buildNormalizedRow(DatasetSpectrum spectrum, int width) {
        int size = spectrum.spectrumLength();
        double spectrumPaletteMax = spectrumPaletteStart + spectrumPaletteSize;
        Arrays.fill(drawMaxBuffer, MINIMUM_DRAW_BUFFER_VALUE);
        double widthDivSize = (double) width / size;
        double inverseSpectrumPaletteSize = 1d / spectrumPaletteSize;
        double spectrumPaletteStartDiv = spectrumPaletteStart / spectrumPaletteSize;
        for (int i = 0; i < size; i++) {
            double power = spectrum.getPower(i);
            double percentagePower = 0;
            if (power > spectrumPaletteStart) {
                if (power < spectrumPaletteMax) {
                    percentagePower = power * inverseSpectrumPaletteSize - spectrumPaletteStartDiv;
                } else {
                    percentagePower = 1;
                }
            }
            int pixelX = (int) Math.round(widthDivSize * i);
            if (pixelX < 0) pixelX = 0;
            if (pixelX >= drawMaxBuffer.length) pixelX = drawMaxBuffer.length - 1;
            if (percentagePower > drawMaxBuffer[pixelX]) {
                drawMaxBuffer[pixelX] = (float) percentagePower;
            }
        }
    }

    /**
     * Paint one row of normalised palette positions into the tier's
     * ring buffer at the new "top" position, then advance the topRow
     * pointer.
     */
    private void writeRowToTier(Tier tier, float[] row, ColorPalette p) {
        if (tier.buffer == null || tier.height <= 0) return;

        // Advance the ring pointer one row "up" (visually): the next
        // displayed top row will be the one we are about to paint.
        tier.topRow = (tier.topRow - 1 + tier.height) % tier.height;

        Graphics2D g = tier.buffer.createGraphics();
        try {
            // Black-fill the destination row first so any column we
            // don't touch shows the empty colour rather than stale
            // ring-buffer data.
            g.setColor(Color.black);
            g.fillRect(0, tier.topRow, tier.width, 1);

            Color lastValidColor = p.getColor(0);
            Rectangle2D.Float rect = new Rectangle2D.Float(0f, (float) tier.topRow, 1f, 0f);
            int n = Math.min(row.length, tier.width);
            for (int x = 0; x < n; x++) {
                Color color;
                if (row[x] == MINIMUM_DRAW_BUFFER_VALUE) {
                    color = lastValidColor;
                } else {
                    color = p.getColorNormalized(row[x]);
                    lastValidColor = color;
                }
                rect.x = x;
                g.setColor(color);
                g.draw(rect);
            }
        } finally {
            g.dispose();
        }
    }

    /**
     * Swap the colour ramp used for new pixels. Old scrollback rows
     * keep the colours they were painted with; the boundary line
     * between "old palette" and "new palette" is actually a useful
     * visual cue that the user just changed the theme.
     */
    public void setPalette(ColorPalette newPalette) {
        if (newPalette == null) return;
        this.palette = newPalette;
        requestPaint();
    }

    /**
     * Clear all scrollback so old pixels disappear immediately.
     * Useful when the user changes the frequency range or hits
     * "Clear traces".
     */
    public synchronized void clearHistory() {
        if (flatTier != null && flatTier.buffer != null) {
            Graphics2D g = flatTier.buffer.createGraphics();
            try {
                g.setColor(Color.BLACK);
                g.fillRect(0, 0, flatTier.buffer.getWidth(), flatTier.buffer.getHeight());
            } finally {
                g.dispose();
            }
            flatTier.topRow = 0;
        }
        if (funnelHistory != null) {
            for (float[] row : funnelHistory) {
                if (row != null) Arrays.fill(row, MINIMUM_DRAW_BUFFER_VALUE);
            }
            funnelTop = 0;
            funnelCount = 0;
        }
        Arrays.fill(drawMaxBuffer, MINIMUM_DRAW_BUFFER_VALUE);
        requestPaint();
    }

    public void requestPaint() {
        if (Platform.isFxApplicationThread()) {
            paint();
        } else {
            Platform.runLater(this::paint);
        }
    }

    private synchronized void paint() {
        long t0 = PERF ? System.nanoTime() : 0L;
        GraphicsContext gc = getGraphicsContext2D();
        double w = getWidth();
        double h = getHeight();
        if (w < 1 || h < 1) return;

        gc.setFill(javafx.scene.paint.Color.BLACK);
        gc.fillRect(0, 0, w, h);

        int destW = Math.max(1, chartWidth);
        int totalCanvasH = (int) Math.round(h);

        if (funnelEnabled) {
            paintFunnel(gc, chartXOffset, destW, totalCanvasH);
        } else if (flatTier != null) {
            paintTier(gc, flatTier, chartXOffset, 0, destW, totalCanvasH);
        }
        if (PERF) paintPerf.record(System.nanoTime() - t0);
    }

    /**
     * Render the funnel mode by mapping each screen row to a
     * non-linear interval of source rows and max-holding them
     * together. The mapping is monotonically increasing with
     * derivative growing as a power of {@link #FUNNEL_EASING_POWER},
     * so the top of the canvas stays 1:1 with the live row and the
     * bottom compresses many seconds of history into each pixel.
     *
     * <p>Output goes through a reusable {@link BufferedImage} written
     * with raw int-RGB pixel access (one {@code System.arraycopy} of
     * the {@link ColorPalette} per row beats {@code fillRect}-per-pixel
     * by ~30x for typical waterfall widths) and then a single
     * {@code drawImage} blit to the canvas.
     */
    private void paintFunnel(GraphicsContext gc, int destX, int destW, int destH) {
        if (funnelHistory == null || funnelDepth <= 0 || destW <= 0 || destH <= 0) return;
        if (funnelMaxRow == null || funnelMaxRow.length != funnelWidth) {
            funnelMaxRow = new float[funnelWidth];
        }
        if (funnelOutImage == null
                || funnelOutImage.getWidth() != funnelWidth
                || funnelOutImage.getHeight() != destH) {
            funnelOutImage = new BufferedImage(funnelWidth, destH, BufferedImage.TYPE_INT_RGB);
            funnelOutPixels = ((DataBufferInt) funnelOutImage.getRaster().getDataBuffer()).getData();
        }
        ColorPalette p = palette;
        int blackRgb = Color.BLACK.getRGB();
        int n = Math.min(funnelMaxRow.length, funnelWidth);

        for (int y = 0; y < destH; y++) {
            // Map [y/destH, (y+1)/destH] -> source-row interval [r0,r1)
            // through f(t) = t^FUNNEL_EASING_POWER. Top of the canvas
            // (small y) collapses to a tiny source range = live
            // detail; bottom (y near destH) widens to many source
            // rows = compressed history.
            double f0 = (double) y / destH;
            double f1 = (double) (y + 1) / destH;
            int r0 = (int) Math.floor(funnelDepth * Math.pow(f0, FUNNEL_EASING_POWER));
            int r1 = (int) Math.ceil (funnelDepth * Math.pow(f1, FUNNEL_EASING_POWER));
            if (r1 <= r0) r1 = r0 + 1;
            if (r0 >= funnelCount) {
                int lineStart = y * funnelWidth;
                Arrays.fill(funnelOutPixels, lineStart, lineStart + funnelWidth, blackRgb);
                continue;
            }
            if (r1 > funnelCount) r1 = funnelCount;

            // Max-hold the source-row interval into funnelMaxRow.
            // Single-row fast path skips the inner max comparison.
            float[] firstSrc = funnelHistory[(funnelTop + r0) % funnelDepth];
            if (r1 - r0 == 1) {
                System.arraycopy(firstSrc, 0, funnelMaxRow, 0, n);
            } else {
                System.arraycopy(firstSrc, 0, funnelMaxRow, 0, n);
                for (int r = r0 + 1; r < r1; r++) {
                    float[] src = funnelHistory[(funnelTop + r) % funnelDepth];
                    if (src == null) continue;
                    int m = Math.min(n, src.length);
                    for (int x = 0; x < m; x++) {
                        if (src[x] > funnelMaxRow[x]) funnelMaxRow[x] = src[x];
                    }
                }
            }

            // Paletteise into the destination scanline.
            int lineStart = y * funnelWidth;
            int lastRgb = blackRgb;
            for (int x = 0; x < n; x++) {
                int rgb;
                if (funnelMaxRow[x] == MINIMUM_DRAW_BUFFER_VALUE) {
                    rgb = lastRgb;
                } else {
                    rgb = p.getColorNormalized(funnelMaxRow[x]).getRGB();
                    lastRgb = rgb;
                }
                funnelOutPixels[lineStart + x] = rgb;
            }
        }

        WritableImage img = SwingFXUtils.toFXImage(funnelOutImage, null);
        gc.drawImage(img, 0, 0, funnelWidth, destH, destX, 0, destW, destH);
    }

    /**
     * Render one tier's ring buffer into a horizontal strip of the
     * canvas. Two-slice unwrap: the logical "top" of the tier is the
     * physical row {@code topRow}, so we draw {@code [topRow..H)}
     * first as the upper portion of the strip, then {@code [0..topRow)}
     * as the lower portion. When {@code topRow == 0} the second slice
     * has zero height and the first call alone draws the entire buffer.
     */
    private void paintTier(GraphicsContext gc, Tier tier,
                           int destX, int destY, int destW, int destH) {
        BufferedImage source = tier.buffer;
        if (source == null) return;
        // Reuse the same FX image surface across tiers when sizes
        // happen to match; otherwise SwingFXUtils.toFXImage will
        // allocate a fresh one. Per-tier allocation here is rare
        // enough (only on resize / palette change) to ignore.
        WritableImage img = SwingFXUtils.toFXImage(source, null);
        int srcW = source.getWidth();
        int srcH = source.getHeight();
        int top = tier.topRow;
        int topSliceH = srcH - top;
        int destSplit = (top == 0) ? destH
                : (int) Math.round((double) topSliceH / srcH * destH);
        if (destSplit > destH) destSplit = destH;
        if (destSplit < 0) destSplit = 0;

        if (destSplit > 0) {
            gc.drawImage(img,
                    0, top, srcW, topSliceH,
                    destX, destY, destW, destSplit);
        }
        if (destSplit < destH && top > 0) {
            gc.drawImage(img,
                    0, 0, srcW, top,
                    destX, destY + destSplit, destW, destH - destSplit);
        }
    }

    /**
     * Handle a chart-area or canvas resize. Fires every frame during a
     * SplitPane drag, so we just record the desired size and (re)start
     * the debounce timer; the actual buffer reallocation runs once
     * after the user stops dragging. Until then {@link #paint()}
     * stretches the existing buffers to fit the new chart width.
     */
    private void resizeBuffers() {
        double fxHeight = getHeight();
        int newWidth = Math.max(MIN_BUFFER_WIDTH, chartWidth);
        int newHeight = Math.max(MIN_BUFFER_HEIGHT, (int) Math.round(fxHeight));
        if (newWidth == drawMaxBuffer.length && newHeight == totalBufferHeight) {
            return;
        }
        pendingWidth = newWidth;
        pendingHeight = newHeight;
        if (Platform.isFxApplicationThread()) {
            resizeDebouncer.playFromStart();
        } else {
            Platform.runLater(resizeDebouncer::playFromStart);
        }
        requestPaint();
    }

    private synchronized void applyPendingResize() {
        if (pendingWidth == drawMaxBuffer.length && pendingHeight == totalBufferHeight) return;
        rebuildBuffers(pendingWidth, pendingHeight, false);
        requestPaint();
    }

    /**
     * (Re)allocate the active mode's buffers for the new size. The
     * inactive mode is released so we don't pay the memory cost of
     * the funnel ring while in flat mode (or vice versa). When
     * {@code dropHistory} is {@code false} the flat-mode tier
     * inherits its previous contents via NN scaling so a resize
     * doesn't wipe the visible waterfall; the funnel ring is always
     * dropped on resize because remapping the easing-curved history
     * to a new depth would visibly tear the timeline.
     */
    private synchronized void rebuildBuffers(int newWidth, int newHeight, boolean dropHistory) {
        if (drawMaxBuffer.length != newWidth) {
            drawMaxBuffer = new float[newWidth];
        }
        totalBufferHeight = newHeight;

        if (funnelEnabled) {
            // Release any flat-mode buffer to claw back its memory.
            flatTier = null;
            int newDepth = Math.max(MIN_BUFFER_HEIGHT, newHeight * FUNNEL_HISTORY_FACTOR);
            // Re-init the ring on width/depth change OR on explicit
            // history drop (mode toggle). We deliberately don't try
            // to remap old funnel rows into the new layout.
            boolean reinit = dropHistory
                    || funnelHistory == null
                    || funnelDepth != newDepth
                    || funnelWidth != newWidth;
            if (reinit) {
                funnelDepth = newDepth;
                funnelWidth = newWidth;
                funnelHistory = new float[funnelDepth][];
                funnelTop = 0;
                funnelCount = 0;
                funnelOutImage = null;
                funnelOutPixels = null;
                funnelMaxRow = null;
            }
        } else {
            // Release funnel state so the mode swap actually frees memory.
            funnelHistory = null;
            funnelOutImage = null;
            funnelOutPixels = null;
            funnelMaxRow = null;
            funnelCount = 0;
            funnelDepth = 0;
            funnelWidth = 0;

            Tier prev = (dropHistory ? null : flatTier);
            Tier t = new Tier();
            t.width = newWidth;
            t.height = newHeight;
            t.buffer = allocateAndCopy(newWidth, newHeight, prev);
            t.topRow = 0;
            flatTier = t;
        }
    }

    /**
     * Allocate a fresh tier-sized image. When {@code previous} is
     * non-null its contents are <em>unwrapped</em> (most-recent-first)
     * and copied into the new image with NN scaling so the user keeps
     * seeing this tier's history across resizes. The new image is in
     * "topRow=0" form, so the caller resets {@code topRow}.
     */
    private BufferedImage allocateAndCopy(int newWidth, int newHeight, Tier previous) {
        BufferedImage img = GraphicsToolkit.createAcceleratedImageOpaque(newWidth, newHeight);
        Graphics2D g = img.createGraphics();
        try {
            g.setColor(Color.BLACK);
            g.fillRect(0, 0, newWidth, newHeight);
            if (previous != null && previous.buffer != null) {
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                        RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
                BufferedImage src = previous.buffer;
                int oldW = src.getWidth();
                int oldH = src.getHeight();
                int copyH = Math.min(oldH, newHeight);
                int prevTop = previous.topRow;
                int topSliceH = Math.min(oldH - prevTop, copyH);
                if (topSliceH > 0) {
                    g.drawImage(src,
                            0, 0, newWidth, topSliceH,
                            0, prevTop, oldW, prevTop + topSliceH,
                            null);
                }
                int bottomSliceH = copyH - topSliceH;
                if (bottomSliceH > 0) {
                    g.drawImage(src,
                            0, topSliceH, newWidth, copyH,
                            0, 0, oldW, bottomSliceH,
                            null);
                }
            }
        } finally {
            g.dispose();
        }
        return img;
    }

    public void setBackgroundPaint(Paint paint) {
        getGraphicsContext2D().setFill(paint);
    }

    public DatasetSpectrum getLastSpectrum() {
        return lastSpectrum;
    }
}
