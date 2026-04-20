package jspectrumanalyzer.fx.ui;

import java.util.List;

import javafx.application.Platform;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import jspectrumanalyzer.wifi.WifiAccessPoint;
import jspectrumanalyzer.wifi.WifiScanService;
import jspectrumanalyzer.wifi.WifiScanService.RssiSample;

/**
 * Compact strip chart that visualises the rolling 60-second RSSI history of
 * a single Wi-Fi access point. Designed to live below the AP table in
 * {@link WifiWindow}; redraws itself on every fresh scan snapshot so the
 * line keeps tracking even while the user watches.
 *
 * <p>Empty state (nothing selected, or the selected BSSID has no samples
 * yet) renders a discreet placeholder so the strip never collapses to a
 * blank rectangle that the user mistakes for a layout bug.
 */
public final class ApTrendChart extends Canvas {

    private static final double Y_MIN_DBM = -100d;
    private static final double Y_MAX_DBM = -30d;
    private static final long X_WINDOW_MS = WifiScanService.HISTORY_WINDOW_MS;

    private static final Color BG_COLOR = Color.web("#16161a");
    private static final Color GRID_COLOR = Color.web("#2a2a30");
    private static final Color AXIS_LABEL_COLOR = Color.web("#888");
    private static final Color TITLE_COLOR = Color.web("#cccccc");
    private static final Color LINE_COLOR = Color.web("#5BE572");
    private static final Color PLACEHOLDER_COLOR = Color.web("#666");

    private final WifiScanService service;
    /**
     * Optional probe-response-derived SSID lookup. Used to render
     * "(hidden: name)" instead of the bare "(hidden)" placeholder for
     * APs whose real name has been recovered via monitor-mode capture.
     * {@code null} when no capture pipeline is wired up - we never
     * dereference without a guard.
     */
    private final jspectrumanalyzer.wifi.capture.BeaconStore beaconStore;

    /** Currently followed AP. {@code null} = render placeholder. */
    private WifiAccessPoint selected;

    public ApTrendChart(WifiScanService service,
                        jspectrumanalyzer.wifi.capture.BeaconStore beaconStore) {
        this.service = service;
        this.beaconStore = beaconStore;
        widthProperty().addListener((obs, o, n) -> redraw());
        heightProperty().addListener((obs, o, n) -> redraw());
    }

    /**
     * Switch to a new AP and trigger a redraw. Pass {@code null} to clear.
     * The history itself is owned by the {@link WifiScanService} so swapping
     * APs is cheap - no buffer copies, just a reference update.
     */
    public void setSelected(WifiAccessPoint ap) {
        this.selected = ap;
        redraw();
    }

    /**
     * Repaint hook that the {@link WifiWindow} can call from the snapshot
     * listener so the line keeps moving every second even if the table
     * selection has not changed.
     */
    public void refresh() {
        Platform.runLater(this::redraw);
    }

