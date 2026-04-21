package jspectrumanalyzer.fx.ui;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

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
import jspectrumanalyzer.wifi.capture.ieee80211.BssLoad;

/**
 * BSS Load IE viewer for the monitor-capture panel: shows each AP's
 * self-reported channel utilization as a coloured gauge bar plus the
 * advertised station count.
 *
 * <h2>Why a separate panel and not a column in the top-talkers table?</h2>
 * Different scope. Top talkers ranks BSSIDs by frames we observed;
 * BSS Load only contains APs that <em>broadcast</em> the IE (most
 * enterprise APs do, most consumer APs don't). Mixing them would
 * leave one column populated for ~half the rows, which is more
 * confusing than two short tables side by side.
 *
 * <h2>Why is the gauge banded green/amber/red?</h2>
 * Cisco's "Capacity Coverage Design Guide" puts 0-40% as healthy,
 * 40-70% as "consider load balancing", 70-100% as "saturated, expect
 * client disassociations". The same thresholds drive
 * {@link CaptureInsightCard}'s "air health" line so the card and the
 * table tell the same story.
 */
public final class BssLoadTable {

    public static final int MAX_ROWS = 10;

    private final BeaconStore beaconStore;
    private final ObservableList<Row> rows = FXCollections.observableArrayList();
    private final TableView<Row> table = new TableView<>(rows);

    public BssLoadTable(BeaconStore beaconStore) {
        this.beaconStore = beaconStore;
        configureColumns();
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_SUBSEQUENT_COLUMNS);
        table.setPlaceholder(new Label(
                "No AP advertised BSS Load (IE 11) yet. Many consumer APs do not."));
        table.setPrefHeight(180);
    }

    public TableView<Row> view() { return table; }

    public void update() {
        Map<String, BssLoad> loads = beaconStore.bssLoadSnapshot();
        if (loads.isEmpty()) {
            rows.clear();
            return;
        }
        List<Row> next = new ArrayList<>(loads.size());
        for (Map.Entry<String, BssLoad> e : loads.entrySet()) {
            String ssid = beaconStore.discoveredSsid(e.getKey())
                    .filter(s -> !s.isBlank())
                    .orElse("");
            next.add(new Row(ssid, e.getKey(), e.getValue()));
        }
        next.sort(Comparator.comparingInt((Row r) -> r.load().channelUtilizationPercent()).reversed());
        if (next.size() > MAX_ROWS) {
            next = next.subList(0, MAX_ROWS);
        }
        rows.setAll(next);
    }

    private void configureColumns() {
        TableColumn<Row, String> ssidCol = new TableColumn<>("SSID");
        ssidCol.setCellValueFactory(d -> new SimpleStringProperty(
                d.getValue().ssid().isEmpty() ? "(unknown)" : d.getValue().ssid()));
        ssidCol.setPrefWidth(150);

        TableColumn<Row, String> bssidCol = new TableColumn<>("BSSID");
        bssidCol.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().bssid()));
        bssidCol.setPrefWidth(140);

        TableColumn<Row, Row> utilCol = new TableColumn<>("Channel utilization");
        utilCol.setCellValueFactory(d -> new SimpleObjectProperty<>(d.getValue()));
        utilCol.setCellFactory(c -> new UtilGaugeCell());
        utilCol.setPrefWidth(220);

        TableColumn<Row, Integer> staCol = new TableColumn<>("Stations");
        staCol.setCellValueFactory(d -> new SimpleObjectProperty<>(d.getValue().load().stationCount()));
        staCol.setPrefWidth(80);

        table.getColumns().setAll(java.util.List.of(ssidCol, bssidCol, utilCol, staCol));
    }

    public record Row(String ssid, String bssid, BssLoad load) {}

    /** Cisco-style 0-40 / 40-70 / 70-100 bands. */
    private static String colorFor(int pct) {
        if (pct < 40) return "#5cae5c";
        if (pct < 70) return "#d99c2e";
        return "#d9534f";
    }

    private static final class UtilGaugeCell extends TableCell<Row, Row> {
        private final Region bar = new Region();
        private final Label text = new Label();
        private final HBox box;

        UtilGaugeCell() {
            bar.setPrefHeight(12);
            bar.setMaxHeight(12);
            bar.setMinWidth(0);
            HBox barWrap = new HBox(bar);
            barWrap.setAlignment(Pos.CENTER_LEFT);
            barWrap.setMinWidth(140);
            barWrap.setPrefWidth(140);
            barWrap.setMaxWidth(140);
            barWrap.setStyle("-fx-background-color: #1f1f24; -fx-background-radius: 3;");
            text.getStyleClass().add("preset-caption");
            text.setMinWidth(50);
            box = new HBox(6, barWrap, text);
            box.setAlignment(Pos.CENTER_LEFT);
        }

        @Override protected void updateItem(Row r, boolean empty) {
            super.updateItem(r, empty);
            if (empty || r == null) { setGraphic(null); setText(null); return; }
            int pct = Math.max(0, Math.min(100, r.load().channelUtilizationPercent()));
            bar.setPrefWidth(Math.max(2, 140 * pct / 100.0));
            bar.setStyle(String.format(
                    "-fx-background-color: %s; -fx-background-radius: 2;",
                    colorFor(pct)));
            text.setText(pct + "%");
            setText(null);
            setGraphic(box);
        }
    }
}
