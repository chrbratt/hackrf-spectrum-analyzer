package jspectrumanalyzer.fx.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Spinner;
import javafx.scene.control.SpinnerValueFactory;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import jspectrumanalyzer.core.FrequencyMultiRangePreset;
import jspectrumanalyzer.core.FrequencyPlan;
import jspectrumanalyzer.core.FrequencyRange;
import jspectrumanalyzer.fx.frequency.FrequencyRangeValidator;
import jspectrumanalyzer.fx.util.FxControls;

/**
 * Compact "list of segments" editor for the {@link FrequencyMultiRangePreset#CUSTOM}
 * mode in the Scan tab.
 * <p>
 * Each row is a {@code [Start MHz] [Stop MHz] [-]} triple; an "Add segment"
 * button lives at the bottom and is disabled once
 * {@link FrequencyMultiRangePreset#MAX_CUSTOM_SEGMENTS} rows exist. Whenever
 * any value changes the editor rebuilds a {@link FrequencyPlan} and pushes
 * it through the {@code onPlanChanged} callback - but only if the resulting
 * plan is valid (segments in range, non-zero width, non-overlapping). On
 * invalid input a status label below the rows explains the reason and the
 * previous good plan stays active.
 * <p>
 * The first row carries no remove button: at least one segment must always
 * exist while CUSTOM is selected.
 */
public final class CustomMultiRangeEditor extends VBox {

    private final FrequencyRangeValidator validator;
    private final Consumer<FrequencyPlan> onPlanChanged;
    private final VBox rowContainer = new VBox(4);
    private final List<SegmentRow> rows = new ArrayList<>();
    private final Button addButton = new Button("+ Add segment");
    private final Label status = new Label();

    public CustomMultiRangeEditor(FrequencyRangeValidator validator,
                                  Consumer<FrequencyPlan> onPlanChanged) {
        this.validator = validator;
        this.onPlanChanged = onPlanChanged;

        setSpacing(6);
        setPadding(new Insets(4, 0, 0, 0));

        Label header = new Label("Custom segments (max "
                + FrequencyMultiRangePreset.MAX_CUSTOM_SEGMENTS + ")");
        header.getStyleClass().add("preset-caption");

        addButton.getStyleClass().add("preset-chip");
        FxControls.withTooltip(addButton,
                "Add another (Start, Stop) segment to the custom plan. "
                + "Each extra segment costs one retune step (~10 ms) per sweep.");
        addButton.setOnAction(e -> {
            addRow(suggestNextSegment());
            rebuildAndPush();
        });

        status.getStyleClass().add("preset-caption");
        status.setWrapText(true);
        status.setVisible(false);
        status.setManaged(false);

        getChildren().addAll(header, rowContainer, addButton, status);
    }

    /**
     * Reset the editor to a sensible starting state. Called by the parent
     * each time the user switches into CUSTOM mode so the editor never
     * starts empty.
     */
    public void seedWith(List<FrequencyRange> initial) {
        rows.clear();
        rowContainer.getChildren().clear();
        if (initial == null || initial.isEmpty()) {
            addRow(new FrequencyRange(2400, 2500));
            addRow(new FrequencyRange(5150, 5895));
        } else {
            int n = Math.min(initial.size(), FrequencyMultiRangePreset.MAX_CUSTOM_SEGMENTS);
            for (int i = 0; i < n; i++) addRow(initial.get(i));
        }
        rebuildAndPush();
    }

    /** Current valid plan, or {@code null} if rows are inconsistent. */
    public FrequencyPlan currentPlan() {
        return tryBuildPlan();
    }

    private void addRow(FrequencyRange initial) {
        SegmentRow row = new SegmentRow(initial);
        rows.add(row);
        rowContainer.getChildren().add(row);
        refreshRowChrome();
    }

    private void removeRow(SegmentRow row) {
        rows.remove(row);
        rowContainer.getChildren().remove(row);
        refreshRowChrome();
        rebuildAndPush();
    }

    private void refreshRowChrome() {
        // The first row's remove button stays hidden so the user cannot
        // delete the last remaining segment - CUSTOM mode always needs at
        // least one band.
        for (int i = 0; i < rows.size(); i++) {
            rows.get(i).setRemovable(rows.size() > 1);
        }
        addButton.setDisable(rows.size() >= FrequencyMultiRangePreset.MAX_CUSTOM_SEGMENTS);
    }

    /**
     * Pick a sane initial range for a freshly added row: start one MHz above
     * the highest current Stop value (or in the middle of the spectrum if
     * that would push past the validator's max).
     */
    private FrequencyRange suggestNextSegment() {
        int highestStop = validator.getMinMHz();
        for (SegmentRow r : rows) {
            highestStop = Math.max(highestStop, r.stopValue());
        }
        int start = highestStop + 1;
        int stop = start + 100;
        if (stop > validator.getMaxMHz()) {
            int mid = (validator.getMinMHz() + validator.getMaxMHz()) / 2;
            start = mid;
            stop = mid + 100;
        }
        return validator.coerce(start, stop);
    }

    private void rebuildAndPush() {
        FrequencyPlan plan = tryBuildPlan();
        if (plan == null) return;
        clearStatus();
        onPlanChanged.accept(plan);
    }

