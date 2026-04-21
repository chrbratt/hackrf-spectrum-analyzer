package jspectrumanalyzer.fx.ui;

import java.io.FileNotFoundException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import javafx.application.Platform;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Spinner;
import javafx.scene.control.SpinnerValueFactory;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TitledPane;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import jspectrumanalyzer.core.FrequencyAllocationTable;
import jspectrumanalyzer.core.FrequencyAllocations;
import jspectrumanalyzer.core.FrequencyMultiRangePreset;
import jspectrumanalyzer.core.FrequencyPlan;
import jspectrumanalyzer.core.FrequencyRange;
import jspectrumanalyzer.fx.model.SettingsStore;
import jspectrumanalyzer.fx.util.FxControls;
import jspectrumanalyzer.fx.util.FxCoalescingDispatcher;
import jspectrumanalyzer.wifi.ChannelOccupancyService;
import jspectrumanalyzer.wifi.DensityHistogramService;
import jspectrumanalyzer.wifi.InterfererClassifier;
import jspectrumanalyzer.wifi.WifiAccessPoint;
import jspectrumanalyzer.wifi.WifiAdapter;
import jspectrumanalyzer.wifi.WifiScanService;
import jspectrumanalyzer.wifi.capture.MonitorModeCapture;

/**
 * Floating window that hosts the Wi-Fi analyzer view: a band picker that drives
 * the main spectrum scan, plus a live AP list filtered to whatever the
 * spectrum is currently sweeping.
 *
 * <h2>Why a separate window instead of a tab?</h2>
 * Users want to keep the spectrum and waterfall visible while reading the AP
 * list - and to keep the Scan / Display / Recording tabs reachable so they
 * can change RBW, gain, palette, etc. while looking at the Wi-Fi data. A
 * tab forced an either / or; an owned {@link Stage} lets both panels
 * co-exist on the desktop, side by side or in any user-arranged layout.
 *
 * <h2>Lifecycle</h2>
 * <ul>
 *   <li>One instance per {@link MainWindow}, created lazily on first
 *       toolbar click via {@link #show()}.</li>
 *   <li>Closing the window {@link Stage#hide() hides} it; it stays in
 *       memory so the next open is instant and remembers the user's band /
 *       spinner choices.</li>
 *   <li>The first call to {@link #show()} applies the Wi-Fi 2.4 + 5 + 6E
 *       multi-range plan + channel-grid overlay so the window is useful
 *       out of the box. Subsequent opens leave the spectrum alone.</li>
 *   <li>Closing the window does <em>not</em> restore the prior spectrum
 *       state - the main window stays accessible the whole time, so the
 *       user can revert via the {@code Scan} tab themselves. Avoids the
 *       "spectrum jumped back to 920 MHz when I closed the window" gotcha
 *       that snapshot/restore used to cause in the tab implementation.</li>
 * </ul>
 *
 * <h2>AP-list filter</h2>
 * The list is filtered by the engine's currently-active scan plan
 * ({@link SettingsStore#getEffectivePlan()}). Picking a single band in this
 * window reduces the scan range and shrinks the list automatically; changing
 * the scan from the main window's Scan tab does the same. A checkbox lets
 * the user disable the filter to see all visible APs on the system regardless
 * of what the SDR is currently sweeping.
 */
public final class WifiWindow {

    private static final Logger LOG = LoggerFactory.getLogger(WifiWindow.class);

    private static final String WIFI_GRID_RESOURCE = "Wi-Fi channel grid.csv";
    private static final String WIFI_BANDS_PRESET = "Wi-Fi 2.4 + 5 + 6E";

    private final SettingsStore settings;
    /**
     * Routed through this controller for every retune intent emitted by
     * the band combo / spinners / "All bands" picker. Replaces the
     * legacy direct writes to {@link SettingsStore#getFrequency()} and
     * {@link SettingsStore#getFrequencyPlan()}.
     */
    private final jspectrumanalyzer.fx.engine.SdrController sdrController;
    private final WifiScanService wifiScanService;
    @SuppressWarnings("unused") // referenced only inside the constructor for listener wiring
    private final ChannelOccupancyService occupancyService;
    @SuppressWarnings("unused") // referenced only inside the constructor for listener wiring
    private final jspectrumanalyzer.wifi.ChannelInterferenceService interferenceService;
    @SuppressWarnings("unused") // referenced only inside the constructor for listener wiring
    private final DensityHistogramService densityService;
    private final DensityChartView densityView;
    @SuppressWarnings("unused") // referenced only inside the constructor for listener wiring
    private final InterfererClassifier interfererClassifier;
    private final InterfererListView interfererListView;

    /**
     * Phase-2 monitor-capture panel. Lives at the bottom of the window
     * and is owned here so {@link #close()} can shut its capture handle
     * down cleanly when the user closes the Wi-Fi window without first
     * pressing Stop.
     */
    private final MonitorCapturePanel monitorCapturePanel;

    /**
     * Shared {@link jspectrumanalyzer.wifi.capture.BeaconStore}. Read
     * by the AP table to substitute a captured probe-response SSID
     * for the literal {@code "(hidden)"} placeholder, and passed
     * through to the {@link MonitorCapturePanel} where the capture
     * polling thread populates it.
     */
    private final jspectrumanalyzer.wifi.capture.BeaconStore beaconStore;

    private final Stage stage = new Stage();

    private final ComboBox<AdapterChoice> adapterCombo = new ComboBox<>();
    private final Label adapterHintLabel = new Label();

    private final ComboBox<WifiBand> bandCombo = new ComboBox<>();
    private final Spinner<Integer> startSpinner = new Spinner<>();
    private final Spinner<Integer> stopSpinner = new Spinner<>();
    private final HBox rangeRow;
    private final Label rangeReadout = new Label();
    private final Label scanStatusLabel = new Label();

