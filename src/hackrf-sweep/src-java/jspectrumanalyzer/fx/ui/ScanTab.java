package jspectrumanalyzer.fx.ui;

import java.io.FileNotFoundException;

import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import jspectrumanalyzer.core.FrequencyMultiRangePreset;
import jspectrumanalyzer.core.FrequencyPlan;
import jspectrumanalyzer.core.FrequencyPresets;
import jspectrumanalyzer.core.FrequencyRange;
import jspectrumanalyzer.fx.engine.SdrController;
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
    // Powers of two from 4k to 128k - the only values libhackrf actually
    // accepts for the FFT size, and the most common ones users reach for
    // when trading "fast sweep" against "low noise floor / fine bins".
    private static final int[] FFT_PRESETS_SAMPLES = {4096, 8192, 16384, 32768, 65536, 131072};

    private final SettingsStore settings;
    /**
     * Single point of intent for retune / start / stop. Forwarded to
     * the nested {@link DeviceSection} and used directly for slider
     * writes, multi-band selection and the pan buttons - replacing
     * the legacy direct {@link SettingsStore} mutators.
     */
    private final SdrController sdrController;
    private final FrequencyRangeValidator validator =
            new FrequencyRangeValidator(SettingsStore.FREQ_MIN_MHZ, SettingsStore.FREQ_MAX_MHZ);

    public ScanTab(SettingsStore settings, SdrController sdrController) {
        this.settings = settings;
        this.sdrController = sdrController;
        setFitToWidth(true);
        setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);

        FrequencyRangeSelector rangeSelector = new FrequencyRangeSelector(validator,
                settings.getFrequency().getValue());
        // Slider / text-field writes flow through the controller so the
        // engine sees a single intent stream; the read direction
        // (model -> property) is still handled by the binder so the UI
        // mirrors whatever the active source of truth currently is.
        FxModelBinder.bindObject(rangeSelector.rangeProperty(), settings.getFrequency(),
                sdrController::requestRetune);
        try {
            rangeSelector.setPresets(new FrequencyPresets().getList());
        } catch (FileNotFoundException ignored) {
        }

        VBox content = new VBox(12);
        content.setPadding(new Insets(12));
        content.getChildren().addAll(
                FxControls.section("Device", new DeviceSection(settings, sdrController)),
                FxControls.section("Frequency",
                        rangeSelector,
                        buildPanBar(),
                        buildMultiRangeRow()),
                FxControls.section("Resolution",
                        FxControls.labeled("RBW [kHz]",
                                FxControls.withTooltip(
                                        FxControls.intSpinner(settings.getFFTBinHz(), 1, 5000, 5),
                                        "Resolution Bandwidth: width of one FFT bin in kHz. "
                                        + "Smaller = sharper frequency resolution but slower sweeps "
                                        + "and more noise per bin. 50 kHz is a good default.")),
                        buildRbwPresets(),
                        FxControls.labeled("Samples",
                                FxControls.withTooltip(
                                        FxControls.intSpinner(settings.getSamples(), 1024, 131072, 1024),
                                        "FFT size per tuning step. More samples = better noise floor "
                                        + "and finer effective bandwidth, at the cost of CPU.")),
                        buildFftPresets()),
                FxControls.section("Gain",
                        FxControls.labeled("LNA gain (dB, 8 dB steps)",
                                FxControls.withTooltip(
                                        FxControls.slider(settings.getGainLNA(), 0, 40),
                                        "Front-end Low-Noise Amplifier gain. Hardware only accepts "
                                        + "0, 8, 16, 24, 32, 40 dB. Increase for weak signals; reduce "
                                        + "if strong signals look clipped or you see ghost peaks.")),
                        FxControls.labeled("VGA gain (dB, 2 dB steps)",
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

    /**
     * Compact "Multi-band:" + combo row appended at the bottom of the
     * Frequency section. Picking anything other than "Off" enables a
     * {@link FrequencyPlan} that stitches several sub-bands together,
     * removing the dead air between them. Lives inside the Frequency
     * section to avoid spending a whole section header on a single combo.
     */
    private VBox buildMultiRangeRow() {
        Label caption = new Label("Multi-band:");

        // The custom editor lives below the combo and only becomes visible
        // when the user picks the "Custom..." entry. It owns its own
        // FrequencyPlan and pushes it through the SettingsStore on every
        // valid edit; the combo just toggles whether the editor is shown.
        CustomMultiRangeEditor customEditor = new CustomMultiRangeEditor(
                validator,
                sdrController::requestRetunePlan);
        customEditor.setVisible(false);
        customEditor.setManaged(false);

        ComboBox<FrequencyMultiRangePreset> combo = buildMultiRangeCombo(customEditor);
        HBox top = new HBox(6, caption, combo);
        top.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        HBox.setHgrow(combo, Priority.ALWAYS);

        VBox row = new VBox(6, top, customEditor);
        return row;
    }

    /**
     * Combo for picking a multi-band stitched scan. "Off" returns the engine
     * to the legacy single-range path. Selecting any other entry installs a
     * {@link FrequencyPlan} that drops the gaps between bands and paints
     * separator lines on the spectrum chart.
     */
    private ComboBox<FrequencyMultiRangePreset> buildMultiRangeCombo(
            CustomMultiRangeEditor customEditor) {
        ComboBox<FrequencyMultiRangePreset> combo = new ComboBox<>();
        combo.getItems().addAll(FrequencyMultiRangePreset.defaults());
        // Initial selection mirrors the model: a non-null plan in the store
        // (e.g. restored from preferences in the future) selects its preset
        // when the names match; otherwise we land on Off.
        FrequencyPlan currentPlan = settings.getFrequencyPlan().getValue();
        FrequencyMultiRangePreset initial = FrequencyMultiRangePreset.OFF;
        if (currentPlan != null) {
            for (FrequencyMultiRangePreset p : combo.getItems()) {
                if (p.getPlan() != null && p.getPlan().equals(currentPlan)) {
                    initial = p;
                    break;
                }
            }
        }
        combo.getSelectionModel().select(initial);
        combo.valueProperty().addListener((obs, oldV, newV) -> {
            if (newV == null) return;
            boolean custom = (newV == FrequencyMultiRangePreset.CUSTOM);
            customEditor.setVisible(custom);
            customEditor.setManaged(custom);
            if (custom) {
                // Seed the editor with whatever segments the user already had:
                // the active multi-range plan (so they can tweak a preset they
                // just picked), or the current single-range frequency.
                FrequencyPlan active = settings.getFrequencyPlan().getValue();
                java.util.List<jspectrumanalyzer.core.FrequencyRange> seed =
                        (active != null)
                                ? active.segments()
                                : java.util.Collections.singletonList(
                                        settings.getFrequency().getValue());
                customEditor.seedWith(seed);
            } else {
                // Off -> requestRetunePlan(null) clears the plan;
                // any other preset publishes its plan. SdrController
                // dedups against the current value so re-selecting
                // the active preset is a no-op.
                sdrController.requestRetunePlan(newV.getPlan());
            }
        });

        // Model -> combo: when something else changes the frequency plan
        // (notably the Wi-Fi window picking "All bands" or returning to a
        // single range), keep the combo selection in sync so the UI never
        // silently lies about which preset is active. The setValue equality
        // check inside ModelValue prevents the combo->model listener above
        // from looping back and re-firing.
        settings.getFrequencyPlan().addListener(() ->
                javafx.application.Platform.runLater(() -> {
                    FrequencyPlan current = settings.getFrequencyPlan().getValue();
                    FrequencyMultiRangePreset target = FrequencyMultiRangePreset.OFF;
                    if (current != null) {
                        for (FrequencyMultiRangePreset p : combo.getItems()) {
                            if (p.getPlan() != null && p.getPlan().equals(current)) {
                                target = p;
                                break;
                            }
                        }
                        // Plan exists but no preset matches: must be a custom
                        // multi-range; switch to CUSTOM so the editor opens.
                        if (target == FrequencyMultiRangePreset.OFF) {
                            target = FrequencyMultiRangePreset.CUSTOM;
                        }
                    }
                    if (combo.getValue() != target) {
                        combo.getSelectionModel().select(target);
                    }
                }));
        FxControls.withTooltip(combo,
                "Stitch multiple bands into a single chart by removing the dead air "
                + "between them. Wi-Fi 2.4 + 5 + 6E sweeps three regulatory sub-bands "
                + "and skips ~2.6 GHz of unused spectrum, so each band gets full "
                + "horizontal resolution. Pick \"Custom...\" to define your own "
                + "segments (max " + FrequencyMultiRangePreset.MAX_CUSTOM_SEGMENTS
                + ") or \"Off\" to go back to a single-range scan.");
        combo.setMaxWidth(Double.MAX_VALUE);
        return combo;
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

    /**
     * Quick-pick chips for FFT size (Samples). Mirrors the RBW chip row so
     * users can get to the common values without having to step the spinner
     * 64x or remember whether libhackrf wants 32k or 32768.
     */
    private FlowPane buildFftPresets() {
        FlowPane chips = new FlowPane(4, 4);
        chips.getChildren().add(presetLabel("FFT size:"));
        for (int n : FFT_PRESETS_SAMPLES) {
            Button chip = new Button(formatFftLabel(n));
            chip.getStyleClass().add("preset-chip");
            FxControls.withTooltip(chip, "Set FFT size to " + n + " samples.");
            chip.setOnAction(e -> settings.getSamples().setValue(n));
            chips.getChildren().add(chip);
        }
        return chips;
    }

    private static String formatFftLabel(int samples) {
        // Render in "k" units once we hit 1024 so the chip stays narrow:
        // 4096 -> "4k", 65536 -> "64k", 131072 -> "128k".
        if (samples >= 1024 && samples % 1024 == 0) {
            return (samples / 1024) + "k";
        }
        return Integer.toString(samples);
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
        sdrController.requestRetune(validator.coerce(newStart, newEnd));
    }
}