    /**
     * Build a {@link FrequencyPlan} from the current rows and surface any
     * validation problem in the status label. Returns {@code null} when the
     * rows do not form a legal plan; callers must keep the previously
     * applied plan in that case so the engine never stops mid-sweep on a
     * half-edited input.
     */
    private FrequencyPlan tryBuildPlan() {
        if (rows.isEmpty()) {
            showStatus("No segments. Add one to start scanning.");
            return null;
        }
        List<FrequencyRange> segments = new ArrayList<>(rows.size());
        for (int i = 0; i < rows.size(); i++) {
            SegmentRow row = rows.get(i);
            int start = row.startValue();
            int stop = row.stopValue();
            boolean valid = stop > start && validator.isValid(start, stop);
            row.setError(!valid);
            if (!valid) {
                showStatus("Segment " + (i + 1) + " has Stop \u2264 Start "
                        + "or is outside " + validator.getMinMHz() + "\u2013"
                        + validator.getMaxMHz() + " MHz.");
                return null;
            }
            segments.add(new FrequencyRange(start, stop));
        }
        try {
            FrequencyPlan plan = new FrequencyPlan(segments);
            for (SegmentRow r : rows) r.setError(false);
            return plan;
        } catch (IllegalArgumentException ex) {
            // Most likely overlap; FrequencyPlan sorts internally so we have
            // to find the offending pair against the *original* row order
            // to colour the right rows.
            highlightOverlaps();
            showStatus("Segments overlap. Adjust ranges so they do not share any MHz.");
            return null;
        }
    }

    private void highlightOverlaps() {
        for (SegmentRow r : rows) r.setError(false);
        for (int i = 0; i < rows.size(); i++) {
            SegmentRow a = rows.get(i);
            for (int j = i + 1; j < rows.size(); j++) {
                SegmentRow b = rows.get(j);
                if (overlap(a.startValue(), a.stopValue(),
                            b.startValue(), b.stopValue())) {
                    a.setError(true);
                    b.setError(true);
                }
            }
        }
    }

    private static boolean overlap(int s1, int e1, int s2, int e2) {
        return s1 < e2 && s2 < e1;
    }

    private void showStatus(String message) {
        status.setText(message);
        if (!status.isVisible()) {
            status.setVisible(true);
            status.setManaged(true);
        }
    }

    private void clearStatus() {
        if (status.isVisible()) {
            status.setVisible(false);
            status.setManaged(false);
            status.setText("");
        }
    }

    /**
     * One row in the editor: caption + Start spinner + caption + Stop spinner
     * + remove button. The error styling toggles a CSS pseudo-class on the
     * spinners so {@code dark.css} (or any future theme) can paint invalid
     * rows distinctly without the editor knowing about colours.
     */
    private final class SegmentRow extends HBox {

        private final Spinner<Integer> start;
        private final Spinner<Integer> stop;
        private final Button remove;

        SegmentRow(FrequencyRange initial) {
            super(6);
            setAlignment(Pos.CENTER_LEFT);

            int initStart = initial.getStartMHz();
            int initStop = initial.getEndMHz();

            start = mhzSpinner(initStart);
            stop = mhzSpinner(initStop);
            FxControls.withTooltip(start, "Lower edge of this segment in MHz.");
            FxControls.withTooltip(stop, "Upper edge of this segment in MHz.");

            start.valueProperty().addListener((obs, o, n) -> rebuildAndPush());
            stop.valueProperty().addListener((obs, o, n) -> rebuildAndPush());

            remove = new Button("\u2212");
            remove.getStyleClass().add("preset-chip");
            FxControls.withTooltip(remove, "Remove this segment.");
            remove.setOnAction(e -> removeRow(this));

            HBox.setHgrow(start, Priority.ALWAYS);
            HBox.setHgrow(stop, Priority.ALWAYS);
            start.setMaxWidth(Double.MAX_VALUE);
            stop.setMaxWidth(Double.MAX_VALUE);

            getChildren().addAll(new Label("Start"), start,
                                  new Label("Stop"), stop, remove);
        }

        int startValue() {
            Integer v = start.getValue();
            return v == null ? validator.getMinMHz() : v;
        }

        int stopValue() {
            Integer v = stop.getValue();
            return v == null ? validator.getMinMHz() : v;
        }

        void setRemovable(boolean removable) {
            remove.setVisible(removable);
            remove.setManaged(removable);
        }

        void setError(boolean error) {
            start.pseudoClassStateChanged(SEGMENT_ERROR, error);
            stop.pseudoClassStateChanged(SEGMENT_ERROR, error);
        }

        private Spinner<Integer> mhzSpinner(int initial) {
            int clamped = Math.max(validator.getMinMHz(),
                    Math.min(validator.getMaxMHz(), initial));
            Spinner<Integer> s = new Spinner<>(
                    new SpinnerValueFactory.IntegerSpinnerValueFactory(
                            validator.getMinMHz(), validator.getMaxMHz(),
                            clamped, 10));
            s.setEditable(true);
            return s;
        }
    }

    private static final javafx.css.PseudoClass SEGMENT_ERROR =
            javafx.css.PseudoClass.getPseudoClass("segment-error");
}
