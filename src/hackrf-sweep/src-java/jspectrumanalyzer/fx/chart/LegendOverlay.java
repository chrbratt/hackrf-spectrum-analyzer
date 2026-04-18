package jspectrumanalyzer.fx.chart;

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
 * <p>The dot colours come from {@link SpectrumChart.Palette}'s {@code _FX}
 * constants, which are derived from the AWT constants the JFreeChart renderer
 * uses; that guarantees the legend matches what the chart paints without
 * forcing this FX overlay to import {@code java.awt}.
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

        getChildren().addAll(
                buildChip("Peaks",   SpectrumChart.Palette.PEAKS_FX,    settings.isChartsPeaksVisible()),
                buildChip("Average", SpectrumChart.Palette.AVERAGE_FX,  settings.isChartsAverageVisible()),
                buildChip("Max",     SpectrumChart.Palette.MAX_HOLD_FX, settings.isChartsMaxHoldVisible()),
                buildChip("Live",    SpectrumChart.Palette.REALTIME_FX, settings.isChartsRealtimeVisible()));
    }

    private static HBox buildChip(String label, Color color, ModelValueBoolean visible) {
        Circle dot = new Circle(5, color);
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
        return chip;
    }
}
