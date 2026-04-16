package jspectrumanalyzer.fx;

import javafx.application.Application;
import javafx.stage.Stage;
import jspectrumanalyzer.fx.engine.SpectrumEngine;
import jspectrumanalyzer.fx.model.SettingsStore;

/**
 * JavaFX entry point for the modernized HackRF Spectrum Analyzer UI.
 * <p>
 * The legacy Swing UI is still available via the old Ant build as a fallback during
 * migration; the Gradle build only ships this JavaFX application.
 */
public final class FxApp extends Application {

    private SettingsStore settings;
    private SpectrumEngine engine;

    public static void main(String[] args) {
        launch(FxApp.class, args);
    }

    @Override
    public void init() {
        settings = new SettingsStore();
        engine = new SpectrumEngine(settings);
    }

    @Override
    public void start(Stage primaryStage) {
        MainWindow window = new MainWindow(settings, engine);
        window.show(primaryStage);
        engine.start();
    }

    @Override
    public void stop() {
        if (engine != null) engine.shutdown();
    }
}
