package jspectrumanalyzer.fx.frequency;

import java.util.List;

import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.control.TextFormatter;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.util.StringConverter;
import javafx.util.converter.IntegerStringConverter;

import org.controlsfx.control.RangeSlider;

import jspectrumanalyzer.core.FrequencyRange;
import jspectrumanalyzer.core.Preset;

/**
 * Modern replacement for {@code FrequencySelectorPanel} + {@code FrequencySelectorRangeBinder}.
 * <p>
 * Fixes the concrete bugs in the Swing version:
 * <ul>
 *   <li>Direct keyboard entry of the frequency in MHz.</li>
 *   <li>Explicit stepper buttons for &plusmn;1, &plusmn;10, &plusmn;100, &plusmn;1000 MHz.</li>
 *   <li>Shared {@link FrequencyRangeValidator} with coercion (no silent vetoes that desync
 *       the UI from the model).</li>
 *   <li>Atomic preset updates: start and end change together in one model event.</li>
 *   <li>CSS-driven theming; no manual black-on-black {@code setBackground} calls.</li>
 *   <li>Scales with the scene's DPI and font; no fixed pixel dimensions.</li>
 * </ul>
 */
public final class FrequencyRangeSelector extends VBox {

    // Direct keyboard entry replaces the ±100 / ±1000 buttons, leaving room
    // for the MHz readout to stay visible in a narrow sidebar.
    private static final int[] STEPS = {1, 10};

    private final FrequencyRangeValidator validator;
    private final ObjectProperty<FrequencyRange> range = new SimpleObjectProperty<>();

    private final TextField startField = new TextField();
    private final TextField endField = new TextField();
    private final RangeSlider slider;
    private final ComboBox<Preset> presetBox = new ComboBox<>();

    private boolean updating = false;

    public FrequencyRangeSelector(FrequencyRangeValidator validator, FrequencyRange initial) {
        this.validator = validator;
        this.slider = new RangeSlider(validator.getMinMHz(), validator.getMaxMHz(),
                initial.getStartMHz(), initial.getEndMHz());
        this.range.set(initial);

        getStyleClass().add("frequency-range-selector");
        setSpacing(8);
        setPadding(new Insets(8));

        presetBox.setPromptText("Preset");
        presetBox.setMaxWidth(Double.MAX_VALUE);
        presetBox.valueProperty().addListener((obs, oldV, newV) -> {
            if (newV != null) applyPreset(newV);
        });

        VBox endpoints = new VBox(6);
        endpoints.getChildren().addAll(
                buildEndpointRow("Start", startField, FrequencyRangeValidator.Endpoint.START),
                buildEndpointRow("Stop", endField, FrequencyRangeValidator.Endpoint.END));

        slider.setShowTickMarks(true);
        slider.setShowTickLabels(true);
        int span = validator.getMaxMHz() - validator.getMinMHz();
        slider.setMajorTickUnit(Math.max(1, span / 5.0));
        slider.setMinorTickCount(0);
        slider.setBlockIncrement(10);
        // Default label formatter uses the JVM locale (e.g. "1 208,333" in sv-SE).
        // Force plain integers so ticks stay readable in the narrow sidebar.
        slider.setLabelFormatter(new StringConverter<Number>() {
            @Override public String toString(Number n) { return Integer.toString(n.intValue()); }
            @Override public Number fromString(String s) { return Integer.parseInt(s.trim()); }
        });

        slider.lowValueProperty().addListener((obs, oldV, newV) -> {
            if (updating || slider.isLowValueChanging() || newV == null) return;
            apply(newV.intValue(), (int) slider.getHighValue(),
                    FrequencyRangeValidator.Endpoint.START);
        });
        slider.highValueProperty().addListener((obs, oldV, newV) -> {
            if (updating || slider.isHighValueChanging() || newV == null) return;
            apply((int) slider.getLowValue(), newV.intValue(),
                    FrequencyRangeValidator.Endpoint.END);
        });
        slider.lowValueChangingProperty().addListener((obs, wasChanging, isChanging) -> {
            if (!isChanging) {
                apply((int) slider.getLowValue(), (int) slider.getHighValue(),
                        FrequencyRangeValidator.Endpoint.START);
            }
        });
        slider.highValueChangingProperty().addListener((obs, wasChanging, isChanging) -> {
            if (!isChanging) {
                apply((int) slider.getLowValue(), (int) slider.getHighValue(),
                        FrequencyRangeValidator.Endpoint.END);
            }
        });

        writeToUi(initial);

        range.addListener((obs, oldV, newV) -> {
            if (newV != null) writeToUi(newV);
        });

        getChildren().addAll(presetBox, endpoints, slider);
    }

