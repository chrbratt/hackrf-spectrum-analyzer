package jspectrumanalyzer.fx.ui;

import java.io.FileNotFoundException;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import javafx.application.Platform;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.value.ObservableValue;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Spinner;
import javafx.scene.control.SpinnerValueFactory;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import jspectrumanalyzer.core.FrequencyAllocationTable;
import jspectrumanalyzer.core.FrequencyAllocations;
import jspectrumanalyzer.core.FrequencyMultiRangePreset;
import jspectrumanalyzer.core.FrequencyPlan;
import jspectrumanalyzer.core.FrequencyRange;
import jspectrumanalyzer.fx.model.SettingsStore;
import jspectrumanalyzer.fx.util.FxControls;
import jspectrumanalyzer.wifi.WifiAccessPoint;
import jspectrumanalyzer.wifi.WifiScanService;

/**
 * Phase 1 / Step 1 of the Wi-Fi analyzer mode.
 * <p>
 * Acts as a thin "mode switch": when the user activates this tab the engine
 * is auto-configured to sweep one or more Wi-Fi bands (default = 2.4 + 5 +
 * 6 GHz) and the Wi-Fi channel grid overlay is enabled. The user can narrow
 * the scan to a single band or further to a range of MHz inside that band
 * via the controls in this tab. When the user leaves the tab the previous
 * frequency / overlay state is restored exactly.
 *
 * <h2>Why a snapshot, not a "Wi-Fi mode flag"?</h2>
 * The settings model has no concept of "current mode"; it just has values
 * the engine reads. Snapshotting the four model values we touch (plan,
 * single-range frequency, allocation table, overlay-visible) and writing
 * them back on deactivate keeps the model untouched while letting the
 * Wi-Fi tab make whatever local changes it needs - no new mode field, no
 * coupling between tabs.
 */
public final class WifiTab extends ScrollPane {

    private static final Logger LOG = LoggerFactory.getLogger(WifiTab.class);

    /** File name of the Wi-Fi channel grid CSV under {@code /freq/}. */
    private static final String WIFI_GRID_RESOURCE = "Wi-Fi channel grid.csv";

    /** Display name of the multi-range preset that covers 2.4 + 5 + 6E. */
    private static final String WIFI_BANDS_PRESET = "Wi-Fi 2.4 + 5 + 6E";

    private final SettingsStore settings;
    private final WifiScanService wifiScanService;

    /** Captured state from when the user entered the tab; null while inactive. */
    private Snapshot snapshot;

    private final ComboBox<WifiBand> bandCombo = new ComboBox<>();
    private final Spinner<Integer> startSpinner = new Spinner<>();
    private final Spinner<Integer> stopSpinner = new Spinner<>();
    private final HBox rangeRow;
    private final Label rangeReadout = new Label();

    private final ObservableList<WifiAccessPoint> apRows = FXCollections.observableArrayList();
    private final TableView<WifiAccessPoint> apTable = new TableView<>(apRows);
    private final Label apCountsLabel = new Label();

    /**
     * Suppresses the spinner / combo listeners while we're programmatically
     * resetting them (e.g. when the user picks a new band and we re-bound
     * the spinners). Without this flag, changing the band would fire one
     * spurious "spinner moved" event per spinner before the band's plan was
     * even applied.
     */
    private boolean suppressEvents = false;

    public WifiTab(SettingsStore settings, WifiScanService wifiScanService) {
        this.settings = settings;
        this.wifiScanService = wifiScanService;
        setFitToWidth(true);
        setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);

        Label badge = new Label(
                "Wi-Fi mode: sweep auto-configured for the band(s) selected below "
                + "with the Wi-Fi channel grid overlaid on the chart. Switch back "
                + "to another tab to restore your previous range.");
        badge.setWrapText(true);
        badge.getStyleClass().add("wifi-mode-badge");

