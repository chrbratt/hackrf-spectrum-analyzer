package jspectrumanalyzer.fx;

import java.awt.geom.Rectangle2D;

import javafx.application.Platform;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.SplitPane;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextInputControl;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.canvas.Canvas;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import jspectrumanalyzer.core.HackRFSettings;
import jspectrumanalyzer.fx.chart.AllocationOverlayCanvas;
import jspectrumanalyzer.fx.chart.ApMarkerCanvas;
import jspectrumanalyzer.fx.chart.ChartZoomController;
import jspectrumanalyzer.fx.chart.LegendOverlay;
import jspectrumanalyzer.fx.chart.PersistentDisplayController;
import jspectrumanalyzer.fx.chart.SpectrumChart;
import jspectrumanalyzer.fx.chart.WaterfallCanvas;
import jspectrumanalyzer.fx.engine.SdrController;
import jspectrumanalyzer.fx.engine.SpectrumEngine;
import jspectrumanalyzer.fx.engine.SpectrumFrame;
import jspectrumanalyzer.fx.frequency.FrequencyRangeValidator;
import jspectrumanalyzer.fx.model.SettingsStore;
import jspectrumanalyzer.fx.ui.ChartToolbar;
import jspectrumanalyzer.fx.ui.DisplayTab;
import jspectrumanalyzer.fx.ui.ParamsTab;
import jspectrumanalyzer.fx.ui.RecordingTab;
import jspectrumanalyzer.fx.ui.ScanTab;
import jspectrumanalyzer.fx.ui.WifiWindow;
import jspectrumanalyzer.nativebridge.HackRFDeviceInfo;
import jspectrumanalyzer.nativebridge.HackRFSweepNativeBridge;
import jspectrumanalyzer.wifi.WifiScanService;

/**
 * Main JavaFX window wiring together settings, chart, waterfall, persistent display,
 * and the status bar. Owns the frame-consumer callbacks that glue the headless
 * {@link SpectrumEngine} to the FX UI.
 */
public final class MainWindow {

    private static final long CHART_REFRESH_INTERVAL_MS = 33;

    private final SettingsStore settings;
    private final SpectrumEngine engine;
    /**
     * Single point of intent for retune / start / stop. Passed down to
     * every UI component that wants to drive the SDR rather than each
     * one writing directly to {@link SettingsStore} mutators - see
     * {@link SdrController} for the design rationale.
     */
    private final SdrController sdrController;
    private final WifiScanService wifiScanService;
    private final jspectrumanalyzer.wifi.ChannelOccupancyService occupancyService;
    private final jspectrumanalyzer.wifi.ChannelInterferenceService interferenceService;
    private final jspectrumanalyzer.wifi.DensityHistogramService densityService;
    private final jspectrumanalyzer.wifi.InterfererClassifier interfererClassifier;
    /**
     * App-scope {@link jspectrumanalyzer.wifi.capture.MonitorModeCapture}
     * shared with the lazily-built Wi-Fi window. Held here (not inside
     * the Wi-Fi window) so a future second consumer (e.g. a dedicated
     * capture window) can reuse the same handle - libpcap allows only
     * one open RFMON capture per adapter at a time.
     */
    private final jspectrumanalyzer.wifi.capture.MonitorModeCapture monitorCapture;
    /**
     * App-scope {@link jspectrumanalyzer.wifi.capture.BeaconStore} that
     * the monitor-capture panel populates and the AP marker overlay
     * reads from. Held here (not pulled from the Wi-Fi window) so the
     * marker overlay can substitute discovered SSIDs even when the
     * Wi-Fi window is closed - the user might run a capture, close the
     * window, and still expect the markers on the main spectrum chart
     * to render the resolved names.
     */
    private final jspectrumanalyzer.wifi.capture.BeaconStore beaconStore;

    private final SpectrumChart spectrumChart;
    private final WaterfallCanvas waterfall;
    private final PersistentDisplayController persistent;
    private final AllocationOverlayCanvas allocationOverlay;
    private final ApMarkerCanvas apMarkerOverlay;
    private final Canvas dragOverlay;