    private final ObservableList<WifiAccessPoint> apRows = FXCollections.observableArrayList();
    private final TableView<WifiAccessPoint> apTable = new TableView<>(apRows);
    private final Label apCountsLabel = new Label();
    private final CheckBox filterToScanRangeBox =
            new CheckBox("Filter list to current spectrum scan range");
    /**
     * Two-way bound to {@link SettingsStore#isApMarkersVisible()} so the same
     * setting can be flipped from this checkbox or from anywhere else that
     * reads the model. Built in the constructor via {@link FxControls#checkBox}.
     */
    private final CheckBox apMarkersBox;
    /**
     * Per-AP RSSI trend strip below the table. Updates whenever the table
     * selection changes or a fresh scan snapshot arrives.
     */
    private final ApTrendChart trendChart;
    /**
     * Per-Wi-Fi-channel duty cycle bars; populated by the
     * {@link ChannelOccupancyService} listener wired in the constructor.
     */
    private final ChannelOccupancyView occupancyView;

    /** Latest unfiltered snapshot pushed by the scan service (FX-thread only). */
    private List<WifiAccessPoint> latestSnapshot = List.of();

    /** Latest occupancy snapshot, cached so the insight card can use it. */
    private ChannelOccupancyService.Snapshot latestOccupancy;

    /**
     * Plain-English "what am I looking at" card pinned to the top of
     * the window. See {@link WifiInsightCard} for the wording rules.
     */
    private final WifiInsightCard insightCard;

    /** True while we're programmatically updating spinners after a band swap. */
    private boolean suppressEvents = false;

    /** True after the first {@link #show()} call so we only auto-apply once. */
    private boolean appliedOnce = false;

    /**
     * Snapshot of {@code isApMarkersVisible} taken when the window opens
     * so {@code setOnHidden} can restore the user's pre-show choice.
     * {@code null} while the window is closed.
     */
    private Boolean apMarkerStateBeforeShow;

