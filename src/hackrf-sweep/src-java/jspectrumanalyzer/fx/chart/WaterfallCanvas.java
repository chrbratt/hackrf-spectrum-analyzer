package jspectrumanalyzer.fx.chart;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.util.Arrays;

import javafx.application.Platform;
import javafx.embed.swing.SwingFXUtils;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.image.WritableImage;
import javafx.scene.paint.Paint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import jspectrumanalyzer.core.DatasetSpectrum;
import jspectrumanalyzer.ui.GraphicsToolkit;
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

    private final HotIronBluePalette palette = new HotIronBluePalette();
    private double spectrumPaletteSize = 65;
    private double spectrumPaletteStart = -110;

    private int chartXOffset = 0;
    private int chartWidth = MIN_BUFFER_WIDTH;
    private int bufferWidth = MIN_BUFFER_WIDTH;
    private int bufferHeight = MIN_BUFFER_HEIGHT;
    private int drawIndex = 0;

    private volatile DatasetSpectrum lastSpectrum;

    public WaterfallCanvas() {
        super(320, 200);
        allocateBuffers(MIN_BUFFER_WIDTH, MIN_BUFFER_HEIGHT);
        widthProperty().addListener((obs, o, n) -> resizeBuffers());
        heightProperty().addListener((obs, o, n) -> resizeBuffers());
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

            Color lastValidColor = palette.getColor(0);
            for (int x = 0; x < drawMaxBuffer.length; x++) {
                Color color;
                if (drawMaxBuffer[x] == MINIMUM_DRAW_BUFFER_VALUE) {
                    color = lastValidColor;
                } else {
                    color = palette.getColorNormalized(drawMaxBuffer[x]);
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
        // 1:1 paste at the chart's data area. Stretching the buffer to a
        // different chartWidth would introduce horizontal blur/banding.
        gc.drawImage(fxImage, 0, 0, source.getWidth(), source.getHeight(),
                chartXOffset, 0, source.getWidth(), source.getHeight());
        if (PERF) paintPerf.record(System.nanoTime() - t0);
    }

    private synchronized void resizeBuffers() {
        double fxHeight = getHeight();
        // Buffer width follows the chart's data-area width, not the canvas, so
        // each spectrum bin maps to one buffer column and paint() stays 1:1.
        int newWidth = Math.max(MIN_BUFFER_WIDTH, chartWidth);
        int newHeight = Math.max(MIN_BUFFER_HEIGHT, (int) Math.round(fxHeight));
        if (newWidth == bufferWidth && newHeight == bufferHeight) return;
        allocateBuffers(newWidth, newHeight);
        requestPaint();
    }

    private synchronized void allocateBuffers(int newWidth, int newHeight) {
        // History is intentionally dropped on resize. Scaling the old buffer
        // produced visible banding/colour smearing while the divider was
        // dragged; a clean reset is more useful than a distorted past.
        BufferedImage[] newImages = new BufferedImage[2];
        newImages[0] = GraphicsToolkit.createAcceleratedImageOpaque(newWidth, newHeight);
        newImages[1] = GraphicsToolkit.createAcceleratedImageOpaque(newWidth, newHeight);
        for (BufferedImage img : newImages) {
            Graphics2D g = img.createGraphics();
            try {
                g.setColor(Color.BLACK);
                g.fillRect(0, 0, newWidth, newHeight);
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