    private final Label hardwareStatus = new Label("Stopped");
    private final Label peakLabel = new Label("");
    private final Label totalPowerLabel = new Label("");

    private volatile long lastChartUpdate = 0;
    private int persistentFrameCounter = 0;

    /**
     * Lazily created Wi-Fi window. Held as a field so a second toolbar click
     * brings the existing window to front instead of opening a duplicate
     * (and so its band/spinner selections survive a hide/show cycle).
     */
    private WifiWindow wifiWindow;

    /**
     * Holds the most recent frame waiting to be rendered on the FX thread, plus a
     * flag that is true while a runLater is still pending. Together they prevent
     * the runLater queue from filling up faster than FX can drain it (the classic
     * cause of stutter that worsens over time).
     */
    private final java.util.concurrent.atomic.AtomicReference<SpectrumFrame> pendingFrame =
            new java.util.concurrent.atomic.AtomicReference<>();
    private final java.util.concurrent.atomic.AtomicBoolean refreshScheduled =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    public MainWindow(SettingsStore settings, SpectrumEngine engine,
                      SdrController sdrController,
                      WifiScanService wifiScanService,
                      jspectrumanalyzer.wifi.ChannelOccupancyService occupancyService,
                      jspectrumanalyzer.wifi.ChannelInterferenceService interferenceService,
                      jspectrumanalyzer.wifi.DensityHistogramService densityService,
                      jspectrumanalyzer.wifi.InterfererClassifier interfererClassifier,
                      jspectrumanalyzer.wifi.capture.MonitorModeCapture monitorCapture,
                      jspectrumanalyzer.wifi.capture.BeaconStore beaconStore) {
        this.settings = settings;
        this.engine = engine;
        this.sdrController = sdrController;
        this.wifiScanService = wifiScanService;
        this.occupancyService = occupancyService;
        this.interferenceService = interferenceService;
        this.densityService = densityService;
        this.interfererClassifier = interfererClassifier;
        this.monitorCapture = monitorCapture;
        this.beaconStore = beaconStore;
        this.spectrumChart = new SpectrumChart(settings);
        this.waterfall = new WaterfallCanvas();
        this.persistent = new PersistentDisplayController(settings, spectrumChart);
        this.allocationOverlay = new AllocationOverlayCanvas(settings);
        this.apMarkerOverlay = new ApMarkerCanvas(settings, beaconStore);
        this.dragOverlay = new Canvas();
        this.dragOverlay.setMouseTransparent(true);

        // Push AP snapshots from the background scan service onto the marker
        // overlay. The service callback fires on its scheduler thread; hop to
        // FX before touching the canvas.
        wifiScanService.addListener(snap ->
                Platform.runLater(() -> apMarkerOverlay.setAccessPoints(snap)));
        // Seed with whatever the service already has so reopening the main
        // window after a reload paints markers immediately instead of waiting
        // for the next scan tick.
        apMarkerOverlay.setAccessPoints(wifiScanService.getLatest());

        // When the beacon store learns a new SSID (probe response that
        // resolved a hidden BSSID, or a fresh BSS Load advertisement),
        // poke the marker overlay so the new label paints without the
        // user having to wait for the next 1 s wifi-scan tick. The
        // store debounces internally - we get one wake per ~16 mgmt
        // frames, not one per beacon.
        beaconStore.addListener(() -> Platform.runLater(apMarkerOverlay::redraw));

        settings.registerListener(new HackRFSettings.HackRFEventAdapter() {
            @Override
            public void hardwareStatusChanged(boolean sending) {
                Platform.runLater(() -> {
                    if (sending) {
                        // Surface the actual board name (e.g. PRALINE = HackRF
                        // Pro) once libhackrf has populated it; falls back to
                        // a generic label if the read failed.
                        HackRFDeviceInfo info = safeGetOpenedInfo();
                        String label = (info != null)
                                ? "HW: " + info.boardName()
                                : "HW sending data";
                        hardwareStatus.setText(label);
                    } else {
                        hardwareStatus.setText(settings.isRunningRequested().getValue()
                                ? "HW idle (waiting for device)"
                                : "Stopped");
                    }
                    hardwareStatus.pseudoClassStateChanged(
                            javafx.css.PseudoClass.getPseudoClass("connected"), sending);
                });
            }
        });

        settings.isRunningRequested().addListener(() -> Platform.runLater(() -> {
            if (!settings.isRunningRequested().getValue()) {
                hardwareStatus.setText("Stopped");
                hardwareStatus.pseudoClassStateChanged(
                        javafx.css.PseudoClass.getPseudoClass("connected"), false);
            }
        }));

        settings.getSpectrumPaletteStart().addListener(() ->
                waterfall.setSpectrumPaletteStart(settings.getSpectrumPaletteStart().getValue()));
        settings.getSpectrumPaletteSize().addListener(() ->
                waterfall.setSpectrumPaletteSize(Math.max(1, settings.getSpectrumPaletteSize().getValue())));
        waterfall.setSpectrumPaletteStart(settings.getSpectrumPaletteStart().getValue());
        waterfall.setSpectrumPaletteSize(Math.max(1, settings.getSpectrumPaletteSize().getValue()));

        // Apply the user's palette pick to both the live waterfall and the
        // persistent display, then keep them in sync when the user picks a
        // new theme from the Display tab. Persistent display lives behind a
        // controller wrapper, so we use that wrapper's setter.
        applyWaterfallTheme();
        settings.getWaterfallTheme().addListener(() -> Platform.runLater(this::applyWaterfallTheme));

        // Funnel waterfall mode toggle. setFunnelEnabled clears the
        // existing scrollback because the per-tier time scale changes;
        // that's the right behaviour - stretching old rows from flat
        // mode into a tier with stride 8 would mislabel time.
        waterfall.setFunnelEnabled(settings.isWaterfallFunnel().getValue());
        settings.isWaterfallFunnel().addListener(() -> Platform.runLater(() ->
                waterfall.setFunnelEnabled(settings.isWaterfallFunnel().getValue())));

        engine.addFrameConsumer(this::onNewFrameOffFx);
    }

