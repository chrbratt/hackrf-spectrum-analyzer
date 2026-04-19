package jspectrumanalyzer.fx.chart;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.util.Arrays;

import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.embed.swing.SwingFXUtils;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.image.WritableImage;
import javafx.scene.paint.Paint;
import javafx.util.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import jspectrumanalyzer.core.DatasetSpectrum;
import jspectrumanalyzer.ui.GraphicsToolkit;
import jspectrumanalyzer.ui.ColorPalette;
import jspectrumanalyzer.ui.HotIronBluePalette;

/**
 * JavaFX port of {@code WaterfallPlot}.
 * <p>
 * Fixes the primary-screen-width bug: the internal {@link BufferedImage} ring buffer is
 * now sized to the actual canvas pixel width (and to the plot's left offset / width, set
 * through {@link #setDrawingOffsets(int, int)}). History is preserved on resize by
 * copying the old buffer into the new one.
 */
public final class WaterfallCanvas extends Canvas {

    private static final Logger LOG = LoggerFactory.getLogger(WaterfallCanvas.class);

    private static final int MIN_BUFFER_WIDTH = 64;
    private static final int MIN_BUFFER_HEIGHT = 32;
    private static final float MINIMUM_DRAW_BUFFER_VALUE = -150f;

    /**
     * Per-stage timing meter, opt-in via {@code -Dwaterfall.perf=true}.
     * Reports rolling avg/max/count once per second (rate-limited to keep
     * log noise predictable). When disabled, the only overhead is one
     * {@code System.nanoTime()} read per call - negligible compared to
     * the BufferedImage allocation work the path already does.
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

    private BufferedImage[] bufferedImages = new BufferedImage[2];
    private float[] drawMaxBuffer = new float[MIN_BUFFER_WIDTH];
    private WritableImage fxImage;

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
    private int bufferWidth = MIN_BUFFER_WIDTH;
    private int bufferHeight = MIN_BUFFER_HEIGHT;
    private int drawIndex = 0;

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
        allocateBuffers(MIN_BUFFER_WIDTH, MIN_BUFFER_HEIGHT, null);
        widthProperty().addListener((obs, o, n) -> resizeBuffers());
        heightProperty().addListener((obs, o, n) -> resizeBuffers());
        // When the debounce timer fires we promote the latest pending size
        // to a real reallocation. NN-scaling preserves the visible history
        // instead of clearing to black on every resize.
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
     * Push one spectrum into the ring buffer. Safe to call off the FX thread. After the
     * call, trigger {@link #requestPaint()} from any thread to schedule a repaint on the
     * FX thread.
     */
    public synchronized void addNewData(DatasetSpectrum spectrum) {
        if (spectrum == null) return;
        long t0 = PERF ? System.nanoTime() : 0L;
        this.lastSpectrum = spectrum;
        int size = spectrum.spectrumLength();
        double width = bufferedImages[0].getWidth();
        double spectrumPaletteMax = spectrumPaletteStart + spectrumPaletteSize;

        BufferedImage previousImage = bufferedImages[drawIndex];
        drawIndex = (drawIndex + 1) % 2;
        Graphics2D g = bufferedImages[drawIndex].createGraphics();
        try {
            g.drawImage(previousImage, 0, 1, null);
            g.setColor(Color.black);
            g.fillRect(0, 0, (int) width, 1);

            // Bin pixel width = canvas width / number of allocated bins. For
            // multi-segment plans this is the only formula that stays correct
            // (the legacy MHz-span formula counted gap regions as if they had
            // bins, shrinking each bin by the gap fraction). It also matches
            // widthDivSize below, which is what the per-bin loop already uses
            // to pick a target column.
            float binWidth = (float) ((double) width / size);
            Rectangle2D.Float rect = new Rectangle2D.Float(0f, 0f, binWidth, 0f);

            Arrays.fill(drawMaxBuffer, MINIMUM_DRAW_BUFFER_VALUE);
            double widthDivSize = width / size;
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

            // Snapshot the volatile palette once per row so a concurrent
            // setPalette() from the FX thread can't flip the colour ramp
            // mid-row (would manifest as a single-line discontinuity).
            ColorPalette p = palette;
            Color lastValidColor = p.getColor(0);
            for (int x = 0; x < drawMaxBuffer.length; x++) {
                Color color;
                if (drawMaxBuffer[x] == MINIMUM_DRAW_BUFFER_VALUE) {
                    color = lastValidColor;
                } else {
                    color = p.getColorNormalized(drawMaxBuffer[x]);
                    lastValidColor = color;
                }
                rect.x = x;
                g.setColor(color);
                g.draw(rect);
            }
        } finally {
            g.dispose();
        }
        if (PERF) addPerf.record(System.nanoTime() - t0);
    }

    /**
     * Swap the colour ramp used for new pixels. Old scrollback rows keep the
     * colours they were painted with - re-rasterising the historical buffer
     * with the new palette would also work, but it would briefly halt the
     * sweep (we'd have to lock the ring buffer) and the boundary line
     * between "old palette" and "new palette" is actually a useful visual
     * cue that the user just changed the theme.
     */
    public void setPalette(ColorPalette newPalette) {
        if (newPalette == null) return;
        this.palette = newPalette;
        requestPaint();
    }

