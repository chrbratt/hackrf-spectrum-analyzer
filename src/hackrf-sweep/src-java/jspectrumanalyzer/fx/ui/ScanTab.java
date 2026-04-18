package jspectrumanalyzer.fx.ui;

import java.io.FileNotFoundException;

import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import jspectrumanalyzer.core.FrequencyPresets;
import jspectrumanalyzer.core.FrequencyRange;
import jspectrumanalyzer.fx.frequency.FrequencyRangeSelector;
import jspectrumanalyzer.fx.frequency.FrequencyRangeValidator;
import jspectrumanalyzer.fx.model.FxModelBinder;
import jspectrumanalyzer.fx.model.SettingsStore;
import jspectrumanalyzer.fx.util.FxControls;

/**
 * Scan tab: frequency range, FFT bin, samples, LNA / VGA gain, antenna power / LNA.
 * Replaces the left side of the legacy {@code HackRFSweepSettingsUI} main tab.
 */
public final class ScanTab extends ScrollPane {

    private static final int[] RBW_PRESETS_KHZ = {10, 50, 100, 250, 500, 1000};

    private final SettingsStore settings;
    private final FrequencyRangeValidator validator =
            new FrequencyRangeValidator(SettingsStore.FREQ_MIN_MHZ, SettingsStore.FREQ_MAX_MHZ);

    public ScanTab(SettingsStore settings) {
        this.settings = settings;
        setFitToWidth(true);
        setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);

        FrequencyRangeSelector rangeSelector = new FrequencyRangeSelector(validator,
                settings.getFrequency().getValue());
        FxModelBinder.bindObject(rangeSelector.rangeProperty(), settings.getFrequency());
        try {
            rangeSelector.setPresets(new FrequencyPresets().getList());
        } catch (FileNotFoundException ignored) {
        }

        VBox content = new VBox(12);
        content.setPadding(new Insets(12));
        content.getChildren().addAll(
                FxControls.section("Device", new DeviceSection(settings)),
                FxControls.section("Frequency", rangeSelector, buildPanBar()),
                FxControls.section("Resolution",
                        labeled("RBW [kHz]",
                                FxControls.withTooltip(
                                        FxControls.intSpinner(settings.getFFTBinHz(), 1, 5000, 5),
                                        "Resolution Bandwidth: width of one FFT bin in kHz. "
                                        + "Smaller = sharper frequency resolution but slower sweeps "
                                        + "and more noise per bin. 50 kHz is a good default.")),
                        buildRbwPresets(),
                        labeled("Samples",
                                FxControls.withTooltip(
                                        FxControls.intSpinner(settings.getSamples(), 1024, 131072, 1024),
                                        "FFT size per tuning step. More samples = better noise floor "
                                        + "and finer effective bandwidth, at the cost of CPU."))),
                FxControls.section("Gain",
                        labeled("LNA gain (dB, 8 dB steps)",
                                FxControls.withTooltip(
                                        FxControls.slider(settings.getGainLNA(), 0, 40),
                                        "Front-end Low-Noise Amplifier gain. Hardware only accepts "
                                        + "0, 8, 16, 24, 32, 40 dB. Increase for weak signals; reduce "
                                        + "if strong signals look clipped or you see ghost peaks.")),
                        labeled("VGA gain (dB, 2 dB steps)",
                                FxControls.withTooltip(
                                        FxControls.slider(settings.getGainVGA(), 0, 62),
                                        "Baseband Variable-Gain Amplifier. Hardware accepts steps of "
                                        + "2 dB from 0 to 62. Adjusts the post-mixer level; raise it to "
                                        + "make the noise floor visible, lower it to reduce ADC clipping.")),
                        FxControls.withTooltip(
                                FxControls.checkBox("Antenna power (+5 V bias)", settings.getAntennaPowerEnable()),
                                "Enable +5 V bias-tee on the antenna port to power active antennas / "
                                + "LNAs. Leave OFF unless your antenna actually needs it: a short "
                                + "would damage the HackRF."),
                        FxControls.withTooltip(
                                FxControls.checkBox("Internal RF amp (LNA)", settings.getAntennaLNA()),
                                "Enable the HackRF's internal +14 dB RF amplifier ahead of the mixer. "
                                + "Improves sensitivity for weak signals but can overload on strong ones."))
        );
        setContent(content);
    }

    private HBox buildPanBar() {
        Button panLeft = new Button("\u2190 pan");
        panLeft.setOnAction(e -> panFrequency(-0.9));
        Button panRight = new Button("pan \u2192");
        panRight.setOnAction(e -> panFrequency(0.9));
        HBox panBar = new HBox(6, panLeft, panRight);
        HBox.setHgrow(panLeft, Priority.ALWAYS);
        HBox.setHgrow(panRight, Priority.ALWAYS);
        panLeft.setMaxWidth(Double.MAX_VALUE);
        panRight.setMaxWidth(Double.MAX_VALUE);

        Runnable updateTips = () -> {
            FrequencyRange cur = settings.getFrequency().getValue();
            int span = cur.getEndMHz() - cur.getStartMHz();
            int delta = Math.max(1, (int) Math.round(span * 0.9));
            FrequencyRange left = validator.coerce(cur.getStartMHz() - delta, cur.getEndMHz() - delta);
            FrequencyRange right = validator.coerce(cur.getStartMHz() + delta, cur.getEndMHz() + delta);
            FxControls.withTooltip(panLeft, String.format(
                    "Shift the range down by ~%d MHz (90%% of current span). "
                    + "Next: %d \u2013 %d MHz.",
                    delta, left.getStartMHz(), left.getEndMHz()));
            FxControls.withTooltip(panRight, String.format(
                    "Shift the range up by ~%d MHz (90%% of current span). "
                    + "Next: %d \u2013 %d MHz.",
                    delta, right.getStartMHz(), right.getEndMHz()));
        };
        updateTips.run();
        settings.getFrequency().addListener(() ->
                javafx.application.Platform.runLater(updateTips));
        return panBar;
    }

    private FlowPane buildRbwPresets() {
        FlowPane chips = new FlowPane(4, 4);
        chips.getChildren().add(presetLabel("Presets:"));
        for (int khz : RBW_PRESETS_KHZ) {
            Button chip = new Button(khz + " kHz");
            chip.getStyleClass().add("preset-chip");
            FxControls.withTooltip(chip, "Set RBW to " + khz + " kHz.");
            chip.setOnAction(e -> settings.getFFTBinHz().setValue(khz));
            chips.getChildren().add(chip);
        }
        return chips;
    }

    private static Label presetLabel(String text) {
        Label l = new Label(text);
        l.getStyleClass().add("preset-caption");
        return l;
    }

    private void panFrequency(double fraction) {
        FrequencyRange cur = settings.getFrequency().getValue();
        int span = cur.getEndMHz() - cur.getStartMHz();
        int delta = (int) Math.round(span * fraction);
        int newStart = cur.getStartMHz() + delta;
        int newEnd = cur.getEndMHz() + delta;
        settings.getFrequency().setValue(validator.coerce(newStart, newEnd));
    }

    private static VBox labeled(String caption, Node control) {
        Label label = new Label(caption);
        return new VBox(2, label, control);
    }
}
