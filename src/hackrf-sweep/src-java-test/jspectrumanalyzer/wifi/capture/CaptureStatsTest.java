package jspectrumanalyzer.wifi.capture;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class CaptureStatsTest {

    /**
     * Build a minimal beacon frame with the supplied BSSID. Frame
     * control byte 0x80 = type=mgmt, subtype=8 (beacon). MAC address
     * 3 (offset 16) carries the BSSID per IEEE 802.11. The rest of
     * the frame body is whatever bytes we want; CaptureStats only
     * looks at type/subtype + BSSID + length.
     */
    private static byte[] beaconFrame(String bssidHex, int padBytes) {
        return beaconFrame(bssidHex, padBytes, false);
    }

    /** Same as {@link #beaconFrame} but lets the test set the retry bit. */
    private static byte[] beaconFrame(String bssidHex, int padBytes, boolean retry) {
        byte[] mac = new byte[24 + padBytes];
        mac[0] = (byte) 0x80;
        if (retry) mac[1] = (byte) 0x08; // FC byte 1, retry bit
        for (int i = 0; i < 6; i++) {
            int v = Integer.parseInt(bssidHex.substring(i * 2, i * 2 + 2), 16);
            mac[16 + i] = (byte) v;
        }
        return mac;
    }

    private static byte[] probeRequestFrame() {
        byte[] mac = new byte[24];
        mac[0] = (byte) 0x40; // type=mgmt, subtype=4 (probe req)
        // Probe-req address 3 is broadcast; we leave it as 00s here -
        // the panel skips probe-req from per-BSSID tally regardless.
        return mac;
    }

    @Test
    void perBssidCountersAccumulateAcrossFrames() {
        CaptureStats stats = new CaptureStats();
        stats.accept(new RadiotapFrame(1_000, 2412, -45, 1,
                beaconFrame("aabbccddeeff", 100)));
        stats.accept(new RadiotapFrame(2_000, 2412, -50, 1,
                beaconFrame("aabbccddeeff", 100)));
        stats.accept(new RadiotapFrame(3_000, 2412, -60, 1,
                beaconFrame("112233445566", 50)));

        CaptureStats.Snapshot snap = stats.snapshot();
        assertEquals(3, snap.total());
        assertEquals(3, snap.mgmt());
        assertEquals(3, snap.beacons());
        assertEquals(2, snap.byBssid().size());

        CaptureStats.BssidStat top = snap.byBssid().get("aa:bb:cc:dd:ee:ff");
        assertEquals(2, top.frames());
        // RSSI tracks the latest non-zero value.
        assertEquals(-50, top.lastRssiDbm());
    }

    @Test
    void rssiZeroDoesNotPreventBssidTallying() {
        // Bug fix: previously per-BSSID stats were gated on rssi != 0,
        // which silently zeroed out the top-talkers list on adapters
        // (e.g. NETGEAR A6210) whose radiotap header omits the
        // antenna-signal field.
        CaptureStats stats = new CaptureStats();
        stats.accept(new RadiotapFrame(1_000, 2412, 0, 1,
                beaconFrame("aabbccddeeff", 100)));
        CaptureStats.Snapshot snap = stats.snapshot();
        assertEquals(1, snap.byBssid().size());
        assertEquals(1, snap.byBssid().get("aa:bb:cc:dd:ee:ff").frames());
        assertEquals(0, snap.byBssid().get("aa:bb:cc:dd:ee:ff").lastRssiDbm());
    }

    @Test
    void probeRequestsExcludedFromTopTalkers() {
        CaptureStats stats = new CaptureStats();
        stats.accept(new RadiotapFrame(1_000, 2412, -50, 1, probeRequestFrame()));
        stats.accept(new RadiotapFrame(2_000, 2412, -50, 1, probeRequestFrame()));
        CaptureStats.Snapshot snap = stats.snapshot();
        assertEquals(2, snap.probeReq());
        assertTrue(snap.byBssid().isEmpty(),
                "probe requests carry no useful BSSID, must not pollute the talkers list");
    }

    @Test
    void durationAndFpsTrackTimestamps() {
        CaptureStats stats = new CaptureStats();
        stats.accept(new RadiotapFrame(1_000_000_000L, 2412, -50, 1,
                beaconFrame("aabbccddeeff", 100)));
        stats.accept(new RadiotapFrame(2_000_000_000L, 2412, -50, 1,
                beaconFrame("aabbccddeeff", 100)));
        CaptureStats.Snapshot snap = stats.snapshot();
        assertEquals(1_000_000_000L, snap.durationNs());
        // 2 frames over 1 s = 2 fps.
        assertEquals(2.0, snap.framesPerSecond(), 0.0001);
    }

    @Test
    void retryBitTalliesPerBssid() {
        CaptureStats stats = new CaptureStats();
        // 4 frames: 2 are retries -> 50% retry rate.
        stats.accept(new RadiotapFrame(1_000, 2412, -50, 6,
                beaconFrame("aabbccddeeff", 100, false)));
        stats.accept(new RadiotapFrame(2_000, 2412, -50, 6,
                beaconFrame("aabbccddeeff", 100, true)));
        stats.accept(new RadiotapFrame(3_000, 2412, -50, 6,
                beaconFrame("aabbccddeeff", 100, true)));
        stats.accept(new RadiotapFrame(4_000, 2412, -50, 6,
                beaconFrame("aabbccddeeff", 100, false)));

        CaptureStats.BssidStat top = stats.snapshot().byBssid()
                .get("aa:bb:cc:dd:ee:ff");
        assertEquals(4, top.frames());
        assertEquals(2, top.retries());
        assertEquals(50, top.retryPercent());
    }

    @Test
    void avgAndMinPhyRateTracked() {
        CaptureStats stats = new CaptureStats();
        // Rates 6, 12, 24, 6 -> avg 12, min 6. Rate 0 is "unknown" and
        // must not pollute the average (regression: a frame with no
        // radiotap RATE field would otherwise drag the avg towards 0).
        stats.accept(new RadiotapFrame(1_000, 2412, -50, 6,
                beaconFrame("aabbccddeeff", 100)));
        stats.accept(new RadiotapFrame(2_000, 2412, -50, 12,
                beaconFrame("aabbccddeeff", 100)));
        stats.accept(new RadiotapFrame(3_000, 2412, -50, 24,
                beaconFrame("aabbccddeeff", 100)));
        stats.accept(new RadiotapFrame(4_000, 2412, -50, 0, // unknown rate, ignored
                beaconFrame("aabbccddeeff", 100)));
        stats.accept(new RadiotapFrame(5_000, 2412, -50, 6,
                beaconFrame("aabbccddeeff", 100)));

        CaptureStats.BssidStat top = stats.snapshot().byBssid()
                .get("aa:bb:cc:dd:ee:ff");
        assertEquals(12.0, top.avgRateMbps(), 0.0001);
        assertEquals(6, top.minRateMbps());
    }

    @Test
    void beaconHzAndJitterComputedFromTimestamps() {
        CaptureStats stats = new CaptureStats();
        // 5 beacons spaced exactly 100 ms apart -> 4 intervals of 100 ms,
        // mean = 100 ms, jitter ≈ 0. Observed Hz = (5-1)/0.4 s = 10 Hz.
        long start = 1_000_000_000L;
        long step = 100_000_000L; // 100 ms in ns
        for (int i = 0; i < 5; i++) {
            stats.accept(new RadiotapFrame(start + i * step, 2412, -50, 6,
                    beaconFrame("aabbccddeeff", 100)));
        }
        CaptureStats.BssidStat top = stats.snapshot().byBssid()
                .get("aa:bb:cc:dd:ee:ff");
        assertEquals(5, top.beaconFrames());
        assertEquals(10.0, top.beaconObservedHz(), 0.0001);
        assertTrue(top.beaconJitterMs() < 0.001,
                "regular intervals should produce ~0 ms jitter, got "
                        + top.beaconJitterMs());
        assertTrue(top.hasBeaconSeries());
    }

    @Test
    void beaconJitterRisesWithIrregularSpacing() {
        CaptureStats stats = new CaptureStats();
        // Intervals: 100, 50, 150, 100 ms -> std-dev ~36 ms. Compare two
        // BSSIDs to make sure jitter is per-AP, not global.
        long t = 1_000_000_000L;
        long[] gaps = {100, 50, 150, 100}; // ms
        stats.accept(new RadiotapFrame(t, 2412, -50, 6,
                beaconFrame("aabbccddeeff", 100)));
        for (long g : gaps) {
            t += g * 1_000_000L;
            stats.accept(new RadiotapFrame(t, 2412, -50, 6,
                    beaconFrame("aabbccddeeff", 100)));
        }
        CaptureStats.BssidStat top = stats.snapshot().byBssid()
                .get("aa:bb:cc:dd:ee:ff");
        // Std-dev of {100,50,150,100} (sample) is ~35.36; allow generous slack
        // because Welford rounding accumulates differently than the textbook
        // two-pass version.
        assertTrue(top.beaconJitterMs() > 20.0 && top.beaconJitterMs() < 50.0,
                "expected jitter in ~20..50 ms range, got "
                        + top.beaconJitterMs());
    }

    @Test
    void resetWipesEverything() {
        CaptureStats stats = new CaptureStats();
        stats.accept(new RadiotapFrame(1_000, 2412, -50, 1,
                beaconFrame("aabbccddeeff", 100)));
        stats.reset();
        CaptureStats.Snapshot snap = stats.snapshot();
        assertEquals(0, snap.total());
        assertEquals(0, snap.firstFrameNs());
        assertTrue(snap.byBssid().isEmpty());
    }
}
