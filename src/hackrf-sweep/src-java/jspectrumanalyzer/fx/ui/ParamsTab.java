package jspectrumanalyzer.fx.ui;

import javafx.geometry.Insets;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.VBox;
import jspectrumanalyzer.fx.model.SettingsStore;
import jspectrumanalyzer.fx.util.FxControls;

/**
 * Params tab: peak/average/max-hold parameters, amplitude offset, power flux cal,
 * spur filter, frequency shift, chart visibility toggles.
 * Replaces the middle Swing settings tab.
 */
public final class ParamsTab extends ScrollPane {

    public ParamsTab(SettingsStore settings) {
        setFitToWidth(true);
        setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);

        VBox content = new VBox(12);
        content.setPadding(new Insets(12));
        content.getChildren().addAll(
                FxControls.section("Peaks",
                        labeled("Peak fall rate (s)",
                                FxControls.intSpinner(settings.getPeakFallRate(), 0, 60, 1)),
                        labeled("Peak fall threshold (dB)",
                                FxControls.intSpinner(settings.getPeakFallTrs(), 0, 20, 1)),
                        labeled("Peak hold time (s)",
                                FxControls.intSpinner(settings.getPeakHoldTime(), 0, 3600, 1))),
                FxControls.section("Average",
                        labeled("Iterations",
                                FxControls.intSpinner(settings.getAvgIterations(), 1, 200, 1)),
                        labeled("Offset (dB)",
                                FxControls.slider(settings.getAvgOffset(), -40, 40))),
                FxControls.section("Calibration",
                        labeled("Amplitude offset (dB)",
                                FxControls.slider(settings.getAmplitudeOffset(), -40, 40)),
                        labeled("Power flux cal",
                                FxControls.slider(settings.getPowerFluxCal(), 0, 100)),
                        labeled("Frequency shift (MHz)",
                                FxControls.intSpinner(settings.getFreqShift(), -10000, 10000, 1))),
                FxControls.section("Chart visibility",
                        FxControls.checkBox("Realtime spectrum", settings.isChartsRealtimeVisible()),
                        FxControls.checkBox("Average", settings.isChartsAverageVisible()),
                        FxControls.checkBox("Peaks", settings.isChartsPeaksVisible()),
                        FxControls.checkBox("Max hold", settings.isChartsMaxHoldVisible()),
                        FxControls.checkBox("Peak marker", settings.isPeakMarkerVisible()),
                        FxControls.checkBox("Max-hold marker", settings.isMaxHoldMarkerVisible()),
                        FxControls.checkBox("Datestamp overlay", settings.isDatestampVisible())),
                FxControls.section("Processing",
                        FxControls.checkBox("Spur removal", settings.isSpurRemoval()),
                        FxControls.checkBox("Filter spectrum", settings.isFilterSpectrum()))
        );
        setContent(content);
    }

    private static VBox labeled(String caption, javafx.scene.Node control) {
        return new VBox(2, new javafx.scene.control.Label(caption), control);
    }
}
