package jspectrumanalyzer.fx.ui;

import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.image.PixelWriter;
import javafx.scene.image.WritableImage;
import javafx.scene.paint.Color;
import jspectrumanalyzer.fx.model.SettingsStore;
import jspectrumanalyzer.ui.ColorPalette;
import jspectrumanalyzer.ui.WaterfallPalette;
import jspectrumanalyzer.wifi.DensityHistogramService;

/**
 * Canvas that renders {@link DensityHistogramService.Snapshot}s as a
 * Chanalyzer-style density chart: vertical streaks where signals
 * persist, faint smears where they were transient.
 *
 * <h2>Render strategy</h2>
 * The histogram cells are blitted into a {@link WritableImage} sized to
 * the data ({@code width × HEIGHT}), then the canvas scales the image
 * to its current pixel size when drawing - so the view automatically
 * stretches with the parent container and we never have to recompute
 * cell sizes in pixels. JavaFX's image scaling does the right thing
 * for "pixelated" data here because the input is a count grid, not a
 * photograph.
 *
 * <h2>Colour mapping</h2>
 * Counts are mapped through {@code log(1+count) / log(1+maxCount)} to
 * cope with the heavy-tailed distribution of cell counts (a few
 * persistent bins dwarf the rest by orders of magnitude). The
 * normalised value is then passed through whichever
 * {@link WaterfallPalette} the user picked in the Display tab so the
 * density chart and the waterfall always speak the same colour
 * language. Empty cells are painted in the canvas background colour so
 * there is no abrupt "edge of data" line.
 *
 * <h2>Why one snapshot per render</h2>
 * The service publishes one snapshot per spectrum frame; we re-paint
 * the canvas on every snapshot. The image allocation is cheap (a
 * single {@code int[]} the size of the data) compared to the
 * sweep-loop work we are mirroring, and there is no incremental
 * advantage from a ring buffer here because every frame contributes to
 * every column simultaneously - we cannot "scroll" a row in like the
 * waterfall does.
 */
public final class DensityChartView extends Canvas {

    private static final Color BG = Color.web("#16161a");
    private static final Color GRID = Color.web("#2a2a30");
    private static final Color LABEL = Color.web("#cccccc");

    private static final double LEFT_PADDING = 32d;
    private static final double TOP_PADDING = 6d;
    private static final double BOTTOM_PADDING = 18d;
    private static final double RIGHT_PADDING = 8d;

    private final SettingsStore settings;

    private DensityHistogramService.Snapshot snapshot =
            new DensityHistogramService.Snapshot(0,
                    DensityHistogramService.HEIGHT,
                    new int[0], 0, 0, 0,
                    DensityHistogramService.TOP_DBM,
                    DensityHistogramService.TOP_DBM
                            - DensityHistogramService.HEIGHT_DBM, 0);

    /**
     * The palette used to paint counts. Snapshotted once per
     * {@link #setSnapshot} call so a concurrent palette change cannot
     * flip the colour ramp mid-render. The palette is created lazily
     * and re-created when the user picks a different one in the
     * Display tab.
     */
    private ColorPalette palette;
    private WaterfallPalette paletteEnum;

    /**
     * Reused image buffer so we do not allocate a fresh
     * {@link WritableImage} every snapshot. {@code null} until the
     * first non-empty snapshot lands; re-allocated when the snapshot
     * width changes.
     */
    private WritableImage image;
    private int imageWidth;
    private int imageHeight;

    public DensityChartView(SettingsStore settings) {
        this.settings = settings;
        widthProperty().addListener((obs, o, n) -> redraw());
        heightProperty().addListener((obs, o, n) -> redraw());
        // Initial palette mirrors the user's current Display-tab pick;
        // we listen for changes so re-theming the waterfall also
        // re-themes the density chart, no extra setting needed.
        refreshPalette();
        settings.getWaterfallTheme().addListener(() -> {
            refreshPalette();
            redraw();
        });
    }

    public void setSnapshot(DensityHistogramService.Snapshot snap) {
        if (snap == null) return;
        this.snapshot = snap;
        rebuildImage();
        redraw();
    }

    @Override public boolean isResizable() { return true; }
    @Override public double prefWidth(double h)  { return 600; }
    @Override public double prefHeight(double w) { return 220; }
    @Override public double minHeight(double w)  { return 180; }

    private void refreshPalette() {
        WaterfallPalette wp = settings.getWaterfallTheme().getValue();
        if (wp == null) wp = WaterfallPalette.HOT_IRON_BLUE;
        if (wp != paletteEnum || palette == null) {
            paletteEnum = wp;
            palette = wp.create();
        }
    }

