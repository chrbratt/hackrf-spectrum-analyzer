package jspectrumanalyzer.fx.ui;

import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.layout.VBox;
import jspectrumanalyzer.wifi.InterfererClassifier;

/**
 * Table of detected non-Wi-Fi interferers below the density chart in
 * the Wi-Fi window. Reads {@link InterfererClassifier.Snapshot}s from
 * the engine-side classifier and renders one row per source with type,
 * centre frequency, bandwidth, average dBm and a one-sentence rule
 * explanation so the user can see why each row was classified.
 *
 * <p>Sorted by power descending so the most prominent interferer is
 * always at the top, matching the user's likely "what is hurting my
 * signal right now" workflow. Empty state shows a friendly hint
 * instead of an empty pane so first-time users know the table is
 * intentional.
 */
public final class InterfererListView extends VBox {

    private static final double TABLE_PREF_HEIGHT = 160d;

    private final ObservableList<Row> rows = FXCollections.observableArrayList();
    private final TableView<Row> table = new TableView<>(rows);
    private final Label emptyHint = new Label("No non-Wi-Fi interferers detected.");

    public InterfererListView() {
        emptyHint.getStyleClass().add("preset-caption");

        TableColumn<Row, String> colType = column("Type", 200, r -> r.type);
        TableColumn<Row, String> colCenter = column("Center", 90, r -> r.center);
        TableColumn<Row, String> colBw = column("BW", 70, r -> r.bw);
        TableColumn<Row, String> colDbm = column("dBm", 60, r -> r.dbm);
        TableColumn<Row, String> colWhy = column("Why", 320, r -> r.why);
        colWhy.setMaxWidth(Double.MAX_VALUE);
        table.getColumns().setAll(java.util.List.of(colType, colCenter, colBw, colDbm, colWhy));
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        table.setPrefHeight(TABLE_PREF_HEIGHT);
        table.setPlaceholder(emptyHint);

        getChildren().setAll(table);
    }

    public void setSnapshot(InterfererClassifier.Snapshot snap) {
        if (snap == null || snap.sources().isEmpty()) {
            rows.clear();
            return;
        }
        java.util.List<Row> next = new java.util.ArrayList<>(snap.sources().size());
        for (InterfererClassifier.Interferer s : snap.sources()) {
            next.add(Row.from(s));
        }
        // Sort by avgDbm desc so the strongest source is always at top.
        next.sort((a, b) -> Double.compare(b.dbmRaw, a.dbmRaw));
        rows.setAll(next);
    }

    private static TableColumn<Row, String> column(String header, double width,
                                                   java.util.function.Function<Row, String> getter) {
        TableColumn<Row, String> col = new TableColumn<>(header);
        col.setPrefWidth(width);
        col.setCellValueFactory(cd -> new SimpleStringProperty(getter.apply(cd.getValue())));
        return col;
    }

    /**
     * Materialised view-row. We pre-format the strings here (instead of
     * via cell factories) so sorting can use the raw dBm value while
     * the table still shows a tidy "{@code -67 dBm}" string.
     */
    private static final class Row {
        final String type;
        final String center;
        final String bw;
        final String dbm;
        final String why;
        final double dbmRaw;

        private Row(String type, String center, String bw, String dbm,
                    String why, double dbmRaw) {
            this.type = type;
            this.center = center;
            this.bw = bw;
            this.dbm = dbm;
            this.why = why;
            this.dbmRaw = dbmRaw;
        }

        static Row from(InterfererClassifier.Interferer s) {
            return new Row(
                    s.type().label(),
                    String.format("%.0f MHz", s.centerMhz()),
                    String.format("%.0f MHz", s.bandwidthMhz()),
                    String.format("%.0f dBm", s.avgDbm()),
                    s.explanation(),
                    s.avgDbm());
        }
    }
}
