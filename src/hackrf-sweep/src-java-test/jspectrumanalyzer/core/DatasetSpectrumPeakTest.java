package jspectrumanalyzer.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Pin-down tests for the peak / max-hold / average pipeline.
 * <p>
 * The processing thread runs these methods up to 30 times per second on
 * arrays of 15 000+ bins, so silent off-by-one behaviour or a regression
 * in the EMA fall-out math is invisible in the UI but accumulates over
 * a long capture. These tests use a tiny 4-bin dataset so the expected
 * values can be reasoned about by hand.
 */
class DatasetSpectrumPeakTest {

    /** 1 MHz bins across 2400-2404 MHz -> exactly 4 bins. */
    private static final float BIN_HZ = 1_000_000f;
    private static final int START_MHZ = 2400;
    private static final int STOP_MHZ  = 2404;
    private static final float INIT    = -150f;

    private DatasetSpectrumPeak newDataset() {
        return new DatasetSpectrumPeak(
                BIN_HZ, START_MHZ, STOP_MHZ,
                INIT,
                /* peakFallThreshold */ 5f,
                /* peakFalloutMillis */ 1000L,
                /* peakHoldMillis    */ 0L,
                /* freqShift         */ 0,
                /* avgIterations     */ 4,
                /* avgOffset         */ 0);
    }

    private static void writeSpectrum(DatasetSpectrumPeak ds, float... values) {
        float[] s = ds.getSpectrumArray();
        assertEquals(s.length, values.length, "test wrote wrong number of bins");
        System.arraycopy(values, 0, s, 0, values.length);
    }

    @Test
    @DisplayName("max-hold latches the highest value seen per bin")
    void maxHoldLatchesPeak() {
        DatasetSpectrumPeak ds = newDataset();
        writeSpectrum(ds, -80, -70, -60, -90);
        ds.refreshMaxHoldSpectrum();
        // The new sample beats the init power floor, so all four bins
        // should now hold their first sample.
        assertEquals(-80f, ds.spectrumMaxHold[0]);
        assertEquals(-70f, ds.spectrumMaxHold[1]);
        assertEquals(-60f, ds.spectrumMaxHold[2]);
        assertEquals(-90f, ds.spectrumMaxHold[3]);

        // A weaker sample must NOT lower the held value (that's the
        // whole point of "hold"); a stronger sample must raise it.
        writeSpectrum(ds, -100, -50, -60, -85);
        ds.refreshMaxHoldSpectrum();
        assertEquals(-80f, ds.spectrumMaxHold[0], "weaker sample reduced max-hold");
        assertEquals(-50f, ds.spectrumMaxHold[1], "stronger sample didn't raise max-hold");
        assertEquals(-60f, ds.spectrumMaxHold[2]);
        assertEquals(-85f, ds.spectrumMaxHold[3]);
    }

    @Test
    @DisplayName("max-hold fall-out drops aged peaks back to live sample")
    void maxHoldFalloutResetsAgedBins() throws InterruptedException {
        DatasetSpectrumPeak ds = newDataset();
        ds.setMaxHoldFalloutMillis(50);

        writeSpectrum(ds, -50, -50, -50, -50);
        ds.refreshMaxHoldSpectrum();
        assertEquals(-50f, ds.spectrumMaxHold[0]);

        Thread.sleep(80); // wait past the fall-out window

        // Live sample is now weaker than the held value AND the bin has
        // aged out -> the held value must drop back to the live sample,
        // exactly so old peaks stop painting the trace forever.
        writeSpectrum(ds, -90, -90, -90, -90);
        ds.refreshMaxHoldSpectrum();
        for (int i = 0; i < 4; i++) {
            assertEquals(-90f, ds.spectrumMaxHold[i],
                    "aged max-hold bin " + i + " was not reset to live sample");
        }
    }

    @Test
    @DisplayName("max-hold without fall-out keeps legacy infinite-hold behaviour")
    void maxHoldZeroFalloutKeepsForever() throws InterruptedException {
        DatasetSpectrumPeak ds = newDataset();
        ds.setMaxHoldFalloutMillis(0); // 0 == disabled

        writeSpectrum(ds, -50, -50, -50, -50);
        ds.refreshMaxHoldSpectrum();
        Thread.sleep(50);
        writeSpectrum(ds, -90, -90, -90, -90);
        ds.refreshMaxHoldSpectrum();

        for (int i = 0; i < 4; i++) {
            assertEquals(-50f, ds.spectrumMaxHold[i],
                    "fall-out=0 must keep the legacy infinite-hold behaviour");
        }
    }

    @Test
    @DisplayName("resetMaxHold returns every bin to spectrumInitPower")
    void resetMaxHoldClearsState() {
        DatasetSpectrumPeak ds = newDataset();
        writeSpectrum(ds, -50, -50, -50, -50);
        ds.refreshMaxHoldSpectrum();

        ds.resetMaxHold();
        for (int i = 0; i < 4; i++) {
            assertEquals(INIT, ds.spectrumMaxHold[i]);
        }
    }

    @Test
    @DisplayName("snapshotForChart only copies traces the chart asked for")
    void snapshotIsSelective() {
        DatasetSpectrumPeak ds = newDataset();
        writeSpectrum(ds, -80, -70, -60, -90);
        ds.refreshMaxHoldSpectrum();
        // Average-trace state isn't populated yet, so its snapshot must
        // remain at the init power (proving we didn't accidentally copy
        // unrelated buffers).
        ds.snapshotForChart(/* peaks */ false,
                /* average */ false,
                /* maxHold */ true,
                /* realtime */ false);

        assertEquals(-60f, ds.maxHoldSnapshot[2]);
        assertEquals(INIT, ds.averageSnapshot[2],
                "average snapshot was overwritten despite average=false");
    }

    @Test
    @DisplayName("peak-hold rises with a stronger sample and falls below threshold")
    void peakHoldFallThreshold() throws InterruptedException {
        DatasetSpectrumPeak ds = newDataset();

        writeSpectrum(ds, -50, -150, -150, -150);
        ds.refreshPeakSpectrum();
        assertEquals(-50f, ds.spectrumPeakHold[0]);

        // Sleep long enough for the EMA decay to drag spectrumPeak below
        // the hold by more than the fall-threshold (5 dB).
        for (int i = 0; i < 5; i++) {
            Thread.sleep(120);
            writeSpectrum(ds, -100, -150, -150, -150);
            ds.refreshPeakSpectrum();
        }
        assertTrue(ds.spectrumPeakHold[0] < -50f,
                "peak-hold should have fallen toward the EMA peak; got " + ds.spectrumPeakHold[0]);
        assertNotEquals(-50f, ds.spectrumPeakHold[0]);
    }
}
