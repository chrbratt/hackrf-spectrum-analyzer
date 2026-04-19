package jspectrumanalyzer.fx.chart;

import java.util.function.Supplier;

import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import jspectrumanalyzer.fx.model.SettingsStore;
import shared.mvc.ModelValue.ModelValueBoolean;

/**
 * Compact "what colour means what" legend pinned to the spectrum chart's
 * top-right corner.
 *
 * <p>Each chip mirrors the matching <i>ChartsXxxVisible</i> setting:
 * disabling a trace in the Display tab hides the corresponding chip entirely
 * (both {@code visible} and {@code managed} flip), so the legend only ever
 * advertises traces that are actually being drawn. When all chips are hidden
 * the HBox shrinks to zero and disappears completely.
 *
 * <p>Chip dot colours are sourced from the active {@link GraphTheme} so the
 * legend always matches what the chart is currently painting; switching
 * themes from the Display tab repaints all dots in place.
 *
 * <p>Mounted in a {@link javafx.scene.layout.StackPane} above the
 * {@link org.jfree.chart.fx.ChartViewer}; uses {@code pickOnBounds=false} so
 * mouse events outside the chips pass through to the zoom controller.
 */
public final class LegendOverlay extends HBox {

    public LegendOverlay(SettingsStore settings) {
        getStyleClass().add("legend-overlay");
        setSpacing(4);
        setAlignment(Pos.CENTER_RIGHT);
        // Stay at the natural (preferred) size in both axes - we never want
        // the StackPane parent to grow this overlay to fill chart space.
        setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
        // Picking only on actual chips - empty horizontal space between or
        // around them must not swallow drag-zoom events on the chart below.
        setPickOnBounds(false);

        // Each chip resolves its colour lazily through a Supplier that reads
        // the live theme spec. That way the same chip handles every theme
        // switch without us having to reattach listeners or rebuild children.
        Chip peaks   = buildChip("Peaks",   () -> settings.getGraphTheme().getValue().spec().peaksFx(),
                                 settings.isChartsPeaksVisible());
        Chip average = buildChip("Average", () -> settings.getGraphTheme().getValue().spec().averageFx(),
                                 settings.isChartsAverageVisible());
        Chip maxHold = buildChip("Max",     () -> settings.getGraphTheme().getValue().spec().maxHoldFx(),
                                 settings.isChartsMaxHoldVisible());
        Chip live    = buildChip("Live",    () -> settings.getGraphTheme().getValue().spec().realtimeFx(),
                                 settings.isChartsRealtimeVisible());

        getChildren().addAll(peaks.box, average.box, maxHold.box, live.box);

        // Single listener on the theme model fans out to all chips. Cheap:
        // we just re-read the supplier and write the dot fill.
        settings.getGraphTheme().addListener(() -> Platform.runLater(() -> {
            peaks.repaint();
            average.repaint();
            maxHold.repaint();
            live.repaint();
        }));
    }

    /** Bundle of "the chip's HBox" + "how to repaint its dot". */
    private record Chip(HBox box, Runnable repaint) {}

    private static Chip buildChip(String label, Supplier<Color> colorSupplier, ModelValueBoolean visible) {
        Circle dot = new Circle(5, colorSupplier.get());
        Label text = new Label(label);
        text.getStyleClass().add("legend-chip-label");
        HBox chip = new HBox(5, dot, text);
        chip.getStyleClass().add("legend-chip");
        chip.setAlignment(Pos.CENTER_LEFT);

        // Bind both visible and managed - managed=false also removes the chip
        // from layout so the HBox shrinks instead of leaving a gap.
        Runnable refresh = () -> {
            boolean show = visible.getValue();
            chip.setVisible(show);
            chip.setManaged(show);
        };
        visible.addListener(() -> Platform.runLater(refresh));
        refresh.run();

        Runnable repaint = () -> dot.setFill(colorSupplier.get());
        return new Chip(chip, repaint);
    }
}
