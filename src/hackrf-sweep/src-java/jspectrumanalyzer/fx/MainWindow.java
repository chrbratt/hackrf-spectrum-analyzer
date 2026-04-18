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
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import jspectrumanalyzer.core.HackRFSettings;
import jspectrumanalyzer.fx.chart.PersistentDisplayController;
import jspectrumanalyzer.fx.chart.SpectrumChart;
import jspectrumanalyzer.fx.chart.WaterfallCanvas;
import jspectrumanalyzer.fx.engine.SpectrumEngine;
import jspectrumanalyzer.fx.engine.SpectrumFrame;
import jspectrumanalyzer.fx.model.SettingsStore;
import jspectrumanalyzer.fx.ui.ChartToolbar;
import jspectrumanalyzer.fx.ui.DisplayTab;
import jspectrumanalyzer.fx.ui.ParamsTab;
import jspectrumanalyzer.fx.ui.RecordingTab;
import jspectrumanalyzer.fx.ui.ScanTab;
import jspectrumanalyzer.nativebridge.HackRFDeviceInfo;
import jspectrumanalyzer.nativebridge.HackRFSweepNativeBridge;

/**
 * Main JavaFX window wiring together settings, chart, waterfall, persistent display,
 * and the status bar. Owns the frame-consumer callbacks that glue the headless
 * {@link SpectrumEngine} to the FX UI.
 */
public final class MainWindow {

    private static final long CHART_REFRESH_INTERVAL_MS = 33;

    private final SettingsStore settings;
    private final SpectrumEngine engine;

    private final SpectrumChart spectrumChart;
    private final WaterfallCanvas waterfall;
    private final PersistentDisplayController persistent;

    private final Label hardwareStatus = new Label("Stopped");
    private final Label peakLabel = new Label("");
    private final Label totalPowerLabel = new Label("");

    private volatile long lastChartUpdate = 0;
    private int persistentFrameCounter = 0;

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

    public MainWindow(SettingsStore settings, SpectrumEngine engine) {
        this.settings = settings;
        this.engine = engine;
        this.spectrumChart = new SpectrumChart(settings);
        this.waterfall = new WaterfallCanvas();
        this.persistent = new PersistentDisplayController(settings, spectrumChart);

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

        engine.addFrameConsumer(this::onNewFrameOffFx);
    }

    public void show(Stage stage) {
        TabPane tabs = new TabPane();
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        tabs.getTabs().addAll(
                new Tab("Scan", new ScanTab(settings)),
                new Tab("Params", new ParamsTab(settings)),
                new Tab("Display", new DisplayTab(settings)),
                new Tab("Recording", new RecordingTab(settings)));

        Pane waterfallHolder = new Pane(waterfall);
        waterfallHolder.setMinHeight(80);
        waterfall.widthProperty().bind(waterfallHolder.widthProperty());
        waterfall.heightProperty().bind(waterfallHolder.heightProperty());

        // Vertical SplitPane lets the user drag the divider between chart and
        // waterfall. The toolbar sits above the SplitPane in a VBox so it stays
        // pinned regardless of how the user resizes the panes.
        SplitPane chartStack = new SplitPane(spectrumChart.getViewer(), waterfallHolder);
        chartStack.setOrientation(Orientation.VERTICAL);
        chartStack.setDividerPositions(0.60);
        SplitPane.setResizableWithParent(spectrumChart.getViewer(), Boolean.TRUE);
        SplitPane.setResizableWithParent(waterfallHolder, Boolean.TRUE);

        ChartToolbar toolbar = new ChartToolbar(settings, engine, () -> {
            waterfall.clearHistory();
            persistent.getPersistentDisplay().reset();
        });

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
        installShortcuts(scene, toolbar);

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
     * Scene-wide accelerators. Skipped while a text input is focused so typing
     * frequency / RBW values isn't intercepted.
     */
    private void installShortcuts(Scene scene, ChartToolbar toolbar) {
        scene.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
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
            }
        });
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
