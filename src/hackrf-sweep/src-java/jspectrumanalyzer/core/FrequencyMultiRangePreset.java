package jspectrumanalyzer.core;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Curated catalogue of multi-range frequency plans the user can pick from a
 * single combo. New entries can be added here without touching any UI code.
 *
 * <p>Each preset bundles a human-readable name with a {@link FrequencyPlan}.
 * The first entry is always the "Off" sentinel ({@link #plan} == null) which
 * means "use the legacy single-range field instead".
 */
public final class FrequencyMultiRangePreset {

    /** Sentinel "no multi-range" entry; selecting it clears the active plan. */
    public static final FrequencyMultiRangePreset OFF =
            new FrequencyMultiRangePreset("Off (single range)", null);

    private final String name;
    private final FrequencyPlan plan;

    public FrequencyMultiRangePreset(String name, FrequencyPlan plan) {
        this.name = name;
        this.plan = plan;
    }

    public String getName() {
        return name;
    }

    /** Plan for this preset, or {@code null} for the {@link #OFF} sentinel. */
    public FrequencyPlan getPlan() {
        return plan;
    }

    @Override
    public String toString() {
        return name;
    }

    /**
     * Built-in catalogue of useful multi-range scans. Wi-Fi 2.4 + 5 + 6E
     * combines the three regulatory Wi-Fi sub-bands into one chart with the
     * dead air between them stripped out, which is what "Variant A" of the
     * stitched-axis design was sized for.
     */
    public static List<FrequencyMultiRangePreset> defaults() {
        return Collections.unmodifiableList(Arrays.asList(
                OFF,
                new FrequencyMultiRangePreset(
                        "Wi-Fi 2.4 + 5 + 6E",
                        new FrequencyPlan(Arrays.asList(
                                new FrequencyRange(2400, 2500),
                                new FrequencyRange(5150, 5895),
                                new FrequencyRange(5925, 7125))))));
    }
}
