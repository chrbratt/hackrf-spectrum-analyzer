package jspectrumanalyzer.fx.frequency;

import jspectrumanalyzer.core.FrequencyRange;

/**
 * Pure validation and clamping logic for a frequency range in MHz.
 * <p>
 * Replaces the {@code VetoableChangeListener}-based cross-mutation in
 * {@code FrequencySelectorRangeBinder}. The old approach allowed the UI and the model
 * to fall out of sync whenever a veto fired; this class instead <em>coerces</em> the
 * requested range to the valid space and returns the coerced result so the UI can show
 * what was actually accepted.
 */
public final class FrequencyRangeValidator {

    public static final int MIN_SPAN_MHZ = 1;

    private final int minMHz;
    private final int maxMHz;

    public FrequencyRangeValidator(int minMHz, int maxMHz) {
        if (minMHz >= maxMHz) {
            throw new IllegalArgumentException("minMHz must be < maxMHz");
        }
        this.minMHz = minMHz;
        this.maxMHz = maxMHz;
    }

    public int getMinMHz() {
        return minMHz;
    }

    public int getMaxMHz() {
        return maxMHz;
    }

    /**
     * Coerce the requested range into the valid space while preserving the caller's
     * intent as much as possible. If the caller's start/end cross each other, the start
     * wins (the end is pushed to {@code start + MIN_SPAN_MHZ}).
     */
    public FrequencyRange coerce(int requestedStart, int requestedEnd) {
        int start = clamp(requestedStart, minMHz, maxMHz - MIN_SPAN_MHZ);
        int end = clamp(requestedEnd, start + MIN_SPAN_MHZ, maxMHz);
        return new FrequencyRange(start, end);
    }

    /**
     * Coerce but respect which endpoint the user is actively editing. If the user edits
     * {@code end} and pushes it below {@code start}, pull {@code start} down instead of
     * snapping {@code end} back up.
     */
    public FrequencyRange coerceRespecting(int currentStart, int currentEnd,
                                           int requestedStart, int requestedEnd,
                                           Endpoint pinned) {
        if (pinned == Endpoint.END) {
            int end = clamp(requestedEnd, minMHz + MIN_SPAN_MHZ, maxMHz);
            int start = clamp(requestedStart, minMHz, end - MIN_SPAN_MHZ);
            return new FrequencyRange(start, end);
        }
        return coerce(requestedStart, requestedEnd);
    }

    public boolean isValid(int start, int end) {
        return start >= minMHz && end <= maxMHz && (end - start) >= MIN_SPAN_MHZ;
    }

    private static int clamp(int value, int min, int max) {
        if (value < min) return min;
        if (value > max) return max;
        return value;
    }

    public enum Endpoint { START, END }
}
