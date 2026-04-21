package jspectrumanalyzer.fx.ui;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import jspectrumanalyzer.wifi.capture.BeaconStore;
import jspectrumanalyzer.wifi.capture.CaptureStats;

/**
 * Chanalyzer-style "who is on the air" table for the monitor-capture
 * panel. Sorted by management-frame count desc, capped at the top
 * {@link #MAX_ROWS} entries to keep the panel compact.
 *
 * <p>Three at-a-glance cells per row:
 * <ul>
 *   <li><b>RSSI bar</b>: a fixed-width bar coloured by signal strength
 *       (red weak, green strong) so the user can rank APs by signal
 *       without comparing dBm numbers.</li>
 *   <li><b>Frame share bar</b>: each row's frame count expressed as a
 *       percentage of the strongest talker's count (not the total
 *       capture) so the leader is always at 100% width and trailing
 *       talkers are visually proportional to it.</li>
 *   <li><b>Frames + share text</b>: the raw count plus the percentage
 *       of total mgmt traffic, for users who prefer numbers.</li>
 * </ul>
 *
 * <p>Hidden SSIDs resolve through the shared {@link BeaconStore} so
 * the SSID column flips from the BSSID literal to the discovered
 * name automatically once a probe response is captured.</p>
 */
public final class TopTalkersTable {

    /** Limit; more rows than this is more clutter than insight. */
    public static final int MAX_ROWS = 10;

    private final BeaconStore beaconStore;
    private final ObservableList<Row> rows = FXCollections.observableArrayList();
    private final TableView<Row> table = new TableView<>(rows);

    /** Aggregate-snapshot tally so the share bars stay stable mid-render. */
    private long currentTopFrames = 1;
    private long currentTotalMgmt = 1;