    private void redraw() {
        GraphicsContext g = getGraphicsContext2D();
        double w = getWidth();
        double h = getHeight();
        if (w < 4 || h < 4) return;

        g.setFill(BG_COLOR);
        g.fillRect(0, 0, w, h);

        double padLeft = 36;
        double padRight = 8;
        double padTop = 18;
        double padBottom = 18;
        double plotW = Math.max(0, w - padLeft - padRight);
        double plotH = Math.max(0, h - padTop - padBottom);

        Font small = Font.font("Dialog", FontWeight.NORMAL, 10);
        g.setFont(small);

        // Title row above the plot identifies the followed AP.
        g.setFill(TITLE_COLOR);
        if (selected == null) {
            g.fillText("Trend: select an AP in the table to follow its RSSI",
                    padLeft, padTop - 5);
        } else {
            String ssid = displaySsidFor(selected);
            g.fillText(String.format("Trend: %s  %s  ch %d",
                    ssid, selected.bssid(), selected.channel()),
                    padLeft, padTop - 5);
        }

        drawGrid(g, padLeft, padTop, plotW, plotH);

        if (selected == null) {
            g.setFill(PLACEHOLDER_COLOR);
            g.fillText("(no AP selected)", padLeft + plotW / 2 - 35, padTop + plotH / 2);
            return;
        }

        List<RssiSample> samples = service.getHistory(selected.bssid());
        if (samples.isEmpty()) {
            g.setFill(PLACEHOLDER_COLOR);
            g.fillText("(waiting for samples\u2026)", padLeft + plotW / 2 - 50, padTop + plotH / 2);
            return;
        }

        long now = System.currentTimeMillis();
        long startMs = now - X_WINDOW_MS;

        // Sample line. Use a stroke loop because samples may be spaced 1 s
        // apart but missing some ticks (AP off-air briefly): straight lines
        // still convey the trend correctly without misleading interpolation.
        g.setStroke(LINE_COLOR);
        g.setLineWidth(1.6);
        boolean first = true;
        double prevX = 0, prevY = 0;
        for (RssiSample s : samples) {
            if (s.timestampMs() < startMs) continue;
            double x = padLeft + (s.timestampMs() - startMs) / (double) X_WINDOW_MS * plotW;
            double y = padTop + (Y_MAX_DBM - clampDbm(s.rssiDbm())) / (Y_MAX_DBM - Y_MIN_DBM) * plotH;
            if (first) {
                first = false;
            } else {
                g.strokeLine(prevX, prevY, x, y);
            }
            prevX = x;
            prevY = y;
        }

        // Mark the latest sample with a small dot so the user can tell at a
        // glance whether the AP is still beaconing.
        if (!samples.isEmpty()) {
            RssiSample last = samples.get(samples.size() - 1);
            double x = padLeft + (last.timestampMs() - startMs) / (double) X_WINDOW_MS * plotW;
            double y = padTop + (Y_MAX_DBM - clampDbm(last.rssiDbm())) / (Y_MAX_DBM - Y_MIN_DBM) * plotH;
            g.setFill(LINE_COLOR);
            g.fillOval(x - 3, y - 3, 6, 6);
            g.setFill(TITLE_COLOR);
            g.fillText(last.rssiDbm() + " dBm",
                    Math.min(x + 6, padLeft + plotW - 50), y + 4);
        }
    }

    private static void drawGrid(GraphicsContext g,
                                 double padLeft, double padTop,
                                 double plotW, double plotH) {
        g.setStroke(GRID_COLOR);
        g.setLineWidth(1);
        Font small = Font.font("Dialog", FontWeight.NORMAL, 10);
        g.setFont(small);
        g.setFill(AXIS_LABEL_COLOR);

        // Y axis: dBm gridlines at every 20 dB.
        for (int dbm = (int) Y_MAX_DBM; dbm >= (int) Y_MIN_DBM; dbm -= 20) {
            double y = padTop + (Y_MAX_DBM - dbm) / (Y_MAX_DBM - Y_MIN_DBM) * plotH;
            g.strokeLine(padLeft, y, padLeft + plotW, y);
            g.fillText(dbm + "", padLeft - 30, y + 3);
        }

        // X axis: time gridlines every 15 s with "-Ns" labels under the plot.
        long step = 15_000L;
        for (long t = 0; t <= X_WINDOW_MS; t += step) {
            double x = padLeft + t / (double) X_WINDOW_MS * plotW;
            g.strokeLine(x, padTop, x, padTop + plotH);
            long secondsAgo = (X_WINDOW_MS - t) / 1000;
            String label = secondsAgo == 0 ? "now" : ("-" + secondsAgo + "s");
            g.fillText(label, x - 12, padTop + plotH + 12);
        }
    }

    /**
     * Pick the best display name for the followed AP - real SSID >
     * captured probe-response SSID > literal "(hidden)". Mirrors the
     * resolution used by the AP table and marker overlay so the user
     * sees one consistent name across all three views.
     */
    private String displaySsidFor(WifiAccessPoint ap) {
        if (!ap.ssid().isEmpty()) return ap.ssid();
        if (beaconStore != null) {
            return beaconStore.discoveredSsid(ap.bssid())
                    .map(name -> "(hidden: " + name + ")")
                    .orElse("(hidden)");
        }
        return "(hidden)";
    }

    private static double clampDbm(double v) {
        if (v < Y_MIN_DBM) return Y_MIN_DBM;
        if (v > Y_MAX_DBM) return Y_MAX_DBM;
        return v;
    }
}
