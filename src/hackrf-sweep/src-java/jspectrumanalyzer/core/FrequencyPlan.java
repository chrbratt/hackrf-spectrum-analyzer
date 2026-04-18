package jspectrumanalyzer.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Ordered, non-overlapping list of {@link FrequencyRange} segments that the
 * sweep will cover. Forms the bridge between "RF MHz" (the actual radio
 * frequency a sample lives at) and "logical MHz" (its position on the
 * stitched chart axis after gap regions between segments have been removed).
 *
 * <p>Single-segment plans are the normal case and behave identically to the
 * legacy single-range engine: logical MHz == RF MHz, no gaps, no separator
 * lines.
 *
 * <p>The class is immutable and value-equal so it can be parked in a
 * {@code ModelValue} without surprising subscribers.
 */
public final class FrequencyPlan {

    /**
     * Segments in ascending start order. Validated at construction time:
     * sorted, non-empty, no overlap, all widths {@code > 0}.
     */
    private final List<FrequencyRange> segments;

    /** Cumulative logical start of each segment (logical[0] == 0). */
    private final int[] logicalStartMHz;

    /** Sum of all segment widths in MHz. */
    private final int totalLogicalSpanMHz;

    public FrequencyPlan(List<FrequencyRange> segments) {
        Objects.requireNonNull(segments, "segments");
        if (segments.isEmpty()) {
            throw new IllegalArgumentException("FrequencyPlan needs at least one segment");
        }
        List<FrequencyRange> sorted = new ArrayList<>(segments);
        sorted.sort((a, b) -> Integer.compare(a.getStartMHz(), b.getStartMHz()));
        for (int i = 0; i < sorted.size(); i++) {
            FrequencyRange seg = sorted.get(i);
            if (seg.getEndMHz() <= seg.getStartMHz()) {
                throw new IllegalArgumentException(
                        "segment " + i + " has non-positive width: "
                                + seg.getStartMHz() + ".." + seg.getEndMHz());
            }
            if (i > 0 && seg.getStartMHz() < sorted.get(i - 1).getEndMHz()) {
                throw new IllegalArgumentException(
                        "segments overlap: " + sorted.get(i - 1).getStartMHz()
                                + ".." + sorted.get(i - 1).getEndMHz()
                                + " and " + seg.getStartMHz() + ".." + seg.getEndMHz());
            }
        }
        this.segments = Collections.unmodifiableList(sorted);
        this.logicalStartMHz = new int[sorted.size()];
        int cumulative = 0;
        for (int i = 0; i < sorted.size(); i++) {
            logicalStartMHz[i] = cumulative;
            cumulative += sorted.get(i).getEndMHz() - sorted.get(i).getStartMHz();
        }
        this.totalLogicalSpanMHz = cumulative;
    }

    /** Convenience constructor for the single-segment case. */
    public static FrequencyPlan single(FrequencyRange range) {
        return new FrequencyPlan(Collections.singletonList(range));
    }

    /** Convenience constructor for the single-segment case. */
    public static FrequencyPlan single(int startMHz, int endMHz) {
        return single(new FrequencyRange(startMHz, endMHz));
    }

    public List<FrequencyRange> segments() {
        return segments;
    }

    public int segmentCount() {
        return segments.size();
    }

    public boolean isMultiSegment() {
        return segments.size() > 1;
    }

    /** First segment's start (= the sweep's lowest frequency). */
    public int firstStartMHz() {
        return segments.get(0).getStartMHz();
    }

    /** Last segment's end (= the sweep's highest frequency). */
    public int lastEndMHz() {
        return segments.get(segments.size() - 1).getEndMHz();
    }

    /** Sum of widths in MHz - the length of the stitched x-axis. */
    public int totalLogicalSpanMHz() {
        return totalLogicalSpanMHz;
    }

    /** Logical start (in stitched MHz) of segment {@code i}. */
    public int logicalStartMHz(int segmentIndex) {
        return logicalStartMHz[segmentIndex];
    }

    /**
     * Map an RF frequency in Hz to its position on the stitched logical axis,
     * in MHz. Returns {@link Double#NaN} if the frequency falls in a gap
     * between segments (or outside the plan entirely).
     */
    public double rfHzToLogicalMHz(double rfHz) {
        double rfMHz = rfHz / 1_000_000d;
        return rfMHzToLogicalMHz(rfMHz);
    }

    public double rfMHzToLogicalMHz(double rfMHz) {
        // Half-open in the interior, fully inclusive at the very last segment
        // so the closing tick (e.g. 7125 MHz) sits at the right edge.
        // Boundary points where seg[i].end == seg[i+1].start (touching, no
        // actual gap) get claimed by segment i first - both produce the same
        // logical value so this is idempotent for chart positioning.
        for (int i = 0; i < segments.size(); i++) {
            FrequencyRange seg = segments.get(i);
            boolean lastSegment = (i == segments.size() - 1);
            boolean upperOk = lastSegment
                    ? (rfMHz <= seg.getEndMHz())
                    : (rfMHz < seg.getEndMHz());
            if (rfMHz >= seg.getStartMHz() && upperOk) {
                return logicalStartMHz[i] + (rfMHz - seg.getStartMHz());
            }
            // Special case: a sample sitting exactly at this (non-last)
            // segment's end belongs to *this* segment's logical span, not the
            // gap that follows. Without this branch the round-trip
            // rf->logical->rf would lose the right edge of every interior
            // segment.
            if (!lastSegment && rfMHz == seg.getEndMHz()) {
                return logicalStartMHz[i] + (rfMHz - seg.getStartMHz());
            }
        }
        return Double.NaN;
    }

