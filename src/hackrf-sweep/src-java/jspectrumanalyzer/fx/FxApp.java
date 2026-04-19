package jspectrumanalyzer.fx;

import javafx.application.Application;
import javafx.stage.Stage;
import jspectrumanalyzer.fx.engine.SpectrumEngine;
import jspectrumanalyzer.fx.engine.SpectrumRecorder;
import jspectrumanalyzer.fx.model.SettingsStore;
import jspectrumanalyzer.wifi.WifiScanService;
import jspectrumanalyzer.wifi.WifiScannerFactory;

/**
 * JavaFX entry point for the modernized HackRF Spectrum Analyzer UI.
 * <p>
 * The legacy Swing UI is still available via the old Ant build as a fallback during
 * migration; the Gradle build only ships this JavaFX application.
 */
public final class FxApp extends Application {

    private SettingsStore settings;
    private SpectrumEngine engine;
    @SuppressWarnings("unused") // kept alive by listener registration on engine + settings
    private SpectrumRecorder recorder;
    private WifiScanService wifiScanService;

    public static void main(String[] args) {
        launch(FxApp.class, args);
    }

    @Override
    public void init() {
        settings = new SettingsStore();
        engine = new SpectrumEngine(settings);
        recorder = new SpectrumRecorder(settings, engine);
        // Wi-Fi scan service is created here (not in MainWindow) so its
        // lifecycle is tied to the JavaFX app, not to a particular window.
        // The factory picks the no-op impl on non-Windows so this is free
        // on every other platform.
        wifiScanService = new WifiScanService(WifiScannerFactory.create());
    }

    @Override
    public void start(Stage primaryStage) {
        MainWindow window = new MainWindow(settings, engine, wifiScanService);
        window.show(primaryStage);
        engine.start();
        wifiScanService.start();
    }

    @Override
    public void stop() {
        if (wifiScanService != null) wifiScanService.stop();
        if (engine != null) engine.shutdown();
    }
}