    /**
     * Push the currently selected {@link jspectrumanalyzer.ui.WaterfallPalette}
     * to both the rolling waterfall and the persistent-display heatmap. Cheap
     * - the palette LUT is pre-baked at construction; the controllers just
     * swap the reference.
     */
    private void applyWaterfallTheme() {
        jspectrumanalyzer.ui.ColorPalette palette =
                settings.getWaterfallTheme().getValue().create();
        waterfall.setPalette(palette);
        persistent.setPalette(palette);
    }

    public void show(Stage stage) {
        TabPane tabs = new TabPane();
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        // Wi-Fi UI is now a separate window opened from the chart toolbar,
        // not a tab. Keeps the spectrum + waterfall visible while the user
        // looks at the AP list and can still change Scan / Display / etc.
        tabs.getTabs().addAll(
                new Tab("Scan", new ScanTab(settings, sdrController)),
                new Tab("Params", new ParamsTab(settings)),
                new Tab("Display", new DisplayTab(settings)),
                new Tab("Recording", new RecordingTab(settings)));

        Pane waterfallHolder = new Pane(waterfall);
        waterfallHolder.setMinHeight(80);
        waterfall.widthProperty().bind(waterfallHolder.widthProperty());
        waterfall.heightProperty().bind(waterfallHolder.heightProperty());

        // Layered chart pane: the JFreeChart ChartViewer at the bottom plus
        // two transparent canvases on top - one for the allocation overlay
        // (coloured bands + labels) and one for the live drag-zoom rectangle.
        // Both canvases ignore mouse events (setMouseTransparent in their
        // constructors) so the ZoomController's filters on the ChartViewer
        // still receive every press / drag / release / scroll.
        // ChartViewer needs an explicit MAX_VALUE size: StackPane honours
        // children's maxSize and Control's default max == pref, which would
        // otherwise pin the chart at its (small) preferred size and leave the
        // rest of the pane blank - waterfall would still draw because it's
        // in its own Pane below.
        spectrumChart.getViewer().setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);
        LegendOverlay legend = new LegendOverlay(settings);
        // Z-order (bottom to top): chart -> AP markers -> allocation bands ->
        // drag rectangle -> legend. AP markers sit below the allocation
        // overlay so the channel-grid labels stay legible across them, and
        // both sit below the drag overlay so the active drag-zoom rectangle
        // is always the topmost feedback element.
        StackPane chartLayer = new StackPane(
                spectrumChart.getViewer(), apMarkerOverlay,
                allocationOverlay, dragOverlay, legend);
        chartLayer.setMinSize(0, 0);
        allocationOverlay.widthProperty().bind(chartLayer.widthProperty());
        allocationOverlay.heightProperty().bind(chartLayer.heightProperty());
        apMarkerOverlay.widthProperty().bind(chartLayer.widthProperty());
        apMarkerOverlay.heightProperty().bind(chartLayer.heightProperty());
        dragOverlay.widthProperty().bind(chartLayer.widthProperty());
        dragOverlay.heightProperty().bind(chartLayer.heightProperty());
        // Pin the legend to the top-right corner with a small inset so it
        // sits cleanly inside the plot frame; pickOnBounds=false on the
        // overlay itself keeps drag-zoom on the empty area working.
        StackPane.setAlignment(legend, Pos.TOP_RIGHT);
        StackPane.setMargin(legend, new javafx.geometry.Insets(8, 12, 0, 0));