    public TopTalkersTable(BeaconStore beaconStore) {
        this.beaconStore = beaconStore;
        configureColumns();
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_SUBSEQUENT_COLUMNS);
        table.setPlaceholder(new Label(
                "No BSSID-bearing management frames captured yet."));
        table.setPrefHeight(220);
    }

    public TableView<Row> view() { return table; }

    /**
     * Push a fresh snapshot. Recomputes the top-N rows in place; the
     * underlying {@link TableView} keeps its scroll position and
     * column sort across updates.
     */
    public void update(CaptureStats.Snapshot snap) {
        if (snap == null || snap.byBssid().isEmpty()) {
            rows.clear();
            return;
        }
        currentTotalMgmt = Math.max(1, snap.mgmt());
        List<CaptureStats.BssidStat> all = new ArrayList<>(snap.byBssid().values());
        all.sort(Comparator.comparingLong(CaptureStats.BssidStat::frames).reversed());
        currentTopFrames = Math.max(1, all.get(0).frames());

        List<Row> next = new ArrayList<>(MAX_ROWS);
        int n = Math.min(MAX_ROWS, all.size());
        for (int i = 0; i < n; i++) {
            CaptureStats.BssidStat s = all.get(i);
            String ssid = beaconStore.discoveredSsid(s.bssid())
                    .filter(x -> !x.isBlank())
                    .orElse("");
            next.add(new Row(ssid, s.bssid(), s.lastRssiDbm(), s.frames(),
                    currentTopFrames, currentTotalMgmt,
                    s.retryPercent(), s.avgRateMbps()));
        }
        rows.setAll(next);
    }

    // ---------------------------------------------------------------- columns

    private void configureColumns() {
        TableColumn<Row, String> ssidCol = new TableColumn<>("SSID");
        ssidCol.setCellValueFactory(d -> new SimpleStringProperty(
                d.getValue().ssid().isEmpty() ? "(unknown)" : d.getValue().ssid()));
        ssidCol.setPrefWidth(150);

        TableColumn<Row, String> bssidCol = new TableColumn<>("BSSID");
        bssidCol.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().bssid()));
        bssidCol.setPrefWidth(140);

        TableColumn<Row, Row> rssiCol = new TableColumn<>("RSSI");
        rssiCol.setCellValueFactory(d -> new SimpleObjectProperty<>(d.getValue()));
        rssiCol.setCellFactory(c -> new RssiBarCell());
        rssiCol.setPrefWidth(130);

        TableColumn<Row, Row> frameCol = new TableColumn<>("Frames");
        frameCol.setCellValueFactory(d -> new SimpleObjectProperty<>(d.getValue()));
        frameCol.setCellFactory(c -> new FrameShareCell());
        frameCol.setPrefWidth(180);

        // Retry % deserves a coloured cell so the eye lands on the bad
        // APs without having to read every number; the colour mirrors
        // ApHealthTable's status bands so the two views stay in sync.
        TableColumn<Row, Row> retryCol = new TableColumn<>("Retry %");
        retryCol.setCellValueFactory(d -> new SimpleObjectProperty<>(d.getValue()));
        retryCol.setCellFactory(c -> new RetryPercentCell());
        retryCol.setPrefWidth(80);

        // Avg PHY rate is plain numeric. 0 == "no rate samples" because
        // HT/VHT/HE rates do not appear in the legacy radiotap RATE
        // field; we render "-" rather than misleading "0 Mbps".
        TableColumn<Row, String> rateCol = new TableColumn<>("Avg rate");
        rateCol.setCellValueFactory(d -> new SimpleStringProperty(
                formatAvgRate(d.getValue().avgRateMbps())));
        rateCol.setPrefWidth(80);

        table.getColumns().setAll(java.util.List.of(
                ssidCol, bssidCol, rssiCol, frameCol, retryCol, rateCol));
    }

    private static String formatAvgRate(double mbps) {
        if (mbps <= 0.0) return "-";
        if (mbps >= 100) return String.format("%.0f Mbps", mbps);
        return String.format("%.1f Mbps", mbps);
    }

    /** Row backing the table; pure data so cell factories can read it. */
    public record Row(String ssid, String bssid, int rssiDbm,
                      long frames, long topFrames, long totalMgmt,
                      int retryPercent, double avgRateMbps) {

        /** Width fraction for the RSSI bar in [0,1]. */
        double rssiFraction() {
            if (rssiDbm == 0) return 0;
            // Map -90..-30 dBm to 0..1; clamp.
            double x = (rssiDbm + 90.0) / 60.0;
            if (x < 0) return 0;
            if (x > 1) return 1;
            return x;
        }

        /** Width fraction for the frame-share bar relative to the top talker. */
        double frameFraction() {
            return topFrames <= 0 ? 0 : Math.min(1.0, (double) frames / topFrames);
        }

        /** Percentage of total management traffic this BSSID represents. */
        int mgmtSharePercent() {
            return totalMgmt <= 0 ? 0
                    : (int) Math.round(100.0 * frames / totalMgmt);
        }
    }

    /** Renders RSSI as a small coloured bar with the dBm value alongside. */
    private static final class RssiBarCell extends TableCell<Row, Row> {
        private final Region bar = new Region();
        private final Label text = new Label();
        private final HBox box;

        RssiBarCell() {
            bar.setPrefHeight(10);
            bar.setMaxHeight(10);
            bar.setMinWidth(0);
            HBox barWrap = new HBox(bar);
            barWrap.setAlignment(Pos.CENTER_LEFT);
            barWrap.setMinWidth(60);
            barWrap.setPrefWidth(60);
            barWrap.setMaxWidth(60);
            text.getStyleClass().add("preset-caption");
            text.setMinWidth(50);
            box = new HBox(6, barWrap, text);
            box.setAlignment(Pos.CENTER_LEFT);
        }

        @Override protected void updateItem(Row r, boolean empty) {
            super.updateItem(r, empty);
            if (empty || r == null || r.rssiDbm() == 0) {
                setGraphic(null);
                setText(empty || r == null ? null : "n/a");
                return;
            }
            double f = r.rssiFraction();
            bar.setPrefWidth(Math.max(2, 60 * f));
            // Red (weak) -> yellow -> green (strong). HSL hue 0..120.
            double hue = 120.0 * f;
            bar.setStyle(String.format(
                    "-fx-background-color: hsl(%.0f, 70%%, 45%%); "
                    + "-fx-background-radius: 2;", hue));
            text.setText(r.rssiDbm() + " dBm");
            setText(null);
            setGraphic(box);
        }
    }

    /**
     * Coloured retry-% cell. Bands match the per-AP health table:
     * <2% green, 2..10% amber, &gt;10% red. We render text only (no
     * bar) because the cell is narrow enough that a tiny bar would be
     * harder to read than the percentage itself.
     */
    static final class RetryPercentCell extends TableCell<Row, Row> {
        @Override protected void updateItem(Row r, boolean empty) {
            super.updateItem(r, empty);
            if (empty || r == null) { setText(null); setStyle(""); return; }
            int p = r.retryPercent();
            setText(p + "%");
            String color;
            if (p < 2)        color = "#3fb27f"; // green
            else if (p <= 10) color = "#d6a23a"; // amber
            else              color = "#d05a5a"; // red
            setStyle("-fx-text-fill: " + color + "; -fx-font-weight: bold;");
        }
    }

    /** Renders frame count as a bar relative to the top talker plus raw text. */
    private static final class FrameShareCell extends TableCell<Row, Row> {
        private final Region bar = new Region();
        private final Label text = new Label();
        private final HBox box;

        FrameShareCell() {
            bar.setPrefHeight(10);
            bar.setMaxHeight(10);
            bar.setMinWidth(0);
            bar.setStyle("-fx-background-color: #2f9aa6; -fx-background-radius: 2;");
            HBox barWrap = new HBox(bar);
            barWrap.setAlignment(Pos.CENTER_LEFT);
            barWrap.setMinWidth(90);
            barWrap.setPrefWidth(90);
            barWrap.setMaxWidth(90);
            text.getStyleClass().add("preset-caption");
            text.setMinWidth(80);
            box = new HBox(6, barWrap, text);
            box.setAlignment(Pos.CENTER_LEFT);
        }

        @Override protected void updateItem(Row r, boolean empty) {
            super.updateItem(r, empty);
            if (empty || r == null) { setGraphic(null); setText(null); return; }
            double f = r.frameFraction();
            bar.setPrefWidth(Math.max(2, 90 * f));
            text.setText(String.format("%,d  (%d%%)", r.frames(), r.mgmtSharePercent()));
            setText(null);
            setGraphic(box);
        }
    }
}