    public WifiWindow(SettingsStore settings,
                      jspectrumanalyzer.fx.engine.SdrController sdrController,
                      WifiScanService wifiScanService,
                      ChannelOccupancyService occupancyService,
                      jspectrumanalyzer.wifi.ChannelInterferenceService interferenceService,
                      DensityHistogramService densityService,
                      InterfererClassifier interfererClassifier,
                      MonitorModeCapture monitorCapture,
                      jspectrumanalyzer.wifi.capture.BeaconStore beaconStore) {
        this.settings = settings;
        this.sdrController = sdrController;
        this.wifiScanService = wifiScanService;
        this.occupancyService = occupancyService;
        this.interferenceService = interferenceService;
        this.densityService = densityService;
        this.densityView = new DensityChartView(settings, beaconStore);
        this.interfererClassifier = interfererClassifier;
        this.interfererListView = new InterfererListView();
        this.beaconStore = beaconStore;
        this.monitorCapturePanel = new MonitorCapturePanel(monitorCapture, beaconStore);
        this.insightCard = new WifiInsightCard(beaconStore);
        // Refresh the AP table when the beacon store learns a new
        // hidden-SSID name so the table cell flips from "(hidden)" to
        // "(hidden: name)" without waiting for the next 1 s scan tick.
        // Same listener also re-renders the density chart overlay so
        // its channel labels pick up the resolved SSID at the same
        // moment the table does, plus the insight card so its
        // "hidden SSIDs resolved" line is always honest about what the
        // capture pipeline has learned.
        beaconStore.addListener(() -> Platform.runLater(() -> {
            apTable.refresh();
            densityView.setAccessPoints(latestSnapshot);
            refreshInsight();
        }));

        bandCombo.getItems().addAll(WifiBand.values());
        bandCombo.getSelectionModel().select(WifiBand.ALL);
        bandCombo.setMaxWidth(Double.MAX_VALUE);
        FxControls.withTooltip(bandCombo,
                "Pick which Wi-Fi band(s) to scan. Picking a band reconfigures "
                + "the main spectrum sweep - look at the chart in the main "
                + "window to see it switch over. The AP list below filters "
                + "to whatever the spectrum is sweeping.");

        rangeRow = buildRangeRow();
        rangeRow.setVisible(false);
        rangeRow.setManaged(false);
        rangeReadout.getStyleClass().add("preset-caption");
        scanStatusLabel.getStyleClass().add("preset-caption");

        bandCombo.valueProperty().addListener((obs, o, n) -> onBandChanged(n));
        startSpinner.valueProperty().addListener((obs, o, n) -> onSpinnerChanged());
        stopSpinner.valueProperty().addListener((obs, o, n) -> onSpinnerChanged());

        filterToScanRangeBox.setSelected(true);
        FxControls.withTooltip(filterToScanRangeBox,
                "When ON the AP list only shows access points whose centre "
                + "frequency falls inside what the SDR is currently sweeping. "
                + "Turn OFF to see every AP your Wi-Fi adapter can hear, even "
                + "ones outside the current spectrum range.");
        filterToScanRangeBox.selectedProperty().addListener(
                (obs, o, n) -> applyApSnapshot());

        apMarkersBox = FxControls.checkBox(
                "Show AP markers on spectrum chart", settings.isApMarkersVisible());
        FxControls.withTooltip(apMarkersBox,
                "When ON each Wi-Fi access point is drawn on the main spectrum "
                + "chart as a translucent box at its centre frequency, with the "
                + "box height matching the reported RSSI. Turn OFF for a clean "
                + "trace; the AP table below is unaffected.");

        configureApTable();
        apCountsLabel.getStyleClass().add("preset-caption");

        trendChart = new ApTrendChart(wifiScanService, beaconStore);
        trendChart.setHeight(140);
        trendChart.heightProperty().addListener((obs, o, n) -> trendChart.refresh());

        occupancyView = new ChannelOccupancyView(settings);
        // Coalescing dispatcher: at most one runLater in flight, latest
        // snapshot wins. Without this, a slow ChannelOccupancyView repaint
        // would let pending snapshots pile up on the FX queue (each holds
        // a ~30-element list, but multiplied by sweep rate this becomes
        // a steady leak that froze the app after a few minutes).
        occupancyService.addListener(
                new FxCoalescingDispatcher<>(snap -> {
                    latestOccupancy = snap;
                    occupancyView.setSnapshot(snap);
                    refreshInsight();
                }));
        // Seed with whatever the service already has so the rows render
        // immediately on first show instead of waiting for the first sweep.
        latestOccupancy = occupancyService.getLatest();
        occupancyView.setSnapshot(latestOccupancy);
        // Co/adjacent-channel counts arrive on the scan stream's cadence
        // (much slower than spectrum frames). Push them into the same
        // view so the C/A labels next to each channel bar update live as
        // APs come and go.
        interferenceService.addListener(
                new FxCoalescingDispatcher<>(occupancyView::setInterference));
        occupancyView.setInterference(interferenceService.getLatest());
        // Drive the trend's line update when the user picks a new AP, and
        // again on every scan snapshot so the rightmost edge of the chart
        // stays "now" without the user having to re-click.
        apTable.getSelectionModel().selectedItemProperty().addListener(
                (obs, o, n) -> trendChart.setSelected(n));

        // Subscribe immediately so the table starts populating before the
        // window is even shown - the next show() opens with data.
        wifiScanService.addListener(snap -> Platform.runLater(() -> {
            latestSnapshot = (snap == null) ? List.of() : snap;
            applyApSnapshot();
            trendChart.refresh();
            // Feed the unfiltered list - DensityChartView clips to the
            // current sweep range itself and the band picker above the
            // chart already drives that range, so passing the full
            // snapshot keeps the overlay accurate when the user
            // narrows the spectrum but leaves "filter to scan range"
            // OFF for the table.
            densityView.setAccessPoints(latestSnapshot);
            refreshInsight();
        }));

        // React to scan-range changes from anywhere (this window or the main
        // window's Scan tab). Both observable values matter - getEffectivePlan
        // falls back to getFrequency when no multi-range plan is active.
        // syncFromSettings reflects the change back into the band combo and
        // spinners so picking 5120-5880 in the main Frequency selector also
        // moves the picker here, and vice versa.
        settings.getFrequencyPlan().addListener(
                () -> Platform.runLater(this::onScanPlanChanged));
        settings.getFrequency().addListener(
                () -> Platform.runLater(this::onScanPlanChanged));

        // ---------------------------- Header strip
        Label badge = new Label(
                "Pick a band below and the SDR will sweep it. "
                + "Everything on this page reflects what is currently in the air.");
        badge.setWrapText(true);
        badge.getStyleClass().add("wifi-mode-badge");

        // ---------------------------- 1. Survey: AP table
        // The AP table is the most concrete artifact a user looks at,
        // so it stays always-expanded and at the top of the live data.
        // The two checkboxes that affect *what* the table shows live in
        // the table's own card so they read as table options, not as
        // global Wi-Fi options.
        VBox apSection = FxControls.sectionWithHowTo(
                "Visible access points",
                "Live list from the OS Wi-Fi scanner. Click a row to load its 60 s RSSI trend below. "
                + "Sortable columns; \"(hidden)\" rows reveal a name when monitor capture catches a probe response.",
                filterToScanRangeBox, apMarkersBox, apCountsLabel, apTable);
        VBox.setVgrow(apTable, Priority.ALWAYS);
        apTable.setPrefHeight(280);

        // ---------------------------- 2. Channel utilization (responsive 2-col)
        // Duty cycle and density chart answer the same question - "which
        // channels are busy, and for how long" - from two angles, so we
        // show them side by side when the window is wide enough so the
        // user can correlate the two without scrolling. The HBox falls
        // back to single-column on a narrow window (< 880 px) so the
        // density chart stays readable at small sizes.
        long winMin = ChannelOccupancyService.DEFAULT_WINDOW_MS / 60_000L;
        VBox occupancyWrap = new VBox(4,
                captionLabel(String.format("Per-channel duty (last %d min, signals stronger than %.0f dBm)",
                        winMin, ChannelOccupancyService.DEFAULT_THRESHOLD_DBM)),
                occupancyView);
        VBox densityWrap = new VBox(4,
                captionLabel("Density chart (frequency x dBm x count) - persistent streaks = real APs"),
                densityView);
        densityView.setHeight(220);
        densityService.addListener(
                new FxCoalescingDispatcher<>(densityView::setSnapshot));
        densityView.setSnapshot(densityService.getLatest());

        HBox channelsRow = new HBox(12, occupancyWrap, densityWrap);
        HBox.setHgrow(occupancyWrap, Priority.ALWAYS);
        HBox.setHgrow(densityWrap, Priority.ALWAYS);
        occupancyWrap.setMaxWidth(Double.MAX_VALUE);
        densityWrap.setMaxWidth(Double.MAX_VALUE);
        // Bind each canvas's width to its wrapper so the heatmap
        // resizes when the user resizes the window. The HBox already
        // distributes the available space evenly between the two
        // wrappers via Hgrow=ALWAYS, so we don't need a manual /2.
        occupancyView.widthProperty().bind(occupancyWrap.widthProperty());
        densityView.widthProperty().bind(densityWrap.widthProperty());

        VBox channelSection = FxControls.sectionWithHowTo(
                "Channel utilization",
                "Two views of the same question: which channels are busy, and for how long. "
                + "Tall bars on the left = channels saturated by Wi-Fi traffic now. "
                + "Bright streaks on the right = signals that have been sitting on a channel for many sweeps.",
                channelsRow);

        // ---------------------------- 3. Trend strip (collapsible, default open)
        trendChart.widthProperty().bind(apSection.widthProperty().subtract(24));
        VBox trendBody = FxControls.sectionWithHowTo(
                "Selected AP - rolling 60 s RSSI",
                "Pick a row in the AP table above. Higher (less negative) dBm = stronger signal. "
                + "Watch for periodic dips - they often correlate with neighbours' bursts on the same channel.",
                trendChart);
        TitledPane trendPane = FxControls.collapsible("Selected AP trend", true, trendBody);

        // ---------------------------- 4. Other RF (collapsible, default closed)
        // Interferers belong in the Wi-Fi window because they share the
        // 2.4 GHz band, but a typical user opens this window to look at
        // their Wi-Fi. Default-collapsed keeps the page compact; users
        // who hunt for a microwave / video bridge / Zigbee mesh just
        // expand it.
        interfererClassifier.addListener(
                new FxCoalescingDispatcher<>(interfererListView::setSnapshot));
        interfererListView.setSnapshot(interfererClassifier.getLatest());
        VBox interfererBody = FxControls.sectionWithHowTo(
                "Detected interferers",
                "Heuristic classification of non-Wi-Fi 2.4 GHz signals (bandwidth + sweep-to-sweep variance). "
                + "Wi-Fi APs are filtered out. False positives are possible in noisy environments.",
                interfererListView);
        TitledPane interfererPane = FxControls.collapsible(
                "Other RF in 2.4 GHz (interferers)", false, interfererBody);

        // ---------------------------- 5. Monitor capture (collapsible, default closed)
        // Phase-2 experiment lives behind a collapsed pane at the
        // bottom: it requires Npcap, can lock the OS adapter, and is
        // not what most users open this window for. Hiding it by
        // default avoids accidental Start clicks while still keeping
        // it discoverable.
        TitledPane capturePane = FxControls.collapsible(
                "Monitor capture (advanced, requires Npcap)", false,
                monitorCapturePanel.node());

        // ---------------------------- 6. Setup (collapsible, default open)
        // Adapter + scan range live in a single Setup pane so a user
        // who has dialled in their preferred band can collapse it once
        // and stop staring at config controls. Default-expanded so the
        // first-time user sees the band picker without hunting.
        VBox setupBody = new VBox(10,
                FxControls.labeled("Wi-Fi adapter", buildAdapterCombo()),
                adapterHintLabel,
                FxControls.labeled("Scan band", bandCombo),
                rangeRow,
                rangeReadout,
                scanStatusLabel);
        TitledPane setupPane = FxControls.collapsible("Setup (adapter + scan range)", true, setupBody);

        VBox content = new VBox(12,
                badge,
                insightCard,
                setupPane,
                apSection,
                channelSection,
                trendPane,
                interfererPane,
                capturePane);
        content.setPadding(new Insets(12));

        ScrollPane scroller = new ScrollPane(content);
        scroller.setFitToWidth(true);
        scroller.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);

