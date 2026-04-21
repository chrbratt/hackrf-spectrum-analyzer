package jspectrumanalyzer.fx.ui;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import javafx.geometry.Insets;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import jspectrumanalyzer.wifi.capture.BeaconStore;
import jspectrumanalyzer.wifi.capture.CaptureStats;
import jspectrumanalyzer.wifi.capture.ieee80211.BssLoad;

/**
 * Top-of-monitor-capture-panel insight card. Same pedagogy as
 * {@link WifiInsightCard} but for raw 802.11 capture: the user runs
 * monitor mode to learn things the OS-level scanner cannot expose, so
 * this card surfaces those things in plain English.
 *
 * <h2>Lines we render</h2>
 * <ol>
 *   <li><b>Headline</b>: capture state + duration + frames-per-second
 *       so the user can tell at a glance whether the capture is
 *       healthy (a stalled rfmon handle drops fps to 0; an unsupported
 *       chipset shows an unbroken zero counter even with the panel
 *       running).</li>
 *   <li><b>Frame mix</b>: percentage breakdown matching
 *       {@link FrameTypeBar} below it. The numbers are duplicated on
 *       purpose - the bar shows shape, the text spells it out for
 *       users who prefer reading.</li>
 *   <li><b>Top talker</b>: the BSSID with the most management frames
 *       (resolved to a friendly SSID via the shared
 *       {@link BeaconStore}). A dominant talker is usually a beacon-
 *       heavy AP; an unusual jump can mean a rogue or a broken AP
 *       hammering the channel.</li>
 *   <li><b>Air health</b>: how many APs are reporting >= 40% BSS Load
 *       channel utilization, and which one is worst. 40% is the
 *       Cisco-recommended "consider load balancing" threshold.</li>
 *   <li><b>Hidden SSIDs</b>: how many we've resolved this session,
 *       so the user knows whether to leave the capture running longer
 *       to catch more probe responses.</li>
 * </ol>
 *
 * <p>The card is a passive view: callers feed it a fresh snapshot per
 * UI tick; computation is purely functional and cheap.</p>
 */
public final class CaptureInsightCard extends VBox {

    private final BeaconStore beaconStore;

    private final Label headlineLabel = new Label();
    private final Label mixLabel = new Label();
    private final Label talkerLabel = new Label();
    private final Label loadLabel = new Label();
    private final Label hiddenLabel = new Label();

    public CaptureInsightCard(BeaconStore beaconStore) {
        this.beaconStore = beaconStore;
        getStyleClass().add("wifi-insight-card");
        setPadding(new Insets(10, 14, 10, 14));
        setSpacing(2);

        headlineLabel.getStyleClass().add("wifi-insight-headline");
        headlineLabel.setWrapText(true);
        for (Label l : List.of(mixLabel, talkerLabel, loadLabel, hiddenLabel)) {
            l.getStyleClass().add("wifi-insight-line");
            l.setWrapText(true);
        }
        VBox left = new VBox(2, mixLabel, talkerLabel);
        VBox right = new VBox(2, loadLabel, hiddenLabel);
        HBox.setHgrow(left, Priority.ALWAYS);
        HBox.setHgrow(right, Priority.ALWAYS);
        left.setMaxWidth(Double.MAX_VALUE);
        right.setMaxWidth(Double.MAX_VALUE);
        HBox cols = new HBox(16, left, right);

        getChildren().addAll(headlineLabel, cols);
        renderIdle();
    }

    /**
     * Push a new snapshot. Pass {@code running=false} so the headline
     * reads "Stopped." when the user has paused the capture - the
     * counts stay accurate but the user knows nothing is being added.
     */
    public void update(CaptureStats.Snapshot snap, boolean running) {
        if (snap == null || snap.total() <= 0) {
            renderIdle();
            return;
        }

        headlineLabel.setText(buildHeadline(snap, running));
        mixLabel.setText(buildMix(snap));
        talkerLabel.setText(buildTopTalker(snap));
        loadLabel.setText(buildLoad());
        hiddenLabel.setText(buildHidden());
    }

    private void renderIdle() {
        headlineLabel.setText("Capture idle. Click Start to begin.");
        mixLabel.setText("Frame mix: -");
        talkerLabel.setText("Top talker: -");
        loadLabel.setText("Air health: waiting for BSS Load advertisements...");
        hiddenLabel.setText("Hidden SSIDs resolved: 0");
    }