    /**
     * Build (or refresh) the {@link WritableImage} that backs the
     * heatmap. The image is sized to the snapshot grid so JavaFX's
     * scale-on-draw step does the pixel-stretching work for free,
     * which keeps the per-frame CPU cost essentially constant even on
     * narrow chart widths.
     */
    private void rebuildImage() {
        int w = snapshot.width();
        int h = snapshot.height();
        if (w <= 0 || h <= 0) {
            image = null;
            return;
        }
        if (image == null || imageWidth != w || imageHeight != h) {
            image = new WritableImage(w, h);
            imageWidth = w;
            imageHeight = h;
        }
        PixelWriter pw = image.getPixelWriter();
        int max = snapshot.maxCount();
        // log(1+max) is the normaliser; clamp at 1 so the divide stays
        // safe before any data has accumulated.
        double logMax = Math.log1p(Math.max(1, max));
        Color bg = BG;
        int bgArgb = toArgb(bg, 1.0);
        // Render row-major for cache locality; row 0 is the top dBm row
        // (strongest), which is also the orientation Chanalyzer uses.
        refreshPalette();
        ColorPalette p = palette;
        int[] grid = snapshot.grid();
        for (int y = 0; y < h; y++) {
            int rowBase = y * w;
            for (int x = 0; x < w; x++) {
                int count = grid[rowBase + x];
                int argb;
                if (count <= 0) {
                    argb = bgArgb;
                } else {
                    double norm = Math.log1p(count) / logMax;
                    if (norm < 0) norm = 0;
                    else if (norm > 1) norm = 1;
                    argb = toArgb(p.getColorNormalized(norm), 1.0);
                }
                pw.setArgb(x, y, argb);
            }
        }
    }

    private static int toArgb(java.awt.Color c, double alpha) {
        int a = (int) Math.round(255 * Math.max(0, Math.min(1, alpha)));
        return (a << 24) | ((c.getRed() & 0xff) << 16)
                | ((c.getGreen() & 0xff) << 8) | (c.getBlue() & 0xff);
    }

    private static int toArgb(Color c, double alpha) {
        int a = (int) Math.round(255 * Math.max(0, Math.min(1, alpha)));
        int r = (int) Math.round(255 * c.getRed());
        int g = (int) Math.round(255 * c.getGreen());
        int b = (int) Math.round(255 * c.getBlue());
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    private void redraw() {
        double w = getWidth();
        double h = getHeight();
        if (w <= 0 || h <= 0) return;
        GraphicsContext g = getGraphicsContext2D();
        g.setFill(BG);
        g.fillRect(0, 0, w, h);

        double plotX = LEFT_PADDING;
        double plotY = TOP_PADDING;
        double plotW = Math.max(1, w - LEFT_PADDING - RIGHT_PADDING);
        double plotH = Math.max(1, h - TOP_PADDING - BOTTOM_PADDING);

        // Plot border
        g.setStroke(GRID);
        g.setLineWidth(1);
        g.strokeRect(plotX, plotY, plotW, plotH);

        if (image != null && snapshot.width() > 0) {
            g.drawImage(image, plotX, plotY, plotW, plotH);
        } else {
            g.setFill(LABEL);
            g.fillText("Waiting for sweep data...",
                    plotX + 8, plotY + plotH / 2);
            return;
        }

        drawAxes(g, plotX, plotY, plotW, plotH);
    }

    /**
     * Minimal axes: dBm gridlines + labels on the left, frequency labels
     * across the bottom (start / mid / end). We deliberately do not
     * mirror the main spectrum chart's full axis treatment - the
     * density chart's job is to show shape, not absolute readings, and
     * a tighter axis lets us pack more chart into the available space.
     */
    private void drawAxes(GraphicsContext g, double x, double y,
                          double w, double h) {
        g.setStroke(GRID);
        g.setFill(LABEL);
        g.setLineWidth(0.5);
        // dBm gridlines every 20 dB
        double topDbm = snapshot.topDbm();
        double botDbm = snapshot.bottomDbm();
        for (double d = Math.ceil(topDbm / 20) * 20; d >= botDbm; d -= 20) {
            double frac = (topDbm - d) / (topDbm - botDbm);
            double yy = y + frac * h;
            g.strokeLine(x, yy, x + w, yy);
            g.fillText(String.format("%.0f", d), 4, yy + 4);
        }
        // Frequency labels: start, mid, end (rounded MHz)
        double startMHz = snapshot.startMHz();
        double stopMHz = snapshot.stopMHz();
        if (stopMHz <= startMHz) return;
        g.fillText(String.format("%.0f MHz", startMHz),
                x, y + h + 14);
        String mid = String.format("%.0f MHz", (startMHz + stopMHz) / 2);
        g.fillText(mid, x + w / 2 - 30, y + h + 14);
        String end = String.format("%.0f MHz", stopMHz);
        g.fillText(end, x + w - 60, y + h + 14);
    }
}
