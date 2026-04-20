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
        // Push the model's current state into the new control BEFORE wiring
        // listeners. {@link FxModelBinder#bindBoolean} only attaches change
        // listeners; it does not seed the initial value, so without this
        // every checkbox would render unchecked even when the underlying
        // setting is true.
        box.setSelected(model.getValue());
        FxModelBinder.bindBoolean(box.selectedProperty(), model);
        return box;
    }

    public static Label sectionTitle(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("settings-section-title");
        return label;
    }

    /**
     * Caption-on-top + control combo with proper accessibility wiring:
     * the {@link Label#setLabelFor(Node)} link makes assistive tech (and
     * mnemonic Alt-shortcuts) treat the label as describing the control,
     * which the duplicated {@code new VBox(2, new Label(...), control)}
     * pattern scattered through the legacy code did not. Returns a VBox
     * so the call sites read as declaratively as before.
     */
    public static VBox labeled(String caption, Node control) {
        Label label = new Label(caption);
        label.setLabelFor(control);
        VBox box = new VBox(2, label, control);
        return box;
    }

    public static VBox section(String title, Node... children) {
        VBox box = new VBox(6);
        box.setPadding(new Insets(4, 0, 8, 0));
        box.getChildren().add(sectionTitle(title));
        for (Node n : children) box.getChildren().add(n);
        return box;
    }

    /**
     * Section that leads with a one-line "how to read this" hint
     * immediately under the title. Designed for the Wi-Fi window's
     * pedagogical layout where every panel needs to orient a new user
     * before they look at the data. The hint is shorter than the
     * section's existing tooltip - it is the "what" rather than the
     * "how / why" - so users get oriented at a glance and only reach
     * for the tooltip when they want detail.
     */
    public static VBox sectionWithHowTo(String title, String howToRead, Node... children) {
        VBox box = new VBox(4);
        box.setPadding(new Insets(4, 0, 8, 0));
        box.getChildren().add(sectionTitle(title));
        if (howToRead != null && !howToRead.isBlank()) {
            javafx.scene.control.Label hint = new javafx.scene.control.Label(howToRead);
            hint.setWrapText(true);
            hint.getStyleClass().add("wifi-section-howto");
            box.getChildren().add(hint);
        }
        for (Node n : children) box.getChildren().add(n);
        return box;
    }

    /**
     * Wrap a content node in a {@link javafx.scene.control.TitledPane}
     * so the user can collapse it. The title font matches
     * {@code .settings-section-title} via the {@code .titled-pane}
     * theming in {@code dark.css}, so collapsed and expanded panes read
     * as the same kind of heading - they just have a disclosure arrow.
     *
     * @param title          heading shown on the pane bar
     * @param expanded       whether to start expanded - pass false for
     *                       sections the user only occasionally needs
     *                       (capture / advanced / experimental)
     * @param content        single content node; wrap multiple children
     *                       in a {@link VBox} before calling
     */
    public static javafx.scene.control.TitledPane collapsible(String title,
                                                              boolean expanded,
                                                              Node content) {
        javafx.scene.control.TitledPane tp = new javafx.scene.control.TitledPane(title, content);
        tp.setExpanded(expanded);
        tp.setCollapsible(true);
        tp.setAnimated(true);
        return tp;
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