        bandCombo.getItems().addAll(WifiBand.values());
        bandCombo.getSelectionModel().select(WifiBand.ALL);
        bandCombo.setMaxWidth(Double.MAX_VALUE);
        FxControls.withTooltip(bandCombo,
                "Pick which Wi-Fi band(s) to scan. 'All bands' covers 2.4 + 5 + "
                + "6 GHz in one chart but the 2.4 GHz strip will look narrow "
                + "since the X-axis is linear in MHz - pick a single band when "
                + "you need to read individual 2.4 GHz channels.");

        rangeRow = buildRangeRow();
        rangeRow.setVisible(false);
        rangeRow.setManaged(false);
        rangeReadout.getStyleClass().add("preset-caption");

        bandCombo.valueProperty().addListener((obs, o, n) -> onBandChanged(n));
        startSpinner.valueProperty().addListener((obs, o, n) -> onSpinnerChanged());
        stopSpinner.valueProperty().addListener((obs, o, n) -> onSpinnerChanged());

        configureApTable();
        apCountsLabel.getStyleClass().add("preset-caption");
        // Subscribe immediately so the table starts populating as soon as
        // the user opens the Wi-Fi tab. The service polls in the background
        // regardless of which tab is selected; we just have to marshal the
        // updates onto the FX thread.
        wifiScanService.addListener(snapshot ->
                Platform.runLater(() -> applyApSnapshot(snapshot)));

        Label placeholder = new Label(
                "Coming next: AP labels on the spectrum chart, per-channel "
                + "occupancy and density. See TODO-WIFI-PHASE-1.md.");
        placeholder.setWrapText(true);
        placeholder.getStyleClass().add("preset-caption");

        VBox apSection = FxControls.section("Visible access points",
                apCountsLabel, apTable);
        VBox.setVgrow(apTable, Priority.ALWAYS);
        apTable.setPrefHeight(360);

        VBox content = new VBox(12,
                badge,
                FxControls.section("Scan range",
                        FxControls.labeled("Band", bandCombo),
                        rangeRow,
                        rangeReadout),
                apSection,
                placeholder);
        content.setPadding(new Insets(12));
        setContent(content);