        ChartZoomController zoom = new ChartZoomController(
                spectrumChart.getViewer(), spectrumChart, settings, sdrController,
                new FrequencyRangeValidator(SettingsStore.FREQ_MIN_MHZ, SettingsStore.FREQ_MAX_MHZ),
                dragOverlay);

        // Mouse-move event filter on the chart layer feeds the AP marker
        // overlay's tooltip. We use a filter so the chart's drag-zoom logic
        // still receives the same events first, and we translate to the
        // canvas's own coordinate space (identical here since the canvas is
        // bound to the chart layer's bounds, but the explicit translation
        // keeps the overlay decoupled from any future pane padding changes).
        chartLayer.addEventFilter(javafx.scene.input.MouseEvent.MOUSE_MOVED, ev -> {
            javafx.geometry.Point2D p = apMarkerOverlay.sceneToLocal(ev.getSceneX(), ev.getSceneY());
            apMarkerOverlay.setHoveredPoint(p.getX(), p.getY());
        });
        chartLayer.addEventFilter(javafx.scene.input.MouseEvent.MOUSE_DRAGGED, ev -> {
            javafx.geometry.Point2D p = apMarkerOverlay.sceneToLocal(ev.getSceneX(), ev.getSceneY());
            apMarkerOverlay.setHoveredPoint(p.getX(), p.getY());
        });
        chartLayer.addEventFilter(javafx.scene.input.MouseEvent.MOUSE_EXITED, ev ->
                apMarkerOverlay.setHoveredPoint(null, null));

        // Vertical SplitPane lets the user drag the divider between chart and
        // waterfall. The toolbar sits above the SplitPane in a VBox so it stays
        // pinned regardless of how the user resizes the panes.
        SplitPane chartStack = new SplitPane(chartLayer, waterfallHolder);
        chartStack.setOrientation(Orientation.VERTICAL);
        chartStack.setDividerPositions(0.60);
        SplitPane.setResizableWithParent(chartLayer, Boolean.TRUE);
        SplitPane.setResizableWithParent(waterfallHolder, Boolean.TRUE);

        ChartToolbar toolbar = new ChartToolbar(settings, engine, () -> {
            waterfall.clearHistory();
            persistent.getPersistentDisplay().reset();
        }, () -> openWifiWindow(stage));

