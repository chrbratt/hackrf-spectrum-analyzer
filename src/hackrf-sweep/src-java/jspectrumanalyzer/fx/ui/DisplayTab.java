package jspectrumanalyzer.fx.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.VBox;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import jspectrumanalyzer.core.FrequencyAllocationTable;
import jspectrumanalyzer.core.FrequencyAllocations;
import jspectrumanalyzer.fx.chart.GraphTheme;
import jspectrumanalyzer.fx.model.SettingsStore;
import jspectrumanalyzer.fx.util.FxControls;
import jspectrumanalyzer.ui.WaterfallPalette;
import shared.mvc.ModelValue;

/**
 * Display tab: how the spectrum and waterfall look on screen.
 * <p>
 * The on/off toggles for waterfall and persistent display live in the
 * {@code ChartToolbar} above the chart, so this tab focuses purely on the
 * shape of those displays (waterfall scroll speed, palette range, persistence).
 */
public final class DisplayTab extends ScrollPane {

    private static final Logger LOG = LoggerFactory.getLogger(DisplayTab.class);

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
                                        + "1 = every 10th sweep (slow scroll, more averaging).")),
                        FxControls.withTooltip(
                                FxControls.checkBox("Funnel mode (compressed long-term history)",
                                        settings.isWaterfallFunnel()),
                                "Split the waterfall into 4 stacked tiers with strides "
                                + "1, 2, 4 and 8. The top tier shows live detail at one "
                                + "row per sweep; each tier below combines twice as many "
                                + "sweeps per row using max-hold (so short bursts still "
                                + "survive). Visible time stretches to ~3.75x the flat "
                                + "waterfall without any extra per-frame cost. Switching "
                                + "modes clears the existing scrollback.")),
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
                        FxControls.labeled("Country / table", buildAllocationCombo())),
                FxControls.section("Wi-Fi AP markers",
                        FxControls.withTooltip(
                                FxControls.checkBox("Show AP markers on chart",
                                        settings.isApMarkersVisible()),
                                "Draw a translucent box for every visible Wi-Fi access point at its centre "
                                + "frequency. Auto-enables when the Wi-Fi window opens; tick this to keep "
                                + "them visible after the window is closed."),
                        FxControls.labeled("Marker intensity (%)",
                                FxControls.withTooltip(
                                        FxControls.slider(settings.getApMarkerOpacity(), 5, 100),
                                        "Peak fill opacity of each marker box (5-100%). The box uses a "
                                        + "vertical gradient that fades from this value at the RSSI line "
                                        + "down to fully transparent at the chart baseline, so weaker APs "
                                        + "look fainter than strong ones. Lower the value for a subtler "
                                        + "overlay, raise it to make markers pop."))));
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
     * its {@link FrequencyAllocationTable} in the model AND auto-enables the
     * overlay - the previous behaviour required the user to also tick "Show
     * overlay", which made it look like the country picker did nothing.
     * Selecting "(None)" clears the table without touching the visibility
     * flag so users can hide the overlay temporarily without losing the
     * country selection.
     */
    private ComboBox<NamedTable> buildAllocationCombo() {
        ComboBox<NamedTable> combo = new ComboBox<>();
        combo.getItems().add(NamedTable.NONE);
        combo.getItems().addAll(loadTables());
        combo.setMaxWidth(Double.MAX_VALUE);
        FxControls.withTooltip(combo,
                "Country / region whose allocation bands will be drawn on the chart. "
                + "Picking a country auto-enables the overlay above. "
                + "Add your own CSV under src/hackrf-sweep/freq.");

        FrequencyAllocationTable current = settings.getFrequencyAllocationTable().getValue();
        if (current != null) {
            for (NamedTable nt : combo.getItems()) {
                if (nt.table == current) {
                    combo.getSelectionModel().select(nt);
                    break;
                }
            }
        } else {
            combo.getSelectionModel().select(NamedTable.NONE);
        }
        combo.valueProperty().addListener((obs, o, n) -> {
            FrequencyAllocationTable table = (n == null) ? null : n.table;
            settings.getFrequencyAllocationTable().setValue(table);
            // Auto-enable when a real table is picked so the user doesn't
            // have to hunt for the checkbox above. A non-null table with the
            // overlay off was the #1 source of "country picker does nothing"
            // confusion.
            if (table != null && !settings.isFrequencyAllocationVisible().getValue()) {
                settings.isFrequencyAllocationVisible().setValue(true);
            }
        });
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
        // Sort case-insensitively so the dropdown reads alphabetically
        // regardless of file-system collation. NONE stays at the top via
        // the explicit prepend in the caller.
        result.sort((a, b) -> a.label.compareToIgnoreCase(b.label));
        return result;
    }

    /**
     * Strip the .csv extension and any "x " / "- " sort-order prefixes that
     * crept into legacy file names so the combo shows clean country names.
     * Also appends the band count so users can spot empty / partial tables
     * at a glance.
     */
    private static String prettify(String csvFileName) {
        String s = csvFileName;
        if (s.toLowerCase().endsWith(".csv")) s = s.substring(0, s.length() - 4);
        s = s.trim();
        // "x USA" / "x Europe": leading "x " was used to push the file to
        // the bottom of an alphabetical sort. Drop it; the combo now sorts
        // case-insensitively in code.
        if (s.length() > 2 && (s.startsWith("x ") || s.startsWith("X "))) {
            s = s.substring(2).trim();
        }
        // "- Slovakia": leading "- " was used to push the file to the top.
        if (s.startsWith("- ") || s.startsWith("\u2013 ")) {
            s = s.substring(2).trim();
        }
        return s;
    }

    private static final class NamedTable {
        /** Sentinel used by the combo to mean "no allocation table loaded". */
        static final NamedTable NONE = new NamedTable("(None)", null);

        final String label;
        final FrequencyAllocationTable table;

        NamedTable(String label, FrequencyAllocationTable table) {
            this.label = label;
            this.table = table;
        }

        @Override
        public String toString() {
            if (table == null) return label;
            return label + "  (" + table.size() + " bands)";
        }
    }

}
