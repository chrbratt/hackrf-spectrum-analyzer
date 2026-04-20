package jspectrumanalyzer.fx.ui;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import javafx.geometry.VPos;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.image.PixelWriter;
import javafx.scene.image.WritableImage;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.TextAlignment;
import jspectrumanalyzer.fx.model.SettingsStore;
import jspectrumanalyzer.ui.ColorPalette;
import jspectrumanalyzer.ui.WaterfallPalette;
import jspectrumanalyzer.wifi.DensityHistogramService;
import jspectrumanalyzer.wifi.WifiAccessPoint;
import jspectrumanalyzer.wifi.WifiChannelCatalog;
import jspectrumanalyzer.wifi.capture.BeaconStore;

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
 *
 * <h2>Channel + SSID overlay</h2>
 * A header strip above the heatmap labels each Wi-Fi channel that
 * falls inside the visible {@code [startMHz, stopMHz]} range with its
 * channel number, and lists the strongest SSIDs sitting on that
 * channel (using the latest scan snapshot fed in via
 * {@link #setAccessPoints(List)}). Hidden SSIDs are resolved through
 * {@link BeaconStore} when monitor-mode capture is running. Hovering
 * over a column reveals the full SSID list as a tooltip so the chart
 * stays uncluttered when many APs share a channel.
 */
public final class DensityChartView extends Canvas {

    private static final Color BG = Color.web("#16161a");
    private static final Color GRID = Color.web("#2a2a30");
    private static final Color LABEL = Color.web("#cccccc");
    private static final Color CHANNEL_TICK = Color.web("#555562");
    private static final Color CHANNEL_LABEL = Color.web("#9aa0b4");
    private static final Color SSID_LABEL = Color.web("#e2e2ea");
    private static final Color TOOLTIP_BG = Color.web("#0c0c12", 0.92);
    private static final Color TOOLTIP_BORDER = Color.web("#3a3a48");

    private static final double LEFT_PADDING = 32d;
    private static final double TOP_PADDING_BASE = 6d;
    private static final double BOTTOM_PADDING = 18d;
    private static final double RIGHT_PADDING = 8d;

    /** Height of the channel-number row inside the top header strip. */
    private static final double CHANNEL_ROW_H = 12d;
    /** Vertical pitch between stacked SSID labels. */
    private static final double SSID_ROW_H = 11d;
    /** Maximum number of SSID rows we stack per channel before truncating to "+N". */
    private static final int MAX_SSID_ROWS = 3;
    /** Maximum SSID character length before ellipsising. */
    private static final int MAX_SSID_CHARS = 12;

    private final SettingsStore settings;
    private final BeaconStore beaconStore;

    private DensityHistogramService.Snapshot snapshot =
            new DensityHistogramService.Snapshot(0,
                    DensityHistogramService.HEIGHT,
                    new int[0], 0, 0, 0,
                    DensityHistogramService.TOP_DBM,
                    DensityHistogramService.TOP_DBM
                            - DensityHistogramService.HEIGHT_DBM, 0);

    /**
     * Latest AP scan snapshot. Empty list means "no data yet" - the
     * heatmap still renders, we just skip the channel/SSID overlay.
     * Volatile is enough because writes happen on the FX thread (from
     * the scan listener) and reads happen on the FX thread (during
     * {@link #redraw()}); the field is just a defensive guard against
     * any future call from a non-FX thread.
     */
    private volatile List<WifiAccessPoint> accessPoints = List.of();

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

    /** Mouse position tracked for the on-hover tooltip; -1 = no hover. */
    private double hoverX = -1;
    private double hoverY = -1;

    public DensityChartView(SettingsStore settings, BeaconStore beaconStore) {
        this.settings = settings;
        this.beaconStore = beaconStore;
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

        setOnMouseMoved(e -> { hoverX = e.getX(); hoverY = e.getY(); redraw(); });
        setOnMouseExited(e -> { hoverX = -1; hoverY = -1; redraw(); });
    }

    public void setSnapshot(DensityHistogramService.Snapshot snap) {
        if (snap == null) return;
        this.snapshot = snap;
        rebuildImage();
        redraw();
    }

    /**
     * Update the access-point list used by the channel/SSID overlay.
     * Pass an empty list to hide the overlay (e.g. when the OS Wi-Fi
     * scan is unavailable). The view filters internally to whatever
     * frequency range the current density snapshot covers, so callers
     * can hand in the unfiltered scan snapshot without preprocessing.
     */
    public void setAccessPoints(List<WifiAccessPoint> aps) {
        this.accessPoints = (aps == null) ? List.of() : aps;
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

        // Compute overlay rows from current AP snapshot + visible range
        // first so we know how much top padding to reserve. This keeps
        // the heatmap area honest: when there are no APs in the view we
        // give the full height to the data; when many APs stack on the
        // same channel we shrink the heatmap to fit the labels rather
        // than overdraw on top of the streaks.
        Overlay overlay = buildOverlay();
        double topPad = TOP_PADDING_BASE + overlay.headerHeight();

        double plotX = LEFT_PADDING;
        double plotY = topPad;
        double plotW = Math.max(1, w - LEFT_PADDING - RIGHT_PADDING);
        double plotH = Math.max(1, h - topPad - BOTTOM_PADDING);

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
        drawChannelOverlay(g, overlay, plotX, plotY, plotW);
        drawHoverTooltip(g, overlay, plotX, plotY, plotW, plotH);
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

    // -------------------------------------------------------- Channel + SSID overlay

    /**
     * Per-channel overlay slot: which channel, where to draw it, and
     * the AP list (already sorted by RSSI, strongest first) that backs
     * the stacked SSID labels and the on-hover tooltip.
     */
    private record OverlaySlot(WifiChannelCatalog.Channel channel,
                               List<WifiAccessPoint> aps) { }

    private record Overlay(List<OverlaySlot> slots, int maxRows) {
        double headerHeight() {
            if (slots.isEmpty()) return 0;
            return CHANNEL_ROW_H + Math.max(1, maxRows) * SSID_ROW_H + 2;
        }
    }

    private Overlay buildOverlay() {
        double startMHz = snapshot.startMHz();
        double stopMHz = snapshot.stopMHz();
        if (stopMHz <= startMHz) return new Overlay(List.of(), 0);

        List<WifiAccessPoint> aps = accessPoints;
        if (aps.isEmpty()) return new Overlay(List.of(), 0);

        // Group APs by their nearest catalog channel inside the visible
        // range. We bucket on the AP's *primary* centre frequency (not
        // the bonded centre) so wide 80/160 MHz APs slot into their
        // primary channel header, which matches what the AP table and
        // ApMarkerCanvas show. APs whose primary falls outside the
        // visible span are dropped on the floor.
        Map<Integer, List<WifiAccessPoint>> byChannel = new LinkedHashMap<>();
        for (WifiAccessPoint ap : aps) {
            int mhz = ap.centerFrequencyMhz();
            if (mhz < startMHz || mhz > stopMHz) continue;
            WifiChannelCatalog.Channel ch = nearestChannel(mhz);
            if (ch == null) continue;
            byChannel.computeIfAbsent(ch.number(), k -> new ArrayList<>()).add(ap);
        }
        if (byChannel.isEmpty()) return new Overlay(List.of(), 0);

        List<OverlaySlot> slots = new ArrayList<>(byChannel.size());
        int maxRows = 0;
        for (Map.Entry<Integer, List<WifiAccessPoint>> e : byChannel.entrySet()) {
            List<WifiAccessPoint> bucket = e.getValue();
            bucket.sort(Comparator.comparingInt(WifiAccessPoint::rssiDbm).reversed());
            WifiChannelCatalog.Channel ch = lookupChannel(e.getKey(), bucket.get(0));
            if (ch == null) continue;
            slots.add(new OverlaySlot(ch, bucket));
            int rows = Math.min(bucket.size(), MAX_SSID_ROWS);
            if (rows > maxRows) maxRows = rows;
        }
        // Stable order: left-to-right by channel centre so the header
        // strip reads in the same direction as the heatmap underneath.
        slots.sort(Comparator.comparingInt(s -> s.channel().centerMhz()));
        return new Overlay(slots, maxRows);
    }

    /**
     * Find the catalog channel whose 20 MHz primary span covers
     * {@code mhz}, or the closest centre if no exact span match (keeps
     * us robust against scan reports rounded to the nearest MHz).
     */
    private static WifiChannelCatalog.Channel nearestChannel(int mhz) {
        WifiChannelCatalog.Channel best = null;
        int bestDist = Integer.MAX_VALUE;
        for (WifiChannelCatalog.Channel ch : WifiChannelCatalog.ALL) {
            if (mhz >= ch.lowMhz() && mhz <= ch.highMhz()) return ch;
            int dist = Math.abs(mhz - ch.centerMhz());
            if (dist < bestDist) { bestDist = dist; best = ch; }
        }
        // Reject runaway matches (>15 MHz off): means the AP is in a
        // band we have no catalog entry for and we should not invent
        // a channel for it.
        return (bestDist <= 15) ? best : null;
    }

    private static WifiChannelCatalog.Channel lookupChannel(int number, WifiAccessPoint hint) {
        WifiAccessPoint.Band band = hint.band();
        for (WifiChannelCatalog.Channel ch : WifiChannelCatalog.ALL) {
            if (ch.number() == number && ch.band() == band) return ch;
        }
        for (WifiChannelCatalog.Channel ch : WifiChannelCatalog.ALL) {
            if (ch.number() == number) return ch;
        }
        return null;
    }

    private void drawChannelOverlay(GraphicsContext g, Overlay overlay,
                                    double plotX, double plotY, double plotW) {
        if (overlay.slots().isEmpty()) return;
        double startMHz = snapshot.startMHz();
        double stopMHz = snapshot.stopMHz();
        double mhzPerPx = (stopMHz - startMHz) / plotW;
        if (mhzPerPx <= 0) return;

        Font baseFont = Font.font("Segoe UI", 9);
        g.setFont(baseFont);
        g.setTextAlign(TextAlignment.CENTER);
        g.setTextBaseline(VPos.TOP);

        for (OverlaySlot slot : overlay.slots()) {
            WifiChannelCatalog.Channel ch = slot.channel();
            double cx = plotX + (ch.centerMhz() - startMHz) / mhzPerPx;
            if (cx < plotX - 20 || cx > plotX + plotW + 20) continue;

            // Tick from the channel header down to the plot edge so
            // the eye links the SSID label to the heatmap column it
            // belongs to without us having to draw a full vertical
            // gridline (which would compete with the streaks).
            g.setStroke(CHANNEL_TICK);
            g.setLineWidth(0.7);
            g.strokeLine(cx, plotY - 2, cx, plotY);

            // Channel number row
            g.setFill(CHANNEL_LABEL);
            g.fillText("ch " + ch.number(), cx, TOP_PADDING_BASE);

            // Stacked SSID names - up to MAX_SSID_ROWS, then "+N more"
            List<WifiAccessPoint> bucket = slot.aps();
            double yRow = TOP_PADDING_BASE + CHANNEL_ROW_H;
            int shown = Math.min(bucket.size(), MAX_SSID_ROWS);
            int overflow = bucket.size() - shown;
            for (int i = 0; i < shown; i++) {
                WifiAccessPoint ap = bucket.get(i);
                String label = truncate(displaySsidFor(ap));
                if (i == shown - 1 && overflow > 0) {
                    label = label + " +" + overflow;
                }
                g.setFill(SSID_LABEL);
                g.fillText(label, cx, yRow);
                yRow += SSID_ROW_H;
            }
        }

        g.setTextAlign(TextAlignment.LEFT);
        g.setTextBaseline(VPos.BASELINE);
    }

    private void drawHoverTooltip(GraphicsContext g, Overlay overlay,
                                  double plotX, double plotY,
                                  double plotW, double plotH) {
        if (hoverX < plotX || hoverX > plotX + plotW
                || hoverY < plotY || hoverY > plotY + plotH) return;
        if (overlay.slots().isEmpty()) return;

        double startMHz = snapshot.startMHz();
        double stopMHz = snapshot.stopMHz();
        double mhzPerPx = (stopMHz - startMHz) / plotW;
        if (mhzPerPx <= 0) return;
        double cursorMhz = startMHz + (hoverX - plotX) * mhzPerPx;

        // Snap to the nearest channel slot under the cursor; if none
        // is within half a channel width we skip the tooltip rather
        // than show a misleading "ch ?".
        OverlaySlot best = null;
        double bestDist = Double.MAX_VALUE;
        for (OverlaySlot s : overlay.slots()) {
            double d = Math.abs(s.channel().centerMhz() - cursorMhz);
            if (d < bestDist) { bestDist = d; best = s; }
        }
        if (best == null || bestDist > 12) return;

        // Build tooltip lines: header + one line per AP (truncated),
        // showing RSSI so the user can correlate label brightness with
        // signal strength on the same chart.
        WifiChannelCatalog.Channel ch = best.channel();
        List<String> lines = new ArrayList<>();
        lines.add(ch.label());
        for (WifiAccessPoint ap : best.aps()) {
            lines.add(String.format("  %s  %d dBm",
                    truncateLong(displaySsidFor(ap)), ap.rssiDbm()));
        }

        // Measure with a fixed mono-ish font; FX has no width-of-string
        // helper without a Text node so we approximate at ~6 px/char
        // for our 9 pt UI font, which is close enough for tooltip box
        // sizing.
        Font tipFont = Font.font("Segoe UI", 10);
        g.setFont(tipFont);
        double charW = 6.0;
        double lineH = 13;
        double maxChars = 0;
        for (String l : lines) maxChars = Math.max(maxChars, l.length());
        double boxW = maxChars * charW + 12;
        double boxH = lines.size() * lineH + 8;

        // Position to the right of the cursor unless that would clip;
        // then flip to the left. Same vertical flipping at the bottom
        // edge so the tooltip never escapes the canvas.
        double bx = hoverX + 12;
        if (bx + boxW > plotX + plotW) bx = hoverX - 12 - boxW;
        double by = hoverY + 12;
        if (by + boxH > plotY + plotH) by = hoverY - 12 - boxH;
        if (bx < plotX) bx = plotX;
        if (by < plotY) by = plotY;

        g.setFill(TOOLTIP_BG);
        g.fillRect(bx, by, boxW, boxH);
        g.setStroke(TOOLTIP_BORDER);
        g.setLineWidth(0.7);
        g.strokeRect(bx, by, boxW, boxH);

        g.setFill(SSID_LABEL);
        g.setTextAlign(TextAlignment.LEFT);
        g.setTextBaseline(VPos.TOP);
        double ty = by + 4;
        for (String l : lines) {
            g.fillText(l, bx + 6, ty);
            ty += lineH;
        }
    }

    private String displaySsidFor(WifiAccessPoint ap) {
        if (!ap.ssid().isEmpty()) return ap.ssid();
        Optional<String> resolved = beaconStore.discoveredSsid(ap.bssid());
        return resolved.map(name -> "(hidden: " + name + ")").orElse("(hidden)");
    }

    private static String truncate(String s) {
        if (s == null) return "";
        if (s.length() <= MAX_SSID_CHARS) return s;
        return s.substring(0, MAX_SSID_CHARS - 1) + "…";
    }

    private static String truncateLong(String s) {
        if (s == null) return "";
        if (s.length() <= 28) return s;
        return s.substring(0, 27) + "…";
    }
}
