package jspectrumanalyzer.fx.ui;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.util.StringConverter;
import org.controlsfx.control.RangeSlider;
import jspectrumanalyzer.fx.model.SettingsStore;
import jspectrumanalyzer.fx.util.FxControls;
import shared.mvc.ModelValue.ModelValueInt;

/**
 * Display tab: how the spectrum and waterfall look on screen.
 * <p>
 * The on/off toggles for waterfall and persistent display live in the
 * {@code ChartToolbar} above the chart, so this tab focuses purely on the
 * shape of those displays (waterfall scroll speed, palette range, persistence).
 */
public final class DisplayTab extends ScrollPane {

    private static final int PALETTE_MIN = -150;
    private static final int PALETTE_MAX = 0;
    private static final int PALETTE_START_STEP = 10;
    private static final int PALETTE_SIZE_STEP = 5;
    private static final int PALETTE_MIN_SIZE = 5;

    private final SettingsStore settings;

    public DisplayTab(SettingsStore settings) {
        this.settings = settings;
        setFitToWidth(true);
        setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);

        VBox content = new VBox(12);
        content.setPadding(new Insets(12));
        content.getChildren().addAll(
                FxControls.section("Waterfall",
                        labeled("Speed (1 = slow, 10 = fast)",
                                FxControls.withTooltip(
                                        FxControls.slider(settings.getWaterfallSpeed(), 1, 10),
                                        "How many sweeps per pushed waterfall row. "
                                        + "10 = every sweep (fast scroll, less averaging), "
                                        + "1 = every 10th sweep (slow scroll, more averaging)."))),
                FxControls.section("Palette",
                        new Label("Mapped power range (dBm)"),
                        buildPaletteRangeSlider(),
                        paletteReadout()),
                FxControls.section("Persistent display",
                        labeled("Persistence time (s)",
                                FxControls.withTooltip(
                                        FxControls.intSpinner(settings.getPersistentDisplayDecayRate(), 1, 60, 1),
                                        "Seconds an arriving sample stays visible in the persistent overlay "
                                        + "before fully decaying. Larger = longer trails."))));
        setContent(content);
    }

    /**
     * Single {@link RangeSlider} that drives both palette start and palette
     * size. Replaces two separate sliders that forced the user to mentally
     * compute the resulting dB window.
     */
    private RangeSlider buildPaletteRangeSlider() {
        ModelValueInt start = settings.getSpectrumPaletteStart();
        ModelValueInt size = settings.getSpectrumPaletteSize();

        int initialLow = clamp(start.getValue(), PALETTE_MIN, PALETTE_MAX - PALETTE_MIN_SIZE);
        int initialHigh = clamp(initialLow + Math.max(PALETTE_MIN_SIZE, size.getValue()),
                initialLow + PALETTE_MIN_SIZE, PALETTE_MAX);

        RangeSlider slider = new RangeSlider(PALETTE_MIN, PALETTE_MAX, initialLow, initialHigh);
        slider.setShowTickMarks(true);
        slider.setShowTickLabels(true);
        slider.setMajorTickUnit(25);
        slider.setMinorTickCount(4);
        slider.setSnapToTicks(false);
        slider.setLabelFormatter(new StringConverter<Number>() {
            @Override public String toString(Number n) { return Integer.toString(n.intValue()); }
            @Override public Number fromString(String s) { return Integer.parseInt(s.trim()); }
        });
        FxControls.withTooltip(slider,
                "Drag the left handle to set the palette's coldest level (raise it to "
                + "see weak signals) and the right handle to set the highest level "
                + "(lower it to give bright peaks more contrast).");

        slider.lowValueProperty().addListener((obs, o, n) -> commit(slider));
        slider.highValueProperty().addListener((obs, o, n) -> commit(slider));

        Runnable syncFromModel = () -> Platform.runLater(() -> {
            int lo = clamp(start.getValue(), PALETTE_MIN, PALETTE_MAX - PALETTE_MIN_SIZE);
            int hi = clamp(lo + Math.max(PALETTE_MIN_SIZE, size.getValue()),
                    lo + PALETTE_MIN_SIZE, PALETTE_MAX);
            if ((int) slider.getLowValue() != lo) slider.setLowValue(lo);
            if ((int) slider.getHighValue() != hi) slider.setHighValue(hi);
        });
        start.addListener(syncFromModel);
        size.addListener(syncFromModel);

        return slider;
    }

    private void commit(RangeSlider slider) {
        int lo = snapToStep((int) Math.round(slider.getLowValue()), PALETTE_START_STEP);
        int hi = snapToStep((int) Math.round(slider.getHighValue()), PALETTE_SIZE_STEP);
        lo = clamp(lo, PALETTE_MIN, PALETTE_MAX - PALETTE_MIN_SIZE);
        if (hi - lo < PALETTE_MIN_SIZE) hi = lo + PALETTE_MIN_SIZE;
        hi = clamp(hi, lo + PALETTE_MIN_SIZE, PALETTE_MAX);
        int newSize = hi - lo;
        ModelValueInt startModel = settings.getSpectrumPaletteStart();
        ModelValueInt sizeModel = settings.getSpectrumPaletteSize();
        if (startModel.getValue() != lo) startModel.setValue(lo);
        if (sizeModel.getValue() != newSize) sizeModel.setValue(newSize);
    }

    private Node paletteReadout() {
        Label readout = new Label();
        readout.getStyleClass().add("palette-readout");
        Runnable update = () -> Platform.runLater(() -> {
            int lo = settings.getSpectrumPaletteStart().getValue();
            int sz = settings.getSpectrumPaletteSize().getValue();
            readout.setText(String.format("From %d dBm to %d dBm  (\u0394 %d dB)", lo, lo + sz, sz));
        });
        settings.getSpectrumPaletteStart().addListener(update);
        settings.getSpectrumPaletteSize().addListener(update);
        update.run();
        HBox row = new HBox(readout);
        return row;
    }

    private static int snapToStep(int v, int step) {
        return Math.round(v / (float) step) * step;
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static VBox labeled(String caption, Node control) {
        VBox box = new VBox(2, new Label(caption), control);
        HBox.setHgrow(control, Priority.ALWAYS);
        return box;
    }
}