        Scene scene = new Scene(scroller, 780, 940);
        // dark.css makes .scroll-pane transparent so embedded scrollers blend
        // into their parent. Here the scroll pane IS the scene root, so the
        // Scene's default-white fill leaks through every transparent area
        // (table corner, gaps between sections, area beyond content). Pin the
        // Scene fill to the same shade as -fx-background in dark.css so the
        // window looks identical to the main window.
        scene.setFill(Color.web("#1b1b1f"));
        scene.getStylesheets().add(getClass().getResource(
                "/jspectrumanalyzer/fx/theme/dark.css").toExternalForm());
        // Esc closes the window so a keyboard-driven user can dismiss it
        // without reaching for the title-bar X. Only fires for the literal
        // Escape key so it never blocks normal text editing.
        scene.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            if (e.getCode() == KeyCode.ESCAPE) {
                stage.hide();
                e.consume();
            }
        });

        stage.setScene(scene);
        stage.setTitle("Wi-Fi - HackRF Spectrum Analyzer");
        // initModality NONE so both windows are interactable simultaneously.
        stage.initModality(Modality.NONE);
        // The AP marker overlay defaults to OFF: it would clutter the
        // chart for users who never use the Wi-Fi feature. Tie it to the
        // window's lifecycle so opening the window auto-shows the
        // markers and closing it auto-hides them, while still letting
        // the user override either way via the checkbox in this window
        // or the toggle in the Display tab. We snapshot the user's
        // pre-show state so closing the window restores whatever they
        // had before, instead of always forcing OFF (a user who
        // explicitly enabled markers in Display would otherwise be
        // surprised when closing the window switched them off).
        stage.setOnShown(e -> {
            apMarkerStateBeforeShow = settings.isApMarkersVisible().getValue();
            if (Boolean.FALSE.equals(apMarkerStateBeforeShow)) {
                settings.isApMarkersVisible().setValue(true);
            }
        });
        stage.setOnHidden(e -> {
            // Restore only if we were the ones who flipped it on; if the
            // user manually enabled markers via the Display tab while the
            // window was open, leave them on. The simplest "did I touch
            // it?" test is: was the value FALSE when we showed and is it
            // still TRUE now? Anything else means the user has opinions.
            if (Boolean.FALSE.equals(apMarkerStateBeforeShow)
                    && Boolean.TRUE.equals(settings.isApMarkersVisible().getValue())) {
                settings.isApMarkersVisible().setValue(false);
            }
            apMarkerStateBeforeShow = null;
            // Closing the window must release the OS RFMON handle - both
            // because libpcap allows only one open monitor capture per
            // adapter, and because nobody is watching the counters
            // anymore so the capture would just burn CPU on the poll
            // thread for nothing.
            monitorCapturePanel.shutdown();
        });

        updateRangeReadout(WifiBand.ALL, 0, 0);
        updateScanStatus();
        // Reflect the main window's current frequency picker into the band
        // combo at startup so opening the window over a 5 GHz scan lands on
        // "5 GHz" instead of forcing "All bands".
        syncFromSettings();
        applyApSnapshot();
        refreshInsight();
    }

    /**
     * Show or focus the window. The first call also applies the Wi-Fi
     * multi-range plan + channel-grid overlay so the window's AP list has
     * something to filter against; subsequent calls leave the spectrum
     * untouched (the user might have intentionally retuned it).
     *
     * @param owner main-window stage to use as the owned-window parent so
     *              the Wi-Fi window closes automatically when the main
     *              window does. May be {@code null} for a free-floating
     *              window.
     */
    public void show(Window owner) {
        if (!appliedOnce) {
            appliedOnce = true;
            applyUiSelection();
            applyOverlay();
        }
        if (stage.getOwner() == null && owner != null) {
            stage.initOwner(owner);
        }
        if (stage.isShowing()) {
            stage.toFront();
            stage.requestFocus();
        } else {
            stage.show();
        }
    }

    /** Hide the window programmatically (used by the toolbar toggle button). */
    public void hide() {
        stage.hide();
    }

    /** {@code true} while the window is on screen - drives the toolbar toggle. */
    public boolean isShowing() {
        return stage.isShowing();
    }

    // ---------------------------------------------------------------- UI build

    /**
     * Populate the adapter combo with "All adapters" + every Wi-Fi
     * interface the OS exposes. Wired so picking an entry pushes the
     * canonical GUID into {@link SettingsStore#getSelectedWifiAdapterGuid()};
     * the FX-app-level listener forwards that to the scanner.
     *
     * <p>If the OS exposes only a single adapter we still show it in the
     * list (as feedback that adapter discovery worked) but the user
     * gains nothing from picking it - the "All" entry behaves
     * identically.
     */
    private ComboBox<AdapterChoice> buildAdapterCombo() {
        adapterCombo.setMaxWidth(Double.MAX_VALUE);
        FxControls.withTooltip(adapterCombo,
                "Restrict the AP scan to one Wi-Fi card. \"All adapters\" loops "
                + "over every Wi-Fi interface the OS reports (the default and "
                + "the only option on machines with a single adapter). "
                + "Pick a specific card when you have two radios and want "
                + "consistent RSSI from the same physical antenna.");

        List<AdapterChoice> choices = new ArrayList<>();
        choices.add(AdapterChoice.ALL);
        for (WifiAdapter a : wifiScanService.listAdapters()) {
            choices.add(new AdapterChoice(a));
        }
        adapterCombo.getItems().setAll(choices);

        String savedGuid = settings.getSelectedWifiAdapterGuid().getValue();
        AdapterChoice initial = AdapterChoice.ALL;
        for (AdapterChoice c : choices) {
            if (c.guidHex().equalsIgnoreCase(savedGuid)) { initial = c; break; }
        }
        adapterCombo.getSelectionModel().select(initial);
        updateAdapterHint(initial);

        adapterCombo.valueProperty().addListener((obs, o, n) -> {
            if (n == null) return;
            settings.getSelectedWifiAdapterGuid().setValue(n.guidHex());
            updateAdapterHint(n);
        });
        return adapterCombo;
    }

    /**
     * Refresh the secondary line under the adapter combo so the user
     * always sees the GUID of the picked adapter (handy when matching
     * against {@code netsh wlan show wirelesscapabilities} output).
     */
    private void updateAdapterHint(AdapterChoice c) {
        if (c == null || c == AdapterChoice.ALL) {
            adapterHintLabel.setText("");
        } else {
            adapterHintLabel.setText(c.guidHex());
        }
        adapterHintLabel.getStyleClass().setAll("preset-caption");
    }

    /**
     * ComboBox row model. Keeps the WifiAdapter pair around for the
     * tooltip / hint label and serialises to a friendly label so the
     * combo doesn't dump raw GUIDs at the user.
     */
    private record AdapterChoice(WifiAdapter adapter) {
        static final AdapterChoice ALL = new AdapterChoice(null);

        String guidHex() {
            return (adapter == null) ? WifiAdapter.ALL : adapter.guidHex();
        }

        @Override
        public String toString() {
            if (adapter == null) return "All adapters";
            String d = adapter.description();
            return (d == null || d.isBlank()) ? adapter.guidHex() : d;
        }
    }

    private HBox buildRangeRow() {
        startSpinner.setValueFactory(new SpinnerValueFactory.IntegerSpinnerValueFactory(
                SettingsStore.FREQ_MIN_MHZ, SettingsStore.FREQ_MAX_MHZ,
                SettingsStore.FREQ_MIN_MHZ, 1));
        startSpinner.setEditable(true);
        startSpinner.setPrefWidth(110);
        FxControls.withTooltip(startSpinner,
                "Lower edge of the scan in MHz. Use this to channel-restrict "
                + "inside the chosen band (e.g. set to 2451 to look only at "
                + "ch 11 on 2.4 GHz).");

        stopSpinner.setValueFactory(new SpinnerValueFactory.IntegerSpinnerValueFactory(
                SettingsStore.FREQ_MIN_MHZ, SettingsStore.FREQ_MAX_MHZ,
                SettingsStore.FREQ_MAX_MHZ, 1));
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

    /**
     * Pick the best display name for a row in the AP table. Same
     * three-tier resolution as {@code ApMarkerCanvas.displaySsidFor}:
     * real SSID > probe-response-discovered SSID > literal "(hidden)".
     * Kept here as a small private helper rather than promoted to a
     * shared utility because the call site is a one-liner and inlining
     * makes the table-column wiring readable.
     */
    private String displaySsidFor(WifiAccessPoint ap) {
        if (!ap.ssid().isEmpty()) return ap.ssid();
        return beaconStore.discoveredSsid(ap.bssid())
                .map(name -> "(hidden: " + name + ")")
                .orElse("(hidden)");
    }

    private void configureApTable() {
        // Column widths trimmed so the table fits at ~720 px content
        // width without horizontal scroll. Sum below is 600; the
        // CONSTRAINED policy hands the leftover to SSID since that's
        // the column users care about reading in full.
        TableColumn<WifiAccessPoint, String> ssidCol = new TableColumn<>("SSID");
        ssidCol.setCellValueFactory(d -> new SimpleStringProperty(displaySsidFor(d.getValue())));
        ssidCol.setPrefWidth(140);

        TableColumn<WifiAccessPoint, String> bssidCol = new TableColumn<>("BSSID");
        bssidCol.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().bssid()));
        bssidCol.setPrefWidth(130);

        TableColumn<WifiAccessPoint, Integer> rssiCol = new TableColumn<>("RSSI dBm");
        rssiCol.setCellValueFactory(d -> new SimpleObjectProperty<>(d.getValue().rssiDbm()));
        rssiCol.setPrefWidth(65);
        rssiCol.setComparator(Comparator.naturalOrder());

        TableColumn<WifiAccessPoint, Integer> chCol = new TableColumn<>("Ch");
        chCol.setCellValueFactory(d -> new SimpleObjectProperty<>(d.getValue().channel()));
        chCol.setPrefWidth(40);

        TableColumn<WifiAccessPoint, String> bandCol = new TableColumn<>("Band");
        bandCol.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().bandLabel()));
        bandCol.setPrefWidth(55);

        TableColumn<WifiAccessPoint, Integer> freqCol = new TableColumn<>("MHz");
        freqCol.setCellValueFactory(d -> new SimpleObjectProperty<>(d.getValue().centerFrequencyMhz()));
        freqCol.setPrefWidth(55);

        TableColumn<WifiAccessPoint, String> widthCol = new TableColumn<>("Width");
        widthCol.setCellValueFactory(d -> new SimpleStringProperty(
                d.getValue().bandwidthMhz() + " MHz"));
        widthCol.setPrefWidth(60);

        TableColumn<WifiAccessPoint, String> phyCol = new TableColumn<>("PHY");
        phyCol.setCellValueFactory(d -> new SimpleStringProperty(
                d.getValue().phyType() == null ? "" : d.getValue().phyType()));
        phyCol.setPrefWidth(55);

        apTable.getColumns().setAll(java.util.List.of(
                ssidCol, bssidCol, rssiCol, chCol, bandCol, freqCol, widthCol, phyCol));
        apTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_SUBSEQUENT_COLUMNS);
        apTable.setPlaceholder(new Label(
                wifiScanService.isAvailable()
                        ? "No APs visible inside the current scan range. "
                          + "Try widening the scan or unticking the filter above."
                        : "Native Wi-Fi API unavailable on this system - "
                          + "AP discovery disabled."));

        rssiCol.setSortType(TableColumn.SortType.DESCENDING);
        apTable.getSortOrder().clear();
        apTable.getSortOrder().add(rssiCol);
    }

    // ---------------------------------------------------------------- Filter + table refresh

    private void applyApSnapshot() {
        List<WifiAccessPoint> filtered = filterByScanRangeIfEnabled(latestSnapshot);

        // WifiAccessPoint is a record - its equals() compares every field
        // including rssiDbm, which changes on virtually every scan. So
        // setAll(filtered) replaces the row instances with new (equal-by-
        // BSSID but not equal-by-record) objects, and JavaFX's
        // SelectionModel drops the selection because the previously
        // selected reference is no longer in the list. Capture the
        // selected BSSID first, then re-select by BSSID after the swap so
        // the trend strip and the user's row highlight survive snapshots.
        WifiAccessPoint prevSelected = apTable.getSelectionModel().getSelectedItem();
        String selectedBssid = (prevSelected == null) ? null : prevSelected.bssid();

        apRows.setAll(filtered);
        apTable.sort();
        apCountsLabel.setText(buildCountsText(latestSnapshot, filtered));

        if (selectedBssid != null) {
            for (int i = 0; i < apRows.size(); i++) {
                if (selectedBssid.equals(apRows.get(i).bssid())) {
                    apTable.getSelectionModel().select(i);
                    break;
                }
            }
        }
    }

    private List<WifiAccessPoint> filterByScanRangeIfEnabled(List<WifiAccessPoint> aps) {
        if (!filterToScanRangeBox.isSelected()) return aps;
        List<FrequencyRange> ranges = currentScanRanges();
        if (ranges.isEmpty()) return aps;
        List<WifiAccessPoint> kept = new ArrayList<>(aps.size());
        for (WifiAccessPoint ap : aps) {
            if (isInAnyRange(ap.centerFrequencyMhz(), ranges)) kept.add(ap);
        }
        return kept;
    }

    /** Active scan plan as a flat list of segments. Empty list = "filter off". */
    private List<FrequencyRange> currentScanRanges() {
        FrequencyPlan plan = settings.getEffectivePlan();
        return (plan == null) ? List.of() : plan.segments();
    }

    private static boolean isInAnyRange(int mhz, List<FrequencyRange> ranges) {
        for (FrequencyRange r : ranges) {
            // Inclusive on both edges so an AP centred exactly at a band edge
            // (rare but possible, e.g. 2484 MHz on JP ch 14) still passes.
            if (mhz >= r.getStartMHz() && mhz <= r.getEndMHz()) return true;
        }
        return false;
    }

    private String buildCountsText(List<WifiAccessPoint> all, List<WifiAccessPoint> shown) {
        if (!wifiScanService.isAvailable()) {
            return "Native Wi-Fi API unavailable - AP discovery disabled.";
        }
        EnumMap<WifiAccessPoint.Band, Integer> counts = new EnumMap<>(WifiAccessPoint.Band.class);
        for (WifiAccessPoint.Band b : WifiAccessPoint.Band.values()) counts.put(b, 0);
        for (WifiAccessPoint ap : shown) {
            WifiAccessPoint.Band b = ap.band();
            if (b != null) counts.merge(b, 1, Integer::sum);
        }
        boolean filtering = filterToScanRangeBox.isSelected();
        String prefix = filtering
                ? "Visible APs in current scan: "
                : "Visible APs (all bands): ";
        return String.format(
                "%s2.4 GHz: %d  /  5 GHz: %d  /  6 GHz: %d  (%d of %d total)",
                prefix,
                counts.get(WifiAccessPoint.Band.GHZ_24),
                counts.get(WifiAccessPoint.Band.GHZ_5),
                counts.get(WifiAccessPoint.Band.GHZ_6),
                shown.size(), all.size());
    }

    // ---------------------------------------------------------------- Listeners + plan push

    private void onScanPlanChanged() {
        syncFromSettings();
        updateScanStatus();
        applyApSnapshot();
        refreshInsight();
    }

    /**
     * Recompute the live insight card from whatever inputs we currently
     * have cached. Callers fire this from any of the three input
     * streams (scan / occupancy / plan) because the insight wording
     * depends on all three; recomputing more often than strictly
     * needed is harmless because the worst case is a small text label
     * update and the underlying class is purely functional.
     */
    private void refreshInsight() {
        insightCard.recompute(latestSnapshot, latestOccupancy,
                settings.getEffectivePlan());
    }

    /** Caption-style {@link Label} used inside cards under each chart. */
    private static Label captionLabel(String text) {
        Label l = new Label(text);
        l.setWrapText(true);
        l.getStyleClass().add("preset-caption");
        return l;
    }

    /**
     * Reflect the current {@link SettingsStore} state into the band combo and
     * spinners. Idempotent: only writes UI controls when the computed target
     * differs from what is already shown, so listeners can call this without
     * triggering write-back loops.
     *
     * <p>Decision tree:
     * <ul>
     *   <li>Multi-segment plan equal to the all-bands preset -> band = ALL.</li>
     *   <li>Single-segment plan or no plan at all -> derive the band from the
     *       segment edges. If the range fits inside one of {@link WifiBand},
     *       select that band and set the spinners accordingly.</li>
     *   <li>Anything else (custom multi-range plan, range outside every Wi-Fi
     *       band) is left alone - the user is doing non-Wi-Fi work and the
     *       combo's previous selection is the better default for them when
     *       they come back.</li>
     * </ul>
     */
    private void syncFromSettings() {
        FrequencyPlan plan = settings.getFrequencyPlan().getValue();
        FrequencyPlan allBands = lookupAllBandsPlan();

        WifiBand targetBand;
        int targetStart;
        int targetStop;

        if (plan != null && allBands != null && plan.equals(allBands)) {
            targetBand = WifiBand.ALL;
            targetStart = 0;
            targetStop = 0;
        } else {
            // Pick the source range: an explicit single-segment plan (us) wins,
            // otherwise fall back to the legacy single-range frequency value.
            int s, e;
            if (plan != null && plan.segmentCount() == 1) {
                s = plan.firstStartMHz();
                e = plan.lastEndMHz();
            } else if (plan == null) {
                FrequencyRange single = settings.getFrequency().getValue();
                if (single == null) return;
                s = single.getStartMHz();
                e = single.getEndMHz();
            } else {
                // Multi-segment but not the Wi-Fi-all-bands preset: a custom
                // plan from the main window. Don't override the user's combo
                // pick here.
                return;
            }
            WifiBand fitted = bandForRange(s, e);
            if (fitted == null) return; // range is outside every Wi-Fi band
            targetBand = fitted;
            targetStart = s;
            targetStop = e;
        }

        if (uiAlreadyMatches(targetBand, targetStart, targetStop)) return;

        suppressEvents = true;
        try {
            bandCombo.getSelectionModel().select(targetBand);
            if (targetBand == WifiBand.ALL) {
                rangeRow.setVisible(false);
                rangeRow.setManaged(false);
            } else {
                rangeRow.setVisible(true);
                rangeRow.setManaged(true);
                SpinnerValueFactory.IntegerSpinnerValueFactory startFac =
                        (SpinnerValueFactory.IntegerSpinnerValueFactory) startSpinner.getValueFactory();
                SpinnerValueFactory.IntegerSpinnerValueFactory stopFac =
                        (SpinnerValueFactory.IntegerSpinnerValueFactory) stopSpinner.getValueFactory();
                startFac.setMin(targetBand.startMhz);
                startFac.setMax(targetBand.stopMhz);
                stopFac.setMin(targetBand.startMhz);
                stopFac.setMax(targetBand.stopMhz);
                startFac.setValue(clampToBand(targetStart, targetBand));
                stopFac.setValue(clampToBand(targetStop, targetBand));
            }
            updateRangeReadout(targetBand,
                    targetBand == WifiBand.ALL ? 0 : startSpinner.getValue(),
                    targetBand == WifiBand.ALL ? 0 : stopSpinner.getValue());
        } finally {
            suppressEvents = false;
        }
    }

    private static int clampToBand(int v, WifiBand band) {
        if (v < band.startMhz) return band.startMhz;
        if (v > band.stopMhz) return band.stopMhz;
        return v;
    }

    /**
     * Find the {@link WifiBand} whose regulatory range fully contains the
     * supplied frequency edges. {@link WifiBand#ALL} is excluded - this is
     * specifically about single-band selections.
     */
    private static WifiBand bandForRange(int startMhz, int stopMhz) {
        if (stopMhz <= startMhz) return null;
        for (WifiBand b : WifiBand.values()) {
            if (b == WifiBand.ALL) continue;
            if (startMhz >= b.startMhz && stopMhz <= b.stopMhz) return b;
        }
        return null;
    }

    private boolean uiAlreadyMatches(WifiBand targetBand, int targetStart, int targetStop) {
        if (bandCombo.getValue() != targetBand) return false;
        if (targetBand == WifiBand.ALL) return true;
        Integer s = startSpinner.getValue();
        Integer e = stopSpinner.getValue();
        return s != null && e != null && s == targetStart && e == targetStop;
    }

    private void onBandChanged(WifiBand band) {
        if (suppressEvents) return;
        if (band == null) return;
        if (band == WifiBand.ALL) {
            rangeRow.setVisible(false);
            rangeRow.setManaged(false);
            updateRangeReadout(band, 0, 0);
        } else {
            rangeRow.setVisible(true);
            rangeRow.setManaged(true);
            suppressEvents = true;
            try {
                SpinnerValueFactory.IntegerSpinnerValueFactory startFac =
                        (SpinnerValueFactory.IntegerSpinnerValueFactory) startSpinner.getValueFactory();
                SpinnerValueFactory.IntegerSpinnerValueFactory stopFac =
                        (SpinnerValueFactory.IntegerSpinnerValueFactory) stopSpinner.getValueFactory();
                startFac.setMin(band.startMhz);
                startFac.setMax(band.stopMhz);
                startFac.setValue(band.startMhz);
                stopFac.setMin(band.startMhz);
                stopFac.setMax(band.stopMhz);
                stopFac.setValue(band.stopMhz);
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
        updateRangeReadout(band, s, e);
        // Reject "stop <= start" silently - user may be mid-edit.
        if (e <= s) return;
        applyUiSelection();
    }

    /**
     * Push the current band / spinner selection to the settings model. Always
     * runs (no snapshot guard) - this window's whole purpose is to drive the
     * spectrum scan.
     *
     * <p>Single-band writes go through {@link SettingsStore#getFrequency()}
     * with {@link SettingsStore#getFrequencyPlan()} cleared. That way the
     * main window's {@code FrequencyRangeSelector} (bound two-way to
     * {@code frequency}) stays in sync with the band picker here, and the
     * Multi-band combo in the Scan tab shows "Off" because no plan is active.
     * "All bands" still goes through {@code frequencyPlan} so the Multi-band
     * combo selects the matching preset (see also the model-to-combo listener
     * wired in {@code ScanTab.buildMultiRangeCombo}).
     */
    private void applyUiSelection() {
        WifiBand band = bandCombo.getValue();
        if (band == null) return;
        if (band == WifiBand.ALL) {
            FrequencyPlan all = lookupAllBandsPlan();
            if (all != null) {
                sdrController.requestRetunePlan(all);
            } else {
                LOG.warn("Wi-Fi 2.4 + 5 + 6E preset missing; cannot apply 'All bands'.");
            }
            return;
        }
        Integer s = startSpinner.getValue();
        Integer e = stopSpinner.getValue();
        if (s == null || e == null || e <= s) return;
        // SdrController internally clears any active multi-segment plan
        // before publishing the new single range, so the engine won't
        // keep scanning the previous Wi-Fi-all-bands plan.
        sdrController.requestRetune(new FrequencyRange(s, e));
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
            rangeReadout.setText("Selected range: 2400-2500 + 5150-5895 + 5925-7125 MHz "
                    + "(stitched across all three Wi-Fi bands).");
        } else if (stopMhz <= startMhz) {
            rangeReadout.setText("Stop MHz must be greater than Start MHz.");
        } else {
            rangeReadout.setText(String.format(
                    "Selected range: %d-%d MHz (%s, %d MHz wide).",
                    startMhz, stopMhz, band.label, stopMhz - startMhz));
        }
    }

    /** Live "spectrum is currently scanning N segments totalling M MHz" line. */
    private void updateScanStatus() {
        List<FrequencyRange> ranges = currentScanRanges();
        if (ranges.isEmpty()) {
            scanStatusLabel.setText("Spectrum: no active scan range.");
            return;
        }
        int totalMhz = 0;
        for (FrequencyRange r : ranges) totalMhz += (r.getEndMHz() - r.getStartMHz());
        if (ranges.size() == 1) {
            FrequencyRange r = ranges.get(0);
            scanStatusLabel.setText(String.format(
                    "Spectrum is scanning: %d-%d MHz (%d MHz wide).",
                    r.getStartMHz(), r.getEndMHz(), totalMhz));
        } else {
            scanStatusLabel.setText(String.format(
                    "Spectrum is scanning: %d segments, %d MHz total.",
                    ranges.size(), totalMhz));
        }
    }

    // ---------------------------------------------------------------- Helpers

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
            LOG.warn("Could not load allocation tables when opening Wi-Fi window", ex);
            return null;
        }
    }

    /**
     * Wi-Fi sub-bands the user can pick. Edges follow the same regulatory
     * boundaries as the {@code Wi-Fi 2.4 + 5 + 6E} multi-range preset so a
     * single-band scan lines up with the matching segment of the all-bands
     * view.
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
}
