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
                        FxControls.labeled("Peak fall rate (s)",
                                FxControls.withTooltip(
                                        FxControls.intSpinner(settings.getPeakFallRate(), 0, 60, 1),
                                        "How many seconds the falling green peak trace takes to drop "
                                        + "by 'Peak fall threshold' dB. Larger = peaks linger longer.")),
                        FxControls.labeled("Peak fall threshold (dB)",
                                FxControls.withTooltip(
                                        FxControls.intSpinner(settings.getPeakFallTrs(), 0, 20, 1),
                                        "How many dB the EMA peak has to fall below the real peak "
                                        + "before the trace switches back to following the real value.")),
                        FxControls.labeled("Peak hold time (s)",
                                FxControls.withTooltip(
                                        FxControls.intSpinner(settings.getPeakHoldTime(), 0, 3600, 1),
                                        "Per-bin hold time for the green peak trace before it starts "
                                        + "decaying. 0 = no extra hold."))),
                FxControls.section("Max hold",
                        FxControls.labeled("Fade after (s, 0 = forever)",
                                FxControls.withTooltip(
                                        FxControls.intSpinner(settings.getMaxHoldDecaySeconds(), 0, 3600, 1),
                                        "Each frequency bin's max-hold value resets to the live "
                                        + "spectrum after this many seconds without being beaten. "
                                        + "Set 0 to keep the legacy behaviour (max hold accumulates "
                                        + "forever until you clear it manually)."))),
                FxControls.section("Average",
                        FxControls.labeled("Iterations",
                                FxControls.withTooltip(
                                        FxControls.intSpinner(settings.getAvgIterations(), 1, 200, 1),
                                        "Number of sweeps averaged for the orange average trace. "
                                        + "Higher = smoother but slower to react.")),
                        FxControls.labeled("Offset (dB)",
                                FxControls.withTooltip(
                                        FxControls.slider(settings.getAvgOffset(), -40, 40),
                                        "Vertical offset added to the average trace, useful for "
                                        + "separating it visually from the realtime spectrum."))),
                FxControls.section("Calibration",
                        FxControls.labeled("Amplitude offset (dB)",
                                FxControls.withTooltip(
                                        FxControls.slider(settings.getAmplitudeOffset(), -40, 40),
                                        "Calibration offset added to all amplitude values before "
                                        + "they reach the chart. Use it to align readings against a "
                                        + "known reference signal generator.")),
                        FxControls.labeled("Power flux cal",
                                FxControls.withTooltip(
                                        FxControls.slider(settings.getPowerFluxCal(), 0, 100),
                                        "Reference level (dBm/m\u00B2) for the power-flux readout in "
                                        + "the status bar. Tune to match your antenna's published "
                                        + "effective area / gain.")),
                        FxControls.labeled("Frequency shift (MHz)",
                                FxControls.withTooltip(
                                        FxControls.intSpinner(settings.getFreqShift(), -10000, 10000, 1),
                                        "Display offset added to all frequency labels. Use it when "
                                        + "feeding the HackRF through a downconverter (e.g. SAT TV "
                                        + "LNB) so the chart shows the actual RF frequency."))),
                FxControls.section("Chart visibility",
                        FxControls.withTooltip(
                                FxControls.checkBox("Realtime spectrum", settings.isChartsRealtimeVisible()),
                                "Show the unsmoothed live spectrum (cyan)."),
                        FxControls.withTooltip(
                                FxControls.checkBox("Average", settings.isChartsAverageVisible()),
                                "Show the running-average trace (orange)."),
                        FxControls.withTooltip(
                                FxControls.checkBox("Peaks", settings.isChartsPeaksVisible()),
                                "Show the EMA peak trace (green)."),
                        FxControls.withTooltip(
                                FxControls.checkBox("Max hold", settings.isChartsMaxHoldVisible()),
                                "Show the max-hold trace (red). Combine with 'Fade after' above to "
                                + "make it auto-decay instead of accumulating forever."),
                        FxControls.withTooltip(
                                FxControls.checkBox("Peak marker", settings.isPeakMarkerVisible()),
                                "Annotate the strongest realtime bin with its frequency and dBm."),
                        FxControls.withTooltip(
                                FxControls.checkBox("Max-hold marker", settings.isMaxHoldMarkerVisible()),
                                "Annotate the strongest max-hold bin with its frequency and dBm."),
                        FxControls.withTooltip(
                                FxControls.checkBox("Datestamp overlay", settings.isDatestampVisible()),
                                "Burn the current timestamp into the chart - handy for screenshots "
                                + "and recordings.")),
                FxControls.section("Processing",
                        FxControls.withTooltip(
                                FxControls.checkBox("Spur removal", settings.isSpurRemoval()),
                                "Detect narrow internal spurs (HackRF clock harmonics, USB noise) "
                                + "and replace them with the local noise floor estimate."),
                        FxControls.withTooltip(
                                FxControls.checkBox("Filter spectrum", settings.isFilterSpectrum()),
                                "Apply a light smoothing filter across adjacent bins to reduce "
                                + "single-bin variance."))
        );
        setContent(content);
    }
}
