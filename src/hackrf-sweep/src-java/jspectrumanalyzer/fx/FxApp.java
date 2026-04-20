package jspectrumanalyzer.fx;

import javafx.application.Application;
import javafx.stage.Stage;
import jspectrumanalyzer.fx.engine.SdrController;
import jspectrumanalyzer.fx.engine.SpectrumEngine;
import jspectrumanalyzer.fx.engine.SpectrumRecorder;
import jspectrumanalyzer.fx.model.SettingsStore;
import jspectrumanalyzer.util.HealthMonitor;
import jspectrumanalyzer.wifi.ChannelInterferenceService;
import jspectrumanalyzer.wifi.ChannelOccupancyService;
import jspectrumanalyzer.wifi.DensityHistogramService;
import jspectrumanalyzer.wifi.InterfererClassifier;
import jspectrumanalyzer.wifi.WifiScanService;
import jspectrumanalyzer.wifi.WifiScannerFactory;
import jspectrumanalyzer.wifi.capture.MonitorCaptureFactory;
import jspectrumanalyzer.wifi.capture.MonitorModeCapture;

/**
 * JavaFX entry point for the modernized HackRF Spectrum Analyzer UI.
 * <p>
 * The legacy Swing UI is still available via the old Ant build as a fallback during
 * migration; the Gradle build only ships this JavaFX application.
 */
public final class FxApp extends Application {

    private SettingsStore settings;
    private SpectrumEngine engine;
    /**
     * Single point of intent for "tell the SDR what to do". Created
     * here so every UI component that wants to retune / start / stop
     * receives the same instance via the {@link MainWindow}
     * constructor - replaces the legacy pattern of writing directly
     * to {@link SettingsStore} mutators from arbitrary widgets.
     */
    private SdrController sdrController;
    @SuppressWarnings("unused") // kept alive by listener registration on engine + settings
    private SpectrumRecorder recorder;
    private WifiScanService wifiScanService;
    @SuppressWarnings("unused") // kept alive by engine listener registration
    private ChannelOccupancyService occupancyService;
    @SuppressWarnings("unused") // kept alive by Wi-Fi scan listener registration
    private ChannelInterferenceService interferenceService;
    @SuppressWarnings("unused") // kept alive by engine listener registration
    private DensityHistogramService densityService;
    @SuppressWarnings("unused") // kept alive by engine listener registration
    private InterfererClassifier interfererClassifier;
    /**
     * Phase-2 capture backend. Resolved once at startup via
     * {@link MonitorCaptureFactory} - returns a {@code Pcap4jMonitorCapture}
     * when both pcap4j classes and Npcap are reachable, otherwise a
     * no-op stub. Held here so {@link #stop()} can release the handle on
     * shutdown even if the Wi-Fi window never opened.
     */
    private MonitorModeCapture monitorCapture;
    /**
     * Periodic console health probe. Reports heap, GC, FX-thread
     * latency, sweep fps, processing-queue depth and dropped-sample
     * counters every {@link HealthMonitor#DEFAULT_INTERVAL_SEC}s, with
     * WARN escalation when thresholds are crossed. Disable via
     * {@code -Dhealth.disable=true}.
     */
    private HealthMonitor healthMonitor;

    public static void main(String[] args) {
        launch(FxApp.class, args);
    }

    @Override
    public void init() {
        settings = new SettingsStore();
        engine = new SpectrumEngine(settings);
        sdrController = new SdrController(settings);
        recorder = new SpectrumRecorder(settings, engine);
        // Wi-Fi scan service is created here (not in MainWindow) so its
        // lifecycle is tied to the JavaFX app, not to a particular window.
        // The factory picks the no-op impl on non-Windows so this is free
        // on every other platform.
        wifiScanService = new WifiScanService(WifiScannerFactory.create());
        // Per-channel occupancy lives at the app level for the same reason:
        // it subscribes to the engine's frame stream once and exposes a
        // stable handle to whichever windows want to render it.
        occupancyService = new ChannelOccupancyService(engine);
        // Co/adjacent-channel tally is derived purely from the Wi-Fi scan
        // snapshot stream, so it lives next to the scan service in the
        // app-scope wiring and uses the same listener-as-keepalive pattern.
        interferenceService = new ChannelInterferenceService(wifiScanService);
        // Density histogram lives at the app level so the accumulation
        // survives opening/closing the Wi-Fi window. Subscribes to the
        // engine's frame stream once and exposes a snapshot the
        // density chart view can render on demand.
        densityService = new DensityHistogramService(engine);
        // Heuristic non-Wi-Fi interferer detector. Lives at the app
        // level so its EMA accumulators survive the Wi-Fi window
        // open/close cycle - a microwave that started running while
        // the window was closed is still in the table when it opens.
        interfererClassifier = new InterfererClassifier(engine, wifiScanService);
        // Bind the Wi-Fi adapter selection to the scanner so picking an
        // adapter in the Wi-Fi window (or restoring a saved value at
        // startup) takes effect on the next poll tick. Wired in init()
        // rather than in the Wi-Fi window so the scanner respects the
        // selection even on the very first poll, before any window opens.
        wifiScanService.setSelectedAdapter(settings.getSelectedWifiAdapterGuid().getValue());
        settings.getSelectedWifiAdapterGuid().addListener(() ->
                wifiScanService.setSelectedAdapter(
                        settings.getSelectedWifiAdapterGuid().getValue()));
        // Phase-2 monitor-mode backend. Probed once at startup; the
        // factory falls back to a no-op when Npcap isn't installed so
        // the rest of the UI never has to null-check.
        monitorCapture = MonitorCaptureFactory.create();
    }

    @Override
    public void start(Stage primaryStage) {
        MainWindow window = new MainWindow(settings, engine, sdrController, wifiScanService,
                occupancyService, interferenceService, densityService,
                interfererClassifier, monitorCapture);
        window.show(primaryStage);
        engine.start();
        wifiScanService.start();
        // Start health probe AFTER the engine + FX scene are alive so
        // the first tick captures real numbers, not boot-time zeros.
        healthMonitor = new HealthMonitor(engine);
        healthMonitor.start();
    }

    @Override
    public void stop() {
        if (healthMonitor != null) healthMonitor.stop();
        if (monitorCapture != null) monitorCapture.stop();
        if (wifiScanService != null) wifiScanService.stop();
        if (engine != null) engine.shutdown();
    }
}