        updateRangeReadout(WifiBand.ALL, 0, 0);
        applyApSnapshot(wifiScanService.getLatest());
    }

    /**
     * Configure the AP TableView columns. Sortable per column; default sort
     * is RSSI descending so the strongest APs are at the top - matches the
     * way most Wi-Fi tools (Chanalyzer, WiFi Explorer, NetSpot) present the
     * list and is what users with overlapping neighbouring networks reach
     * for first.
     */
    private void configureApTable() {
        TableColumn<WifiAccessPoint, String> ssidCol = new TableColumn<>("SSID");
        ssidCol.setCellValueFactory(d -> new SimpleStringProperty(
                d.getValue().ssid().isEmpty() ? "(hidden)" : d.getValue().ssid()));
        ssidCol.setPrefWidth(160);

        TableColumn<WifiAccessPoint, String> bssidCol = new TableColumn<>("BSSID");
        bssidCol.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().bssid()));
        bssidCol.setPrefWidth(140);

        TableColumn<WifiAccessPoint, Integer> rssiCol = new TableColumn<>("RSSI dBm");
        rssiCol.setCellValueFactory(d -> new SimpleObjectProperty<>(d.getValue().rssiDbm()));
        rssiCol.setPrefWidth(80);
        rssiCol.setComparator(Comparator.naturalOrder());

        TableColumn<WifiAccessPoint, Integer> chCol = new TableColumn<>("Ch");
        chCol.setCellValueFactory(d -> new SimpleObjectProperty<>(d.getValue().channel()));
        chCol.setPrefWidth(50);

        TableColumn<WifiAccessPoint, String> bandCol = new TableColumn<>("Band");
        bandCol.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().bandLabel()));
        bandCol.setPrefWidth(70);

        TableColumn<WifiAccessPoint, String> phyCol = new TableColumn<>("PHY");
        phyCol.setCellValueFactory(d -> new SimpleStringProperty(
                d.getValue().phyType() == null ? "" : d.getValue().phyType()));
        phyCol.setPrefWidth(80);

        apTable.getColumns().setAll(java.util.List.of(
                ssidCol, bssidCol, rssiCol, chCol, bandCol, phyCol));
        apTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_SUBSEQUENT_COLUMNS);
        apTable.setPlaceholder(new Label(
                wifiScanService.isAvailable()
                        ? "Scanning... (first results appear within ~5 seconds)"
                        : "Native Wi-Fi API unavailable on this system - "
                          + "AP discovery disabled."));

        // Default sort: strongest signal at the top. Use clear + add instead
        // of setAll(rssiCol) so the compiler does not warn about a generic
        // array creation for the single-element varargs.
        rssiCol.setSortType(TableColumn.SortType.DESCENDING);
        apTable.getSortOrder().clear();
        apTable.getSortOrder().add(rssiCol);
    }

    /**
     * Replace the table contents and refresh the per-band counts label.
     * Called on the FX thread by the scan-service listener; safe to call
     * with an empty / null list (treated as "no APs visible right now").
     */
    private void applyApSnapshot(List<WifiAccessPoint> snapshot) {
        if (snapshot == null) snapshot = List.of();
        // Replace + sort instead of incremental diff: the AP list is small
        // (<200 entries even in dense apartments) so a full replace keeps
        // the code obvious and avoids the "selection jumps" bug that
        // incremental diffs hit when an item's RSSI changes.
        apRows.setAll(snapshot);
        apTable.sort();
        apCountsLabel.setText(buildCountsText(snapshot));
    }

    private String buildCountsText(List<WifiAccessPoint> snapshot) {
        if (!wifiScanService.isAvailable()) {
            return "Native Wi-Fi API unavailable - AP discovery disabled.";
        }
        // EnumMap so empty bands still print as "0".
        EnumMap<WifiAccessPoint.Band, Integer> counts = new EnumMap<>(WifiAccessPoint.Band.class);
        for (WifiAccessPoint.Band b : WifiAccessPoint.Band.values()) counts.put(b, 0);
        for (WifiAccessPoint ap : snapshot) {
            WifiAccessPoint.Band b = ap.band();
            if (b != null) counts.merge(b, 1, Integer::sum);
        }
        return String.format("Visible APs: 2.4 GHz: %d  /  5 GHz: %d  /  6 GHz: %d  (%d total)",
                counts.get(WifiAccessPoint.Band.GHZ_24),
                counts.get(WifiAccessPoint.Band.GHZ_5),
                counts.get(WifiAccessPoint.Band.GHZ_6),
                snapshot.size());
    }

    /**
     * Build the start/stop MHz spinner row used when a single band is
     * selected. The spinners are constructed with placeholder bounds
     * (0..7250) and reconfigured per-band in {@link #onBandChanged}.
     */
    private HBox buildRangeRow() {
        startSpinner.setValueFactory(new SpinnerValueFactory.IntegerSpinnerValueFactory(
                SettingsStore.FREQ_MIN_MHZ, SettingsStore.FREQ_MAX_MHZ, SettingsStore.FREQ_MIN_MHZ, 1));
        startSpinner.setEditable(true);
        startSpinner.setPrefWidth(110);
        FxControls.withTooltip(startSpinner,
                "Lower edge of the scan in MHz. Use this to channel-restrict "
                + "inside the chosen band (e.g. set to 2401 to skip nothing on "
                + "2.4 GHz, or 2451 to look only at ch 11).");

        stopSpinner.setValueFactory(new SpinnerValueFactory.IntegerSpinnerValueFactory(
                SettingsStore.FREQ_MIN_MHZ, SettingsStore.FREQ_MAX_MHZ, SettingsStore.FREQ_MAX_MHZ, 1));
        stopSpinner.setEditable(true);
        stopSpinner.setPrefWidth(110);
        FxControls.withTooltip(stopSpinner,
                "Upper edge of the scan in MHz. Together with the start "
                + "spinner this lets you trim away parts of the band you do "
                + "not care about - e.g. 5170-5250 for UNII-1 only.");

        HBox row = new HBox(8,
                new Label("Start MHz"), startSpinner,
                new Label("Stop MHz"), stopSpinner);
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    /** Wire the tab's activate / deactivate transitions to a JavaFX boolean. */
    public void bindLifecycle(ObservableValue<Boolean> selected) {
        selected.addListener((obs, wasSelected, isSelected) -> {
            if (Boolean.TRUE.equals(isSelected)) {
                activate();
            } else if (Boolean.TRUE.equals(wasSelected)) {
                deactivate();
            }
        });
    }

    private void activate() {
        if (snapshot != null) return; // Already active; idempotent.
        snapshot = Snapshot.capture(settings);
        try {
            // Apply the current UI selection (which may differ from the
            // default if the user picked a band before leaving + re-entering).
            applyUiSelection();
            applyOverlay();
            LOG.info("Wi-Fi tab activated; previous state snapshotted.");
        } catch (RuntimeException ex) {
            LOG.warn("Failed to apply Wi-Fi mode; rolling back.", ex);
            snapshot.restore(settings);
            snapshot = null;
        }
    }

    private void deactivate() {
        if (snapshot == null) return;
        snapshot.restore(settings);
        snapshot = null;
        LOG.info("Wi-Fi tab deactivated; previous state restored.");
    }

    private void onBandChanged(WifiBand band) {
        if (band == null) return;
        if (band == WifiBand.ALL) {
            rangeRow.setVisible(false);
            rangeRow.setManaged(false);
            updateRangeReadout(band, 0, 0);
        } else {
            rangeRow.setVisible(true);
            rangeRow.setManaged(true);
            // Re-bound the spinners to this band's edges and reset their
            // values to the full band. suppressEvents so we don't fire two
            // spinner-changed events that would each push a partial plan.
            suppressEvents = true;
            try {
                ((SpinnerValueFactory.IntegerSpinnerValueFactory) startSpinner.getValueFactory())
                        .setMin(band.startMhz);
                ((SpinnerValueFactory.IntegerSpinnerValueFactory) startSpinner.getValueFactory())
                        .setMax(band.stopMhz);
                ((SpinnerValueFactory.IntegerSpinnerValueFactory) startSpinner.getValueFactory())
                        .setValue(band.startMhz);
                ((SpinnerValueFactory.IntegerSpinnerValueFactory) stopSpinner.getValueFactory())
                        .setMin(band.startMhz);
                ((SpinnerValueFactory.IntegerSpinnerValueFactory) stopSpinner.getValueFactory())
                        .setMax(band.stopMhz);
                ((SpinnerValueFactory.IntegerSpinnerValueFactory) stopSpinner.getValueFactory())
                        .setValue(band.stopMhz);
            } finally {
                suppressEvents = false;
            }
            updateRangeReadout(band, band.startMhz, band.stopMhz);
        }
        applyUiSelection();
    }

    private void onSpinnerChanged() {
        if (suppressEvents) return;
        WifiBand band = bandCombo.getValue();
        if (band == null || band == WifiBand.ALL) return;
        Integer s = startSpinner.getValue();
        Integer e = stopSpinner.getValue();
        if (s == null || e == null) return;
        // Reject "stop <= start" silently - the user is mid-edit; we wait
        // for a sensible value before pushing a plan that the engine would
        // reject anyway.
        if (e <= s) {
            updateRangeReadout(band, s, e);
            return;
        }
        updateRangeReadout(band, s, e);
        applyUiSelection();
    }

    /**
     * Read the current combo + spinner state and push the matching plan to
     * the settings model. No-op while the tab is inactive (snapshot==null)
     * so the user's local UI tweaks never leak to other tabs.
     */
    private void applyUiSelection() {
        if (snapshot == null) return;
        WifiBand band = bandCombo.getValue();
        if (band == null) return;
        if (band == WifiBand.ALL) {
            FrequencyPlan all = lookupAllBandsPlan();
            if (all != null) {
                settings.getFrequencyPlan().setValue(all);
            } else {
                LOG.warn("Wi-Fi 2.4 + 5 + 6E preset missing; cannot apply 'All bands'.");
            }
            return;
        }
        // Single band: build a one-segment plan so the same code path
        // (frequencyPlan != null) covers both cases. The engine reads
        // either; using a plan keeps the snapshot/restore symmetric.
        Integer s = startSpinner.getValue();
        Integer e = stopSpinner.getValue();
        if (s == null || e == null || e <= s) return;
        FrequencyPlan single = new FrequencyPlan(
                List.of(new FrequencyRange(s, e)));
        settings.getFrequencyPlan().setValue(single);
    }

    private void applyOverlay() {
        FrequencyAllocationTable grid = lookupWifiGrid();
        if (grid != null) {
            settings.getFrequencyAllocationTable().setValue(grid);
            settings.isFrequencyAllocationVisible().setValue(true);
        } else {
            LOG.warn("Wi-Fi channel grid CSV not found; overlay not enabled.");
        }
    }

    private void updateRangeReadout(WifiBand band, int startMhz, int stopMhz) {
        if (band == WifiBand.ALL) {
            rangeReadout.setText("Active range: 2400-2500 + 5150-5895 + 5925-7125 MHz "
                    + "(stitched across all three Wi-Fi bands).");
        } else if (stopMhz <= startMhz) {
            rangeReadout.setText("Stop MHz must be greater than Start MHz.");
        } else {
            rangeReadout.setText(String.format(
                    "Active range: %d-%d MHz (%s, %d MHz wide).",
                    startMhz, stopMhz, band.label, stopMhz - startMhz));
        }
    }

    private static FrequencyPlan lookupAllBandsPlan() {
        for (FrequencyMultiRangePreset p : FrequencyMultiRangePreset.defaults()) {
            if (p.getPlan() != null && WIFI_BANDS_PRESET.equals(p.getName())) {
                return p.getPlan();
            }
        }
        return null;
    }

    private static FrequencyAllocationTable lookupWifiGrid() {
        try {
            Map<String, FrequencyAllocationTable> all = new FrequencyAllocations().getTable();
            return all.get(WIFI_GRID_RESOURCE);
        } catch (FileNotFoundException ex) {
            LOG.warn("Could not load allocation tables when entering Wi-Fi tab", ex);
            return null;
        }
    }

    /**
     * Wi-Fi sub-bands the user can pick in the band combo. Edges follow the
     * same regulatory boundaries as the {@code Wi-Fi 2.4 + 5 + 6E} preset
     * so single-band scans line up exactly with the matching segment of the
     * all-bands view.
     */
    enum WifiBand {
        ALL("All bands (2.4 + 5 + 6 GHz)", 0, 0),
        BAND_24("2.4 GHz", 2400, 2500),
        BAND_5("5 GHz", 5150, 5895),
        BAND_6("6 GHz (Wi-Fi 6E)", 5925, 7125);

        final String label;
        final int startMhz;
        final int stopMhz;

        WifiBand(String label, int startMhz, int stopMhz) {
            this.label = label;
            this.startMhz = startMhz;
            this.stopMhz = stopMhz;
        }

        @Override
        public String toString() {
            if (this == ALL) return label;
            return label + "  (" + startMhz + "-" + stopMhz + " MHz)";
        }
    }

    /**
     * Snapshot of every model value the Wi-Fi tab might touch.
     */
    private static final class Snapshot {
        final FrequencyPlan plan;
        final FrequencyRange range;
        final FrequencyAllocationTable allocationTable;
        final boolean allocationVisible;

        private Snapshot(FrequencyPlan plan, FrequencyRange range,
                         FrequencyAllocationTable allocationTable,
                         boolean allocationVisible) {
            this.plan = plan;
            this.range = range;
            this.allocationTable = allocationTable;
            this.allocationVisible = allocationVisible;
        }

        static Snapshot capture(SettingsStore settings) {
            return new Snapshot(
                    settings.getFrequencyPlan().getValue(),
                    settings.getFrequency().getValue(),
                    settings.getFrequencyAllocationTable().getValue(),
                    settings.isFrequencyAllocationVisible().getValue());
        }

        void restore(SettingsStore settings) {
            settings.getFrequencyPlan().setValue(plan);
            settings.getFrequency().setValue(range);
            settings.getFrequencyAllocationTable().setValue(allocationTable);
            settings.isFrequencyAllocationVisible().setValue(allocationVisible);
        }
    }

}