    /**
     * Clear the waterfall ring buffer so old scrollback disappears immediately.
     * Useful when the user changes the frequency range or hits "Clear traces".
     */
    public synchronized void clearHistory() {
        for (BufferedImage img : bufferedImages) {
            if (img == null) continue;
            Graphics2D g = img.createGraphics();
            try {
                g.setColor(Color.BLACK);
                g.fillRect(0, 0, img.getWidth(), img.getHeight());
            } finally {
                g.dispose();
            }
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

        BufferedImage source = bufferedImages[drawIndex];
        fxImage = SwingFXUtils.toFXImage(source, fxImage);
        // Stretch the buffer horizontally to the current chart-data width
        // and vertically to the current canvas height. During a debounced
        // resize the buffer's native size lags the chart by up to ~150 ms;
        // stretching here keeps the history visible at the right footprint
        // until the deferred allocateBuffers() copies it into a fresh
        // properly-sized buffer. Vertical 1:1 isn't possible (the canvas
        // height has already changed) so we let JavaFX scale - any one-frame
        // smear from this paint is gone the next paint, no compounding.
        int destW = Math.max(1, chartWidth);
        int destH = (int) Math.round(h);
        gc.drawImage(fxImage, 0, 0, source.getWidth(), source.getHeight(),
                chartXOffset, 0, destW, destH);
        if (PERF) paintPerf.record(System.nanoTime() - t0);
    }

    /**
     * Handle a chart-area or canvas resize. Fires every frame during a
     * SplitPane drag, so we just record the desired size and (re)start the
     * debounce timer; the actual buffer reallocation runs once after the
     * user stops dragging. Until then {@link #paint()} stretches the existing
     * buffer to fit the new chart width.
     */
    private void resizeBuffers() {
        double fxHeight = getHeight();
        int newWidth = Math.max(MIN_BUFFER_WIDTH, chartWidth);
        int newHeight = Math.max(MIN_BUFFER_HEIGHT, (int) Math.round(fxHeight));
        if (newWidth == bufferWidth && newHeight == bufferHeight) {
            // No real change; but if the debounce timer is queued from an
            // earlier in-flight resize we let it run - cheap and correct.
            return;
        }
        pendingWidth = newWidth;
        pendingHeight = newHeight;
        if (Platform.isFxApplicationThread()) {
            resizeDebouncer.playFromStart();
        } else {
            Platform.runLater(resizeDebouncer::playFromStart);
        }
        // Repaint immediately so the user sees the buffer track the new
        // canvas size (stretched) instead of revealing the black background.
        requestPaint();
    }

    /**
     * Promote the latest pending size to a real buffer reallocation. Runs on
     * the FX thread (PauseTransition handler). Copies the previous buffer
     * into the new one with nearest-neighbor scaling so visible history is
     * preserved instead of being wiped.
     */
    private synchronized void applyPendingResize() {
        if (pendingWidth == bufferWidth && pendingHeight == bufferHeight) return;
        allocateBuffers(pendingWidth, pendingHeight, bufferedImages[drawIndex]);
        requestPaint();
    }

    /**
     * Allocate fresh buffers at the requested size. When {@code previous} is
     * non-null its contents are copied into the new buffer with NN scaling
     * (sharp, no smearing) so the user keeps seeing the waterfall history
     * across resizes. NN is critical here: bilinear scaling repeated 30 times
     * per second during a drag was the original "banding" that motivated the
     * legacy "always wipe" behaviour - one-shot NN doesn't compound.
     */
    private synchronized void allocateBuffers(int newWidth, int newHeight,
                                              BufferedImage previous) {
        BufferedImage[] newImages = new BufferedImage[2];
        newImages[0] = GraphicsToolkit.createAcceleratedImageOpaque(newWidth, newHeight);
        newImages[1] = GraphicsToolkit.createAcceleratedImageOpaque(newWidth, newHeight);
        for (BufferedImage img : newImages) {
            Graphics2D g = img.createGraphics();
            try {
                g.setColor(Color.BLACK);
                g.fillRect(0, 0, newWidth, newHeight);
                if (previous != null) {
                    // NEAREST_NEIGHBOR: stretches each old pixel column into
                    // the new column slot without averaging, so colours stay
                    // exactly what the palette painted them. Vertically we
                    // copy the top min(oldH, newH) rows untouched so the
                    // time axis doesn't get squished or stretched (rows = time).
                    g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                            RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
                    int copyH = Math.min(previous.getHeight(), newHeight);
                    g.drawImage(previous,
                            0, 0, newWidth, copyH,
                            0, 0, previous.getWidth(), copyH,
                            null);
                }
            } finally {
                g.dispose();
            }
        }
        bufferedImages = newImages;
        drawMaxBuffer = new float[newWidth];
        bufferWidth = newWidth;
        bufferHeight = newHeight;
        drawIndex = 0;
    }

    public void setBackgroundPaint(Paint paint) {
        getGraphicsContext2D().setFill(paint);
    }

    public DatasetSpectrum getLastSpectrum() {
        return lastSpectrum;
    }
}
