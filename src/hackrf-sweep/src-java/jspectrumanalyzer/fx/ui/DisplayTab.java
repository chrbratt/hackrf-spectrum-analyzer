package jspectrumanalyzer.fx.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.util.StringConverter;
import org.controlsfx.control.RangeSlider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import jspectrumanalyzer.core.FrequencyAllocationTable;
import jspectrumanalyzer.core.FrequencyAllocations;
import jspectrumanalyzer.fx.chart.GraphTheme;
import jspectrumanalyzer.fx.model.SettingsStore;
import jspectrumanalyzer.fx.util.FxControls;
import jspectrumanalyzer.ui.WaterfallPalette;
import shared.mvc.ModelValue;
import shared.mvc.ModelValue.ModelValueInt;

/**
 * Display tab: how the spectrum and waterfall look on screen.
 * <p>
 * The on/off toggles for waterfall and persistent display live in the
 * {@code ChartToolbar} above the chart, so this tab focuses purely on the
 * shape of those displays (waterfall scroll speed, palette range, persistence).
 */
public final class DisplayTab extends ScrollPane {

    private static final Logger LOG = LoggerFactory.getLogger(DisplayTab.class);

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
                FxControls.section("Theme",
                        FxControls.labeled("Graph theme",
                                FxControls.withTooltip(
                                        buildThemeCombo(GraphTheme.values(), settings.getGraphTheme()),
                                        "Visual style for the spectrum chart. Try Heatmap if you want max-hold "
                                        + "to visibly cool down as it ages, or High Contrast for screenshots.")),
                        FxControls.labeled("Waterfall theme",
                                FxControls.withTooltip(
                                        buildThemeCombo(WaterfallPalette.values(), settings.getWaterfallTheme()),
                                        "Colour ramp used by the waterfall display. Viridis is the most "
                                        + "perceptually uniform; Hot Iron Blue matches earlier builds."))),
                FxControls.section("Waterfall",
                        FxControls.labeled("Speed (1 = slow, 10 = fast)",
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
                        FxControls.labeled("Persistence time (s)",
                                FxControls.withTooltip(
                                        FxControls.intSpinner(settings.getPersistentDisplayDecayRate(), 1, 60, 1),
                                        "Seconds an arriving sample stays visible in the persistent overlay "
                                        + "before fully decaying. Larger = longer trails."))),
                FxControls.section("Frequency allocation overlay",
                        FxControls.withTooltip(
                                FxControls.checkBox("Show overlay", settings.isFrequencyAllocationVisible()),
                                "Paint the colored allocation bands (e.g. Wi-Fi, GSM, FM radio) over the "
                                + "spectrum so it's obvious which service each peak belongs to. The bands "
                                + "come from the country file selected below."),
                        FxControls.labeled("Country / table", buildAllocationCombo())));
        setContent(content);
    }

    /**
     * Two-way bind a {@link ComboBox} to a {@link ModelValue} of an enum-like
     * type. The combo's items are the supplied options (typically the result
     * of {@code MyEnum.values()}); each entry's {@code toString()} is what
     * shows up to the user.
     *
     * <p>External writes to the model (e.g. via a future preset that flips
     * the theme) are reflected in the combo via a listener; combo writes
     * push back to the model. The {@code selectingFromModel} guard avoids a
     * listener loop when the change originated in the model.
     */
    private static <T> ComboBox<T> buildThemeCombo(T[] options, ModelValue<T> model) {
        ComboBox<T> combo = new ComboBox<>();
        combo.getItems().addAll(options);
        combo.setMaxWidth(Double.MAX_VALUE);
        combo.getSelectionModel().select(model.getValue());

        boolean[] selectingFromModel = { false };
        combo.valueProperty().addListener((obs, o, n) -> {
            if (selectingFromModel[0]) return;
            if (n != null) model.setValue(n);
        });
        model.addListener(() -> Platform.runLater(() -> {
            T value = model.getValue();
            if (combo.getValue() != value) {
                selectingFromModel[0] = true;
                try { combo.getSelectionModel().select(value); }
                finally { selectingFromModel[0] = false; }
            }
        }));
        return combo;
    }

    /**
     * ComboBox of every CSV under {@code freq/}. Picking an entry installs
     * its {@link FrequencyAllocationTable} in the model; the overlay canvas
     * picks the change up via its own listener and repaints. The list is
     * loaded lazily so a missing index file doesn't crash the tab.
     */
    private ComboBox<NamedTable> buildAllocationCombo() {
        ComboBox<NamedTable> combo = new ComboBox<>();
        combo.getItems().addAll(loadTables());
        combo.setMaxWidth(Double.MAX_VALUE);
        FxControls.withTooltip(combo,
                "Country / region whose allocation bands will be drawn on the chart "
                + "when the overlay is enabled. Add your own CSV under src/hackrf-sweep/freq.");

        FrequencyAllocationTable current = settings.getFrequencyAllocationTable().getValue();
        if (current != null) {
            for (NamedTable nt : combo.getItems()) {
                if (nt.table == current) {
                    combo.getSelectionModel().select(nt);
                    break;
                }
            }
        }
        combo.valueProperty().addListener((obs, o, n) ->
                settings.getFrequencyAllocationTable().setValue(n == null ? null : n.table));
        return combo;
    }

    private static List<NamedTable> loadTables() {
        List<NamedTable> result = new ArrayList<>();
        try {
            Map<String, FrequencyAllocationTable> all = new FrequencyAllocations().getTable();
            for (Map.Entry<String, FrequencyAllocationTable> e : all.entrySet()) {
                result.add(new NamedTable(prettify(e.getKey()), e.getValue()));
            }
        } catch (Exception ex) {
            LOG.warn("Could not load allocation tables", ex);
        }
        return result;
    }

    private static String prettify(String csvFileName) {
        String s = csvFileName;
        if (s.toLowerCase().endsWith(".csv")) s = s.substring(0, s.length() - 4);
        return s.trim();
    }

    private static final class NamedTable {
        final String label;
        final FrequencyAllocationTable table;

        NamedTable(String label, FrequencyAllocationTable table) {
            this.label = label;
            this.table = table;
        }

        @Override
        public String toString() { return label; }
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
}