    // ---------------------------------------------------------------- text builders

    private static String buildHeadline(CaptureStats.Snapshot snap, boolean running) {
        long ns = snap.durationNs();
        double fps = snap.framesPerSecond();
        String prefix = running ? "Capturing" : "Stopped";
        if (ns <= 0) {
            return String.format("%s. %,d frames so far.", prefix, snap.total());
        }
        return String.format("%s. %,d frames in %s @ %.0f fps.",
                prefix, snap.total(), formatDuration(ns), fps);
    }

    private static String buildMix(CaptureStats.Snapshot snap) {
        long t = snap.total();
        if (t <= 0) return "Frame mix: -";
        return String.format("Frame mix: %d%% mgmt / %d%% data / %d%% ctrl / %d%% ext",
                pct(snap.mgmt(), t),
                pct(snap.data(), t),
                pct(snap.ctrl(), t),
                pct(snap.ext(),  t));
    }

    private String buildTopTalker(CaptureStats.Snapshot snap) {
        Map<String, CaptureStats.BssidStat> by = snap.byBssid();
        if (by.isEmpty()) return "Top talker: (no BSSID-bearing mgmt frames yet)";
        CaptureStats.BssidStat top = null;
        for (CaptureStats.BssidStat s : by.values()) {
            if (top == null || s.frames() > top.frames()) top = s;
        }
        if (top == null) return "Top talker: -";
        String name = displaySsidFor(top.bssid());
        long mgmt = Math.max(1, snap.mgmt());
        int share = (int) Math.round(100.0 * top.frames() / mgmt);
        String rssi = top.lastRssiDbm() == 0 ? ""
                : String.format(", %d dBm", top.lastRssiDbm());
        return String.format("Top talker: %s (%,d frames%s, %d%% of mgmt)",
                name, top.frames(), rssi, share);
    }

    private String buildLoad() {
        Map<String, BssLoad> loads = beaconStore.bssLoadSnapshot();
        if (loads.isEmpty()) {
            return "Air health: no AP advertised BSS Load yet.";
        }
        // 40 % matches Cisco's "consider load balancing" guidance.
        int crowded = 0;
        Map.Entry<String, BssLoad> worst = null;
        for (Map.Entry<String, BssLoad> e : loads.entrySet()) {
            BssLoad l = e.getValue();
            if (l.channelUtilizationPercent() >= 40) crowded++;
            if (worst == null
                    || l.channelUtilizationPercent()
                       > worst.getValue().channelUtilizationPercent()) {
                worst = e;
            }
        }
        if (worst == null) return "Air health: no BSS Load data yet.";
        String name = displaySsidFor(worst.getKey());
        return String.format("Air health: %d AP%s busy (>= 40%%). Worst: %s at %d%%.",
                crowded, crowded == 1 ? "" : "s",
                name, worst.getValue().channelUtilizationPercent());
    }

    private String buildHidden() {
        int n = beaconStore.discoveredCount();
        if (n == 0) {
            return "Hidden SSIDs resolved: 0 (waiting for probe responses).";
        }
        return String.format("Hidden SSIDs resolved this session: %d.", n);
    }

    // ---------------------------------------------------------------- helpers

    private String displaySsidFor(String bssid) {
        return beaconStore.discoveredSsid(bssid)
                .filter(s -> !s.isBlank())
                .orElse(bssid);
    }

    private static int pct(long part, long total) {
        return (int) Math.round(100.0 * part / total);
    }

    private static String formatDuration(long ns) {
        long sec = ns / 1_000_000_000L;
        long h = sec / 3600;
        long m = (sec % 3600) / 60;
        long s = sec % 60;
        if (h > 0) return String.format("%dh%02dm%02ds", h, m, s);
        if (m > 0) return String.format("%dm%02ds", m, s);
        return s + "s";
    }

    /** Useful for tests: deterministic top-talker selection. */
    public static List<CaptureStats.BssidStat> rankByFrames(
            Map<String, CaptureStats.BssidStat> in) {
        List<CaptureStats.BssidStat> out = new ArrayList<>(in.values());
        out.sort(Comparator.comparingLong(CaptureStats.BssidStat::frames).reversed());
        return out;
    }
}
