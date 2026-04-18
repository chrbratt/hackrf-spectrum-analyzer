package jspectrumanalyzer.core;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests for the spur removal pipeline.
 * <p>
 * The full filter has two phases: <em>calibration</em> (averages
 * {@code validIterations} sweeps to learn where consistent spur lines
 * sit) and <em>filtering</em> (subtracts the learned correction from new
 * sweeps). These tests drive both with synthetic spectra so we can
 * assert the calibration outcome and the filter math without running
 * a real radio.
 */
class SpurFilterTest {

    private static final float BIN_HZ = 1_000_000f;

    /** Build a dataset large enough that maxPeakBins doesn't eat the whole array. */
    private static DatasetSpectrum newInput(int bins) {
        return new DatasetSpectrum(BIN_HZ,
                FrequencyPlan.single(2400, 2400 + bins),
                /* spectrumInitPower */ -100f,
                /* freqShift         */ 0);
    }

    private static SpurFilter newFilter(DatasetSpectrum input,
                                        int validIterations,
                                        float peakThresholdAboveNoise) {
        return new SpurFilter(/* maxPeakJitterdB */ 6f,
                peakThresholdAboveNoise,
                /* maxPeakBins      */ 4,
                validIterations,
                input);
    }

    @Test
    @DisplayName("filter is not calibrated until validIterations sweeps are seen")
    void calibrationRequiresEnoughSweeps() {
        DatasetSpectrum input = newInput(64);
        SpurFilter filter = newFilter(input, /* iterations */ 5, /* threshold */ 4f);

        for (int i = 0; i < 4; i++) {
            // Each filterDataset call before calibration completes is a
            // calibration accumulation step, not a filter pass.
            filter.filterDataset();
            assertFalse(filter.isFilterCalibrated(),
                    "filter declared calibrated after only " + (i + 1) + " sweeps");
        }
        filter.filterDataset();
        assertTrue(filter.isFilterCalibrated(),
                "filter should be calibrated after the 5th sweep");
    }

    @Test
    @DisplayName("recalibrate() forces another full calibration cycle")
    void recalibrateClearsState() {
        DatasetSpectrum input = newInput(64);
        SpurFilter filter = newFilter(input, 3, 4f);
        for (int i = 0; i < 3; i++) filter.filterDataset();
        assertTrue(filter.isFilterCalibrated());

        filter.recalibrate();
        assertFalse(filter.isFilterCalibrated());
    }

    @Test
    @DisplayName("after calibration, a stable spur is subtracted from the live spectrum")
    void filterRemovesStableSpur() {
        DatasetSpectrum input = newInput(64);
        // Plant a clear, consistent spur in bin 32: 20 dB above the rest.
        // Calibration runs 5 sweeps over the same shape so the filter
        // should learn to flatten it.
        final int spurBin = 32;
        final float noise = -90f;
        final float spur  = -70f;

        SpurFilter filter = newFilter(input, /* iterations */ 5, /* threshold */ 4f);
        for (int i = 0; i < 5; i++) {
            float[] s = input.getSpectrumArray();
            for (int b = 0; b < s.length; b++) s[b] = noise;
            s[spurBin] = spur;
            filter.filterDataset();
        }
        assertTrue(filter.isFilterCalibrated());

        // Now run a real filtering pass.
        float[] live = input.getSpectrumArray();
        for (int b = 0; b < live.length; b++) live[b] = noise;
        live[spurBin] = spur;
        float spurBefore = live[spurBin];

        filter.filterDataset();

        // Bin should be pulled back close to the noise floor; we don't
        // care about the exact dB value (it depends on the EMA noise
        // model) but we do require that the filter actually moved it
        // significantly toward the noise floor.
        float spurAfter = live[spurBin];
        assertTrue(spurAfter < spurBefore - 5f,
                "spur bin not corrected: before=" + spurBefore + " after=" + spurAfter);
    }
}
