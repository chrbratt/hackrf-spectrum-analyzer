package jspectrumanalyzer.fx.ui;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.scene.control.Label;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import jspectrumanalyzer.wifi.capture.BeaconStore;
import jspectrumanalyzer.wifi.capture.CaptureStats;

/**
 * Per-AP "is this radio happy on its channel?" view, sitting next to
 * {@link TopTalkersTable} which answers the related but different
 * question of "who is loud on the air right now?".
 *
 * <h2>Why a separate table?</h2>
 * Top-talkers ranks by activity (frame count); health ranks by trouble.
 * The two intentionally diverge: a quiet AP can be unhealthy (stuck
 * negotiating low PHY rates with a far-away client) while a noisy AP
 * can be perfectly healthy. Splitting the views keeps each one easy to
 * skim - no sorting back and forth between "who" and "how".
 *
 * <h2>Composite status</h2>
 * The "Status" column collapses four signals into a single badge:
 * <ul>
 *   <li><b>Healthy</b> - retry &lt; 5%, beacon Hz close to 9.77, jitter &lt; 10 ms.</li>
 *   <li><b>Stressed</b> - any single metric crossed its first threshold.</li>
 *   <li><b>Bad</b> - retry ≥ 25%, beacon Hz &lt; 5, or jitter &gt; 30 ms.</li>
 * </ul>
 * Beacon-based metrics only contribute once we have enough samples
 * ({@link CaptureStats.BssidStat#hasBeaconSeries()} == true) so a brand
 * new BSSID does not get flagged "Bad" purely for lack of evidence.
 */
public final class ApHealthTable {

    /** Cap to keep the panel skimmable; matches {@link TopTalkersTable#MAX_ROWS}. */
    public static final int MAX_ROWS = 10;

    /** Beacons-per-second target for a default 100 TU beacon interval. */
    private static final double EXPECTED_BEACON_HZ = 1000.0 / 102.4;

    private final BeaconStore beaconStore;
    private final ObservableList<Row> rows = FXCollections.observableArrayList();
    private final TableView<Row> table = new TableView<>(rows);

