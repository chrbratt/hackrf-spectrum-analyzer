package jspectrumanalyzer.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Pin down the RF<->logical mapping that powers the stitched-axis chart.
 * If any of these break, peak-marker labels and segment separator drawing
 * silently misalign, so we cover the math here rather than via UI testing.
 */
class FrequencyPlanTest {

    private static final double BIN_HZ = 1_000_000d; // 1 MHz bins for round numbers

    @Test
    @DisplayName("single-segment plan behaves identically to a single FrequencyRange")
    void singleSegmentIsIdentity() {
        FrequencyPlan plan = FrequencyPlan.single(2400, 2483);
        assertFalse(plan.isMultiSegment());
        assertEquals(2400, plan.firstStartMHz());
        assertEquals(2483, plan.lastEndMHz());
        assertEquals(83, plan.totalLogicalSpanMHz());
        assertEquals(0d, plan.rfMHzToLogicalMHz(2400));
        assertEquals(40d, plan.rfMHzToLogicalMHz(2440));
        assertEquals(83d, plan.rfMHzToLogicalMHz(2483));
        assertEquals(2440d, plan.logicalMHzToRfMHz(40));
    }

    @Test
    @DisplayName("multi-segment plan stitches gaps out of the logical axis")
    void multiSegmentStitchesGaps() {
        // Wi-Fi 2.4 + Wi-Fi 5 + Wi-Fi 6E
        FrequencyPlan plan = new FrequencyPlan(Arrays.asList(
                new FrequencyRange(2400, 2483),
                new FrequencyRange(5120, 5880),
                new FrequencyRange(5925, 7125)));
        assertTrue(plan.isMultiSegment());
        assertEquals(83 + 760 + 1200, plan.totalLogicalSpanMHz());
        // Each segment lands at its cumulative start.
        assertEquals(0, plan.logicalStartMHz(0));
        assertEquals(83, plan.logicalStartMHz(1));
        assertEquals(83 + 760, plan.logicalStartMHz(2));
        // RF inside segments maps correctly.
        assertEquals(0d, plan.rfMHzToLogicalMHz(2400));
        assertEquals(83d, plan.rfMHzToLogicalMHz(5120));
        assertEquals(83d + 760d, plan.rfMHzToLogicalMHz(5925));
        // RF in a gap returns NaN so the dataset can drop the sample.
        assertTrue(Double.isNaN(plan.rfMHzToLogicalMHz(3500)));
        assertTrue(Double.isNaN(plan.rfMHzToLogicalMHz(5900)));
    }

    @Test
    @DisplayName("logicalMHzToRfMHz is the strict inverse for interior positions")
    void logicalToRfRoundtrips() {
        FrequencyPlan plan = new FrequencyPlan(Arrays.asList(
                new FrequencyRange(2400, 2483),
                new FrequencyRange(5925, 7125)));
        // Round-trip is well-defined for any RF that is NOT on a
        // discontinuity boundary (where segment[i].end and segment[i+1].start
        // both map to the same logical value, but logical->RF can only return
        // one of them by convention - the segment-end side wins).
        for (double rf : new double[]{2400, 2450, 5926, 6000, 7125}) {
            double logical = plan.rfMHzToLogicalMHz(rf);
            assertEquals(rf, plan.logicalMHzToRfMHz(logical), 1e-9, "round-trip RF " + rf);
        }
    }

    @Test
    @DisplayName("discontinuity boundaries collapse to a single logical position")
    void discontinuityBoundariesShareLogical() {
        FrequencyPlan plan = new FrequencyPlan(Arrays.asList(
                new FrequencyRange(2400, 2483),
                new FrequencyRange(5925, 7125)));
        // End of segment 0 and start of segment 1 land at the same logical
        // x. This is what makes the chart paint a clean vertical separator
        // line at exactly that pixel - we don't want the renderer to draw a
        // false 2483-MHz-wide connector across the gap.
        assertEquals(83d, plan.rfMHzToLogicalMHz(2483));
        assertEquals(83d, plan.rfMHzToLogicalMHz(5925));
    }

    @Test
    @DisplayName("totalBinCount sums per-segment bins with no gap allocation")
    void totalBinCountSkipsGaps() {
        FrequencyPlan plan = new FrequencyPlan(Arrays.asList(
                new FrequencyRange(2400, 2483),
                new FrequencyRange(5925, 7125)));
        // 83 MHz + 1200 MHz = 1283 bins at 1 MHz/bin.
        assertEquals(1283, plan.totalBinCount(BIN_HZ));
    }

    @Test
    @DisplayName("rfHzToGlobalBin returns -1 for samples in the gap")
    void rfToBinDropsGapSamples() {
        FrequencyPlan plan = new FrequencyPlan(Arrays.asList(
                new FrequencyRange(2400, 2483),
                new FrequencyRange(5925, 7125)));
        assertEquals(0, plan.rfHzToGlobalBin(2400_000_000d, BIN_HZ));
        assertEquals(82, plan.rfHzToGlobalBin(2482_000_000d, BIN_HZ));
        assertEquals(83, plan.rfHzToGlobalBin(5925_000_000d, BIN_HZ));
        assertEquals(-1, plan.rfHzToGlobalBin(3500_000_000d, BIN_HZ));
    }

    @Test
    @DisplayName("globalBinToRfMHz round-trips through rfHzToGlobalBin")
    void globalBinRoundTrip() {
        FrequencyPlan plan = new FrequencyPlan(Arrays.asList(
                new FrequencyRange(2400, 2483),
                new FrequencyRange(5925, 7125)));
        for (int bin : new int[]{0, 1, 82, 83, 84, 1282}) {
            double rfMHz = plan.globalBinToRfMHz(bin, BIN_HZ);
            int back = plan.rfHzToGlobalBin(rfMHz * 1_000_000d, BIN_HZ);
            assertEquals(bin, back, "round-trip bin " + bin);
        }
    }

    @Test
    @DisplayName("constructor rejects empty or overlapping segments")
    void constructorRejectsBadInput() {
        assertThrows(IllegalArgumentException.class,
                () -> new FrequencyPlan(java.util.Collections.emptyList()));
        assertThrows(IllegalArgumentException.class,
                () -> new FrequencyPlan(Arrays.asList(
                        new FrequencyRange(2400, 2500),
                        new FrequencyRange(2450, 2600)))); // overlap
        assertThrows(IllegalArgumentException.class,
                () -> new FrequencyPlan(java.util.Collections.singletonList(
                        new FrequencyRange(2400, 2400)))); // zero width
    }

    @Test
    @DisplayName("toNativePairs packs segment endpoints in libhackrf order")
    void nativePairsPacking() {
        FrequencyPlan plan = new FrequencyPlan(Arrays.asList(
                new FrequencyRange(2400, 2483),
                new FrequencyRange(5925, 7125)));
        short[] pairs = plan.toNativePairs();
        assertEquals(4, pairs.length);
        assertEquals(2400, pairs[0]);
        assertEquals(2483, pairs[1]);
        assertEquals(5925, pairs[2]);
        assertEquals(7125, pairs[3]);
    }
}