        VBox chartPane = new VBox(toolbar, chartStack);
        VBox.setVgrow(chartStack, Priority.ALWAYS);

        settings.isWaterfallVisible().addListener(() -> Platform.runLater(() -> {
            boolean visible = settings.isWaterfallVisible().getValue();
            waterfallHolder.setVisible(visible);
            waterfallHolder.setManaged(visible);
            if (visible && !chartStack.getItems().contains(waterfallHolder)) {
                chartStack.getItems().add(waterfallHolder);
                chartStack.setDividerPositions(0.60);
            } else if (!visible) {
                chartStack.getItems().remove(waterfallHolder);
            }
        }));

        spectrumChart.setDataAreaListener(this::onChartDataAreaChanged);

        SplitPane split = new SplitPane(tabs, chartPane);
        split.setOrientation(Orientation.HORIZONTAL);
        split.setDividerPositions(0.28);

        BorderPane root = new BorderPane();
        root.setCenter(split);
        root.setBottom(buildStatusBar());

        Scene scene = new Scene(root, 1280, 800);
        scene.getStylesheets().add(
                getClass().getResource("/jspectrumanalyzer/fx/theme/dark.css").toExternalForm());
        installShortcuts(scene, toolbar, tabs, zoom);

        stage.setTitle("HackRF Spectrum Analyzer");
        stage.setScene(scene);
        stage.setOnCloseRequest(e -> {
            engine.shutdown();
            Platform.exit();
        });
        stage.show();
    }

    /**
     * Status bar with vertical separators between sections, bold peak readout
     * and a flexible spacer that pushes the totals to the right edge.
     */
    private HBox buildStatusBar() {
        peakLabel.getStyleClass().add("status-peak");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox bar = new HBox(8,
                hardwareStatus, separator(),
                peakLabel,
                spacer,
                separator(),
                totalPowerLabel);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.getStyleClass().add("status-bar");
        return bar;
    }

    private static Region separator() {
        Region r = new Region();
        r.getStyleClass().add("status-separator");
        r.setMinWidth(1);
        r.setPrefWidth(1);
        r.setMaxHeight(18);
        return r;
    }

    /**
     * Scene-wide accelerators. The single-key bindings (Space / C / W / F5 /
     * Esc) are skipped while a text input is focused so typing frequency or
     * RBW values isn't intercepted; the modifier-based bindings
     * (Ctrl+1..5) survive even in text fields because they cannot be
     * confused with regular typing.
     */
    private void installShortcuts(Scene scene, ChartToolbar toolbar,
                                  TabPane tabs, ChartZoomController zoom) {
        scene.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            // Modifier shortcuts first so Ctrl+1..4 (and Ctrl+I) work even
            // while the user is editing a text field (matches every
            // browser/IDE).
            if (e.isControlDown() && !e.isAltDown() && !e.isMetaDown() && !e.isShiftDown()) {
                if (e.getCode() == KeyCode.I) {
                    openWifiWindow((Stage) scene.getWindow());
                    e.consume();
                    return;
                }
                int tabIdx = digitIndex(e.getCode());
                if (tabIdx >= 0 && tabIdx < tabs.getTabs().size()) {
                    tabs.getSelectionModel().select(tabIdx);
                    e.consume();
                    return;
                }
            }

            Node focused = scene.getFocusOwner();
            if (focused instanceof TextInputControl) return;
            if (e.isControlDown() || e.isAltDown() || e.isMetaDown() || e.isShiftDown()) return;

            if (e.getCode() == KeyCode.SPACE) {
                toolbar.toggleFreeze();
                e.consume();
            } else if (e.getCode() == KeyCode.C) {
                toolbar.clearTraces();
                e.consume();
            } else if (e.getCode() == KeyCode.W) {
                toolbar.toggleWaterfall();
                e.consume();
            } else if (e.getCode() == KeyCode.F5) {
                sdrController.toggleRunning();
                e.consume();
            } else if (e.getCode() == KeyCode.ESCAPE) {
                zoom.resetZoom();
                e.consume();
            }
        });
    }

    /**
     * Map {@code KeyCode.DIGIT1..DIGIT4} (and their numpad twins) to a
     * zero-based tab index. Returns {@code -1} for any other key so the
     * accelerator filter above can fall through to its default branches.
     * Ctrl+5 used to point at the old Wi-Fi tab; Wi-Fi has its own window
     * now reachable via the toolbar button (or Ctrl+I, see installShortcuts).
     */
    private static int digitIndex(KeyCode code) {
        switch (code) {
            case DIGIT1: case NUMPAD1: return 0;
            case DIGIT2: case NUMPAD2: return 1;
            case DIGIT3: case NUMPAD3: return 2;
            case DIGIT4: case NUMPAD4: return 3;
            default: return -1;
        }
    }

    /**
     * Lazy-create the {@link WifiWindow} on first call, then show or focus it.
     * Owned by the main stage so closing the main window also closes the
     * Wi-Fi window automatically.
     */
    private void openWifiWindow(Stage owner) {
        if (wifiWindow == null) {
            wifiWindow = new WifiWindow(settings, sdrController, wifiScanService, occupancyService,
                    interferenceService, densityService, interfererClassifier,
                    monitorCapture, beaconStore);
        }
        wifiWindow.show(owner);
    }

    private static HackRFDeviceInfo safeGetOpenedInfo() {
        try {
            return HackRFSweepNativeBridge.getOpenedInfo();
        } catch (Throwable t) {
            return null;
        }
    }

    private void onChartDataAreaChanged(Rectangle2D area) {
        int xOffset = (int) Math.round(area.getX());
        int width = (int) Math.round(area.getWidth());
        waterfall.setDrawingOffsets(xOffset, width);
        persistent.onDataAreaChanged(area);
        allocationOverlay.onDataAreaChanged(area);
        apMarkerOverlay.onDataAreaChanged(area);
    }

    /**
     * Runs on the engine's processing thread. Heavy drawing into AWT buffers happens
     * here; only the cheap dataset swap is forwarded to the FX thread.
     */
    private void onNewFrameOffFx(SpectrumFrame frame) {
        if (settings.isWaterfallVisible().getValue()) {
            int speed = settings.getWaterfallSpeed().getValue();
            int divisor = Math.max(1, 11 - speed);
            if ((persistentFrameCounter % divisor) == 0) {
                waterfall.addNewData(frame.dataset);
            }
        }
        if (settings.isPersistentDisplayVisible().getValue()) {
            persistent.accumulate(frame, persistentFrameCounter % 2 == 0);
        }
        persistentFrameCounter++;

        long now = System.currentTimeMillis();
        if (now - lastChartUpdate < CHART_REFRESH_INTERVAL_MS) return;

        // Always replace the pending frame with the latest. The CAS on
        // refreshScheduled guarantees only one runLater is in flight at a time,
        // so a slow FX thread cannot accumulate a backlog.
        pendingFrame.set(frame);
        if (refreshScheduled.compareAndSet(false, true)) {
            lastChartUpdate = now;
            Platform.runLater(this::drainPendingFrame);
        }
    }

    private void drainPendingFrame() {
        try {
            SpectrumFrame frame = pendingFrame.getAndSet(null);
            if (frame == null) return;
            spectrumChart.updateSeries(frame);
            waterfall.requestPaint();
            if (frame.showPeaks) {
                peakLabel.setText(String.format(
                        "Peak %.1f dBm @ %.2f MHz",
                        frame.peakAmplitudeDbm, frame.peakFrequencyMHz).replace(',', '.'));
                totalPowerLabel.setText(String.format(
                        "Total %.1f dBm  \u2248 %s \u00B5W/m\u00B2",
                        frame.totalPowerDbm, frame.powerFluxDensity).replace(',', '.'));
            }
        } finally {
            refreshScheduled.set(false);
        }
    }
}