    public ApHealthTable(BeaconStore beaconStore) {
        this.beaconStore = beaconStore;
        configureColumns();
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_SUBSEQUENT_COLUMNS);
        table.setPlaceholder(new Label(
                "No per-AP health data yet - waiting for enough beacons "
                + "to compute jitter and beacon-rate."));
        table.setPrefHeight(220);
    }

    public TableView<Row> view() { return table; }

    /**
     * Push a fresh snapshot. Sorts by status severity (Bad first, then
     * Stressed, then Healthy) so the eye lands on trouble immediately.
     * Within a band we sort by retry % desc - the metric that maps most
     * cleanly to "user-visible pain".
     */
    public void update(CaptureStats.Snapshot snap) {
        if (snap == null || snap.byBssid().isEmpty()) {
            rows.clear();
            return;
        }
        List<Row> all = new ArrayList<>(snap.byBssid().size());
        for (CaptureStats.BssidStat s : snap.byBssid().values()) {
            String ssid = beaconStore.discoveredSsid(s.bssid())
                    .filter(x -> !x.isBlank())
                    .orElse("");
            all.add(new Row(ssid, s));
        }
        // Sort: Bad (rank 0) first, then Stressed (1), then Healthy (2);
        // within a rank the highest retry % wins (worst first).
        all.sort(Comparator
                .comparingInt((Row r) -> r.status().rank)
                .thenComparing(Comparator.comparingInt(
                        (Row r) -> r.stat().retryPercent()).reversed()));
        if (all.size() > MAX_ROWS) all = all.subList(0, MAX_ROWS);
        rows.setAll(all);
    }

    private void configureColumns() {
        TableColumn<Row, String> ssidCol = new TableColumn<>("SSID / BSSID");
        ssidCol.setCellValueFactory(d -> {
            Row r = d.getValue();
            String ssid = r.ssid().isEmpty() ? "(unknown)" : r.ssid();
            return new SimpleStringProperty(ssid + "\n" + r.stat().bssid());
        });
        ssidCol.setPrefWidth(170);

        TableColumn<Row, Row> retryCol = new TableColumn<>("Retry %");
        retryCol.setCellValueFactory(d -> new SimpleObjectProperty<>(d.getValue()));
        retryCol.setCellFactory(c -> new RetryCell());
        retryCol.setPrefWidth(80);

        TableColumn<Row, Row> bcnHzCol = new TableColumn<>("Beacons/s");
        bcnHzCol.setCellValueFactory(d -> new SimpleObjectProperty<>(d.getValue()));
        bcnHzCol.setCellFactory(c -> new BeaconHzCell());
        bcnHzCol.setPrefWidth(95);

        TableColumn<Row, Row> jitterCol = new TableColumn<>("Jitter");
        jitterCol.setCellValueFactory(d -> new SimpleObjectProperty<>(d.getValue()));
        jitterCol.setCellFactory(c -> new JitterCell());
        jitterCol.setPrefWidth(80);

        TableColumn<Row, String> rateCol = new TableColumn<>("PHY rate");
        rateCol.setCellValueFactory(d -> new SimpleStringProperty(
                formatRate(d.getValue().stat())));
        rateCol.setPrefWidth(110);

        TableColumn<Row, Row> statusCol = new TableColumn<>("Status");
        statusCol.setCellValueFactory(d -> new SimpleObjectProperty<>(d.getValue()));
        statusCol.setCellFactory(c -> new StatusCell());
        statusCol.setPrefWidth(95);

        table.getColumns().setAll(java.util.List.of(
                ssidCol, retryCol, bcnHzCol, jitterCol, rateCol, statusCol));
    }

    private static String formatRate(CaptureStats.BssidStat s) {
        if (s.avgRateMbps() <= 0) return "-";
        if (s.minRateMbps() > 0 && s.minRateMbps() < (int) Math.round(s.avgRateMbps())) {
            return String.format("%.1f / min %d", s.avgRateMbps(), s.minRateMbps());
        }
        return String.format("%.1f Mbps", s.avgRateMbps());
    }

    // ---------------------------------------------------------------- model

    /** Status band for the composite badge. Lower rank = worse. */
    public enum Status {
        BAD(0, "Bad", "health-bad"),
        STRESSED(1, "Stressed", "health-stressed"),
        HEALTHY(2, "Healthy", "health-good");

        final int rank;
        final String label;
        final String styleClass;
        Status(int rank, String label, String styleClass) {
            this.rank = rank; this.label = label; this.styleClass = styleClass;
        }

        /**
         * Compute the composite for a single BSSID. Beacon-derived
         * signals are gated by {@link CaptureStats.BssidStat#hasBeaconSeries()}
         * so a freshly-seen AP does not get punished for sample
         * sparsity.
         */
        public static Status of(CaptureStats.BssidStat s) {
            int retry = s.retryPercent();
            boolean haveBeacons = s.hasBeaconSeries();
            double hz = s.beaconObservedHz();
            double jitter = s.beaconJitterMs();
            // Hard "bad" thresholds first - any one trips the badge.
            if (retry >= 25) return BAD;
            if (haveBeacons && hz > 0 && hz < 5) return BAD;
            if (haveBeacons && jitter > 30) return BAD;
            // Soft "stressed" thresholds.
            if (retry >= 5) return STRESSED;
            if (haveBeacons && hz > 0 && hz < 8) return STRESSED;
            if (haveBeacons && jitter > 10) return STRESSED;
            return HEALTHY;
        }
    }

    /** Row backing the table; status pre-computed once per snapshot. */
    public record Row(String ssid, CaptureStats.BssidStat stat) {
        public Status status() { return Status.of(stat); }
    }

    // ---------------------------------------------------------------- cells

    /** Coloured retry-% cell mirroring TopTalkersTable for consistency. */
    private static final class RetryCell extends TableCell<Row, Row> {
        @Override protected void updateItem(Row r, boolean empty) {
            super.updateItem(r, empty);
            if (empty || r == null) { setText(null); setStyle(""); return; }
            int p = r.stat().retryPercent();
            setText(p + "%");
            String c = (p >= 25) ? "#d05a5a"
                    : (p >= 5)   ? "#d6a23a"
                    : "#3fb27f";
            setStyle("-fx-text-fill: " + c + "; -fx-font-weight: bold;");
        }
    }

    /**
     * Beacon-Hz cell with text + small "%" of expected so the user can
     * read the absolute number AND see how close we are to the spec
     * target without doing the division in their head.
     */
    private static final class BeaconHzCell extends TableCell<Row, Row> {
        @Override protected void updateItem(Row r, boolean empty) {
            super.updateItem(r, empty);
            if (empty || r == null) { setText(null); setStyle(""); return; }
            CaptureStats.BssidStat s = r.stat();
            if (!s.hasBeaconSeries() || s.beaconObservedHz() <= 0) {
                setText("-"); setStyle("-fx-text-fill: #6b7280;"); return;
            }
            double hz = s.beaconObservedHz();
            int pct = (int) Math.round(100.0 * hz / EXPECTED_BEACON_HZ);
            setText(String.format("%.1f  (%d%%)", hz, pct));
            String c = (hz < 5)       ? "#d05a5a"
                    : (hz < 8)        ? "#d6a23a"
                    : "#3fb27f";
            setStyle("-fx-text-fill: " + c + "; -fx-font-weight: bold;");
        }
    }

    /** Beacon-jitter cell in ms with the same colour bands as Status. */
    private static final class JitterCell extends TableCell<Row, Row> {
        @Override protected void updateItem(Row r, boolean empty) {
            super.updateItem(r, empty);
            if (empty || r == null) { setText(null); setStyle(""); return; }
            CaptureStats.BssidStat s = r.stat();
            if (!s.hasBeaconSeries()) {
                setText("-"); setStyle("-fx-text-fill: #6b7280;"); return;
            }
            double j = s.beaconJitterMs();
            setText(String.format("%.1f ms", j));
            String c = (j > 30) ? "#d05a5a"
                    : (j > 10) ? "#d6a23a"
                    : "#3fb27f";
            setStyle("-fx-text-fill: " + c + "; -fx-font-weight: bold;");
        }
    }

    /** Pill-shaped status badge driven by a CSS class per band. */
    private static final class StatusCell extends TableCell<Row, Row> {
        private final Label badge = new Label();
        StatusCell() {
            badge.getStyleClass().add("health-badge");
        }
        @Override protected void updateItem(Row r, boolean empty) {
            super.updateItem(r, empty);
            if (empty || r == null) { setGraphic(null); setText(null); return; }
            Status st = r.status();
            badge.setText(st.label);
            badge.getStyleClass().removeAll(
                    "health-good", "health-stressed", "health-bad");
            badge.getStyleClass().add(st.styleClass);
            setGraphic(badge);
            setText(null);
        }
    }
}