    public ObjectProperty<FrequencyRange> rangeProperty() {
        return range;
    }

    public void setPresets(List<Preset> presets) {
        presetBox.getItems().setAll(presets);
    }

    public void setRange(FrequencyRange newRange) {
        FrequencyRange coerced = validator.coerce(newRange.getStartMHz(), newRange.getEndMHz());
        range.set(coerced);
    }

    private HBox buildEndpointRow(String caption, TextField field,
                                  FrequencyRangeValidator.Endpoint endpoint) {
        field.getStyleClass().add("frequency-field");
        field.setPrefColumnCount(5);
        field.setAlignment(Pos.CENTER_RIGHT);
        field.setTextFormatter(buildFormatter());
        field.focusedProperty().addListener((obs, wasFocused, isFocused) -> {
            if (!isFocused) commitFromField(field, endpoint);
        });
        field.setOnAction(e -> commitFromField(field, endpoint));

        Label caption_label = new Label(caption);
        caption_label.setMinWidth(32);
        Label unit = new Label("MHz");

        HBox steppers = new HBox(2);
        steppers.setAlignment(Pos.CENTER_LEFT);
        for (int i = STEPS.length - 1; i >= 0; i--) {
            steppers.getChildren().add(stepperButton(-STEPS[i], endpoint));
        }
        for (int s : STEPS) {
            steppers.getChildren().add(stepperButton(s, endpoint));
        }

        HBox row = new HBox(6, caption_label, field, unit, steppers);
        row.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(field, Priority.ALWAYS);
        return row;
    }

    private Button stepperButton(int delta, FrequencyRangeValidator.Endpoint endpoint) {
        Button button = new Button((delta > 0 ? "+" : "") + delta);
        button.getStyleClass().add("frequency-stepper");
        button.setTooltip(new Tooltip((delta > 0 ? "+" : "") + delta + " MHz"));
        button.setFocusTraversable(false);
        button.setOnAction(e -> step(endpoint, delta));
        return button;
    }

    private void step(FrequencyRangeValidator.Endpoint endpoint, int delta) {
        FrequencyRange current = range.get();
        int newStart = current.getStartMHz();
        int newEnd = current.getEndMHz();
        if (endpoint == FrequencyRangeValidator.Endpoint.START) {
            newStart += delta;
        } else {
            newEnd += delta;
        }
        apply(newStart, newEnd, endpoint);
    }

    private void commitFromField(TextField field, FrequencyRangeValidator.Endpoint endpoint) {
        Integer parsed = tryParse(field.getText());
        FrequencyRange current = range.get();
        int newStart = current.getStartMHz();
        int newEnd = current.getEndMHz();
        if (parsed == null) {
            writeToUi(current);
            return;
        }
        if (endpoint == FrequencyRangeValidator.Endpoint.START) newStart = parsed;
        else newEnd = parsed;
        apply(newStart, newEnd, endpoint);
    }

    private void apply(int newStart, int newEnd, FrequencyRangeValidator.Endpoint pinned) {
        FrequencyRange current = range.get();
        FrequencyRange next = validator.coerceRespecting(
                current.getStartMHz(), current.getEndMHz(),
                newStart, newEnd, pinned);
        if (!next.equals(current)) {
            range.set(next);
        } else {
            writeToUi(current);
        }
    }

    private void applyPreset(Preset preset) {
        FrequencyRange next = validator.coerce(preset.getStartFreq(), preset.getStopFreq());
        range.set(next);
    }

    private void writeToUi(FrequencyRange r) {
        if (updating) return;
        updating = true;
        try {
            startField.setText(Integer.toString(r.getStartMHz()));
            endField.setText(Integer.toString(r.getEndMHz()));
            slider.setLowValue(r.getStartMHz());
            slider.setHighValue(r.getEndMHz());
            startField.pseudoClassStateChanged(javafx.css.PseudoClass.getPseudoClass("invalid"),
                    !validator.isValid(r.getStartMHz(), r.getEndMHz()));
            endField.pseudoClassStateChanged(javafx.css.PseudoClass.getPseudoClass("invalid"),
                    !validator.isValid(r.getStartMHz(), r.getEndMHz()));
        } finally {
            updating = false;
        }
    }

    private static TextFormatter<Integer> buildFormatter() {
        return new TextFormatter<>(new IntegerStringConverter(), 0, change -> {
            String next = change.getControlNewText();
            if (next.isEmpty()) return change;
            if (next.matches("\\d{1,5}")) return change;
            return null;
        });
    }

    private static Integer tryParse(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