    /**
     * Inverse of {@link #rfMHzToLogicalMHz(double)}. Logical MHz must be
     * within {@code [0, totalLogicalSpanMHz]} - logical positions never fall
     * in a gap by construction. Out-of-range input clamps to the nearest
     * segment boundary.
     */
    public double logicalMHzToRfMHz(double logicalMHz) {
        if (logicalMHz <= 0) return firstStartMHz();
        if (logicalMHz >= totalLogicalSpanMHz) return lastEndMHz();
        for (int i = 0; i < segments.size(); i++) {
            int segLogicalStart = logicalStartMHz[i];
            int segWidth = segments.get(i).getEndMHz() - segments.get(i).getStartMHz();
            if (logicalMHz <= segLogicalStart + segWidth) {
                return segments.get(i).getStartMHz() + (logicalMHz - segLogicalStart);
            }
        }
        return lastEndMHz(); // unreachable - guarded above
    }

    /** Returns true if the supplied RF MHz falls within any segment. */
    public boolean contains(double rfMHz) {
        return !Double.isNaN(rfMHzToLogicalMHz(rfMHz));
    }

    /**
     * Total number of FFT bins needed for this plan at the given bin width.
     * Each segment contributes {@code ceil(widthHz / binHz)} bins; bins are
     * NOT allocated for gap regions.
     */
    public int totalBinCount(double binWidthHz) {
        long total = 0;
        for (FrequencyRange seg : segments) {
            double widthHz = (seg.getEndMHz() - seg.getStartMHz()) * 1_000_000d;
            total += (long) Math.ceil(widthHz / binWidthHz);
        }
        return (int) total;
    }

    /**
     * Map a sequential global bin index (across all segments) to its actual
     * RF frequency in MHz. Used by the dataset to compute peak-marker labels
     * after the chart x-axis has been stitched.
     */
    public double globalBinToRfMHz(int globalBin, double binWidthHz) {
        if (globalBin < 0) return firstStartMHz();
        double binWidthMHz = binWidthHz / 1_000_000d;
        int cumulative = 0;
        for (FrequencyRange seg : segments) {
            int binsInSeg = (int) Math.ceil(
                    (seg.getEndMHz() - seg.getStartMHz()) * 1_000_000d / binWidthHz);
            if (globalBin < cumulative + binsInSeg) {
                int offsetBin = globalBin - cumulative;
                return seg.getStartMHz() + offsetBin * binWidthMHz;
            }
            cumulative += binsInSeg;
        }
        return lastEndMHz();
    }

    /**
     * Map an RF frequency (Hz) to its global bin index in the stitched
     * dataset. Returns -1 if the frequency falls in a gap.
     */
    public int rfHzToGlobalBin(double rfHz, double binWidthHz) {
        double rfMHz = rfHz / 1_000_000d;
        int cumulative = 0;
        for (FrequencyRange seg : segments) {
            int binsInSeg = (int) Math.ceil(
                    (seg.getEndMHz() - seg.getStartMHz()) * 1_000_000d / binWidthHz);
            if (rfMHz >= seg.getStartMHz() && rfMHz < seg.getEndMHz()) {
                double offsetHz = rfHz - seg.getStartMHz() * 1_000_000d;
                int offsetBin = (int) (offsetHz / binWidthHz);
                if (offsetBin < 0) offsetBin = 0;
                if (offsetBin >= binsInSeg) offsetBin = binsInSeg - 1;
                return cumulative + offsetBin;
            }
            cumulative += binsInSeg;
        }
        return -1;
    }

    /** Pack segment endpoints as {@code [s0, e0, s1, e1, ...]} for native ABI. */
    public short[] toNativePairs() {
        short[] pairs = new short[segments.size() * 2];
        for (int i = 0; i < segments.size(); i++) {
            pairs[2 * i] = (short) segments.get(i).getStartMHz();
            pairs[2 * i + 1] = (short) segments.get(i).getEndMHz();
        }
        return pairs;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof FrequencyPlan)) return false;
        FrequencyPlan other = (FrequencyPlan) o;
        if (segments.size() != other.segments.size()) return false;
        for (int i = 0; i < segments.size(); i++) {
            if (!segments.get(i).equals(other.segments.get(i))) return false;
        }
        return true;
    }

    @Override
    public int hashCode() {
        int h = 1;
        for (FrequencyRange seg : segments) {
            h = 31 * h + seg.getStartMHz();
            h = 31 * h + seg.getEndMHz();
        }
        return Arrays.hashCode(logicalStartMHz) ^ h;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("FrequencyPlan[");
        for (int i = 0; i < segments.size(); i++) {
            if (i > 0) sb.append(", ");
            FrequencyRange seg = segments.get(i);
            sb.append(seg.getStartMHz()).append("-").append(seg.getEndMHz()).append(" MHz");
        }
        sb.append(']');
        return sb.toString();
    }
}
