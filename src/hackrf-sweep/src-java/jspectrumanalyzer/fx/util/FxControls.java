package jspectrumanalyzer.fx.util;

import javafx.geometry.HPos;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.Slider;
import javafx.scene.control.Spinner;
import javafx.scene.control.SpinnerValueFactory;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.util.Duration;
import jspectrumanalyzer.fx.model.FxModelBinder;
import shared.mvc.ModelValue.ModelValueBoolean;
import shared.mvc.ModelValue.ModelValueInt;

/**
 * Small helpers to keep settings-pane code terse and declarative.
 */
public final class FxControls {

    private FxControls() {}

    public static Slider slider(ModelValueInt model, int min, int max) {
        int lo = model.isBounded() ? model.getMin() : min;
        int hi = model.isBounded() ? model.getMax() : max;
        int step = (model.isBounded() && model.getStep() > 0) ? model.getStep() : 1;

        Slider slider = new Slider(lo, hi, model.getValue());
        // Snap to the hardware step grid so non-legal values never reach the native
        // layer (e.g. HackRF LNA gain only accepts 0, 8, 16, 24, 32, 40 dB).
        slider.setMajorTickUnit(step);
        slider.setMinorTickCount(0);
        slider.setSnapToTicks(true);
        slider.setShowTickMarks(true);
        slider.setShowTickLabels(true);
        slider.setBlockIncrement(step);

        slider.valueProperty().addListener((obs, oldV, newV) -> {
            if (!slider.isValueChanging() && newV != null) {
                model.setValue(clampToModel(model, newV.intValue()));
            }
        });
        slider.valueChangingProperty().addListener((obs, wasChanging, isChanging) -> {
            if (!isChanging) {
                model.setValue(clampToModel(model, (int) slider.getValue()));
            }
        });
        model.addListener(() -> javafx.application.Platform.runLater(() -> {
            if ((int) slider.getValue() != model.getValue()) {
                slider.setValue(model.getValue());
            }
        }));
        return slider;
    }

    public static Spinner<Integer> intSpinner(ModelValueInt model, int min, int max, int step) {
        int lo = model.isBounded() ? model.getMin() : min;
        int hi = model.isBounded() ? model.getMax() : max;
        int s = (model.isBounded() && model.getStep() > 0) ? model.getStep() : step;
        int initial = clampToModel(model, model.getValue());
        Spinner<Integer> spinner = new Spinner<>(lo, hi, initial, s);
        spinner.setEditable(true);
        spinner.valueProperty().addListener((obs, oldV, newV) -> {
            if (newV != null) model.setValue(clampToModel(model, newV));
        });
        model.addListener(() -> javafx.application.Platform.runLater(() -> {
            if (spinner.getValue() == null || spinner.getValue() != model.getValue()) {
                ((SpinnerValueFactory.IntegerSpinnerValueFactory) spinner.getValueFactory())
                        .setValue(model.getValue());
            }
        }));
        return spinner;
    }

    /**
     * Clamp to [min, max] and snap to the model's step grid when it is bounded.
     * Without the snap, a slider / spinner value that falls between steps (e.g.
     * LNA 5 dB when the step is 8) would reach the native HackRF layer and crash
     * the sweep process.
     */
    static int clampToModel(ModelValueInt model, int v) {
        if (!model.isBounded()) return v;
        return snap(v, model.getMin(), model.getMax(), model.getStep());
    }

    /** Package-private for unit testing. */
    static int snap(int v, int lo, int hi, int step) {
        int clamped = Math.max(lo, Math.min(hi, v));
        if (step <= 1) return clamped;
        int rel = clamped - lo;
        int snapped = lo + Math.round(rel / (float) step) * step;
        if (snapped > hi) snapped -= step;
        if (snapped < lo) snapped = lo;
        return snapped;
    }

    public static CheckBox checkBox(String text, ModelValueBoolean model) {
        CheckBox box = new CheckBox(text);
        FxModelBinder.bindBoolean(box.selectedProperty(), model);
        return box;
    }

    public static Label sectionTitle(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("settings-section-title");
        return label;
    }

    public static VBox section(String title, Node... children) {
        VBox box = new VBox(6);
        box.setPadding(new Insets(4, 0, 8, 0));
        box.getChildren().add(sectionTitle(title));
        for (Node n : children) box.getChildren().add(n);
        return box;
    }

    public static GridPane gridForm() {
        GridPane grid = new GridPane();
        grid.setHgap(8);
        grid.setVgap(6);
        ColumnConstraints label = new ColumnConstraints();
        label.setHalignment(HPos.LEFT);
        label.setMinWidth(120);
        ColumnConstraints control = new ColumnConstraints();
        control.setHgrow(Priority.ALWAYS);
        grid.getColumnConstraints().addAll(label, control);
        return grid;
    }

    public static void addRow(GridPane grid, String caption, Node control) {
        int row = grid.getRowCount();
        grid.add(new Label(caption), 0, row);
        grid.add(control, 1, row);
    }

    /**
     * Attach a wrapping tooltip with a slightly shorter show-delay than the
     * default. Returns the node so calls can be chained inline.
     */
    public static <N extends Node> N withTooltip(N node, String text) {
        Tooltip t = new Tooltip(text);
        t.setShowDelay(Duration.millis(400));
        t.setWrapText(true);
        t.setMaxWidth(360);
        Tooltip.install(node, t);
        return node;
    }
}
