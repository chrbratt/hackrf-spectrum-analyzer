package jspectrumanalyzer.wifi.capture;

import java.util.HashMap;
import java.util.Map;

/**
 * Per-capture-session running tallies: total frames, frames-per-type,
 * a few well-known management subtypes the UI shows individually, plus
 * per-BSSID counters that drive the "top talkers" and "per-AP health"
 * views.
 *
 * <p>Single-threaded by contract - the {@link Pcap4jMonitorCapture}
 * polling thread is the only writer. The UI reads
 * {@link #snapshot()} on the JavaFX thread; that method takes the
 * monitor so the snapshot is consistent with no torn updates. We
 * accept a cheap synchronisation cost rather than the complexity of
 * a per-counter {@code AtomicLong} grid because the polling thread
 * already does heavy work per frame and the contention is one short
 * critical section per UI tick (4 Hz).
 *
 * <h2>Per-AP health metrics</h2>
 * On top of raw counts we derive five "is this AP healthy on its
 * channel?" signals that map directly to widely-used WLAN
 * troubleshooting heuristics:
 * <ul>
 *   <li><b>Retry %</b> - fraction of management frames where the FC
 *       retry bit was set. >10% is concerning, >25% is bad. Bears the
 *       same interpretation as Wireshark's
 *       {@code wlan.fc.retry == 1} filter.</li>
 *   <li><b>Beacon rate (Hz)</b> - observed beacons per second. APs
 *       broadcast beacons every {@code BeaconInterval * 1024 us}
 *       (default 100 TU = 102.4 ms = ~9.77 Hz). A drop signals
 *       beacon loss (RSSI low or channel saturated).</li>
 *   <li><b>Beacon jitter (ms)</b> - standard deviation of beacon
 *       inter-arrival times. Low single-digits = healthy CCA; double
 *       digits = the AP is repeatedly losing the contention slot.</li>
 *   <li><b>Average PHY rate (Mbps)</b> - mean of the radiotap rate
 *       field across captured frames for this BSSID. Trending low
 *       means rate adaptation kicked in (bad SNR or legacy clients).</li>
 *   <li><b>Minimum PHY rate (Mbps)</b> - the floor reached during
 *       capture. Useful to spot APs that only briefly drop to slow
 *       rates when a far-away client wakes up.</li>
 * </ul>
 */
public final class CaptureStats {

    /**
     * Per-BSSID rollup including health metrics. {@code lastRssiDbm} is
     * the last non-zero RSSI we saw for that BSSID (or 0 if the
     * radiotap header never included the antenna-signal field, which
     * happens on some USB NICs); the UI must treat 0 as "unknown"
     * rather than "0 dBm". Rate fields use the same sentinel: 0 means
     * "no rate sample observed", the modern HT/VHT/HE PHY case.
     */
    public record BssidStat(String bssid, long frames, long bytes,
                            int lastRssiDbm, long retries,
                            long beaconFrames, double beaconObservedHz,
                            double beaconJitterMs,
                            double avgRateMbps, int minRateMbps) {

        /** Retry rate as a 0..100 percentage; 0 when no frames observed. */
        public int retryPercent() {
            return frames <= 0 ? 0 : (int) Math.round(100.0 * retries / frames);
        }

        /** True when we have enough beacon samples to trust the jitter / Hz numbers. */
        public boolean hasBeaconSeries() {
            return beaconFrames >= 4;
        }
    }

    /**
     * Immutable snapshot returned by {@link #snapshot()}. UI can read
     * fields without further locking.
     *
     * <p>{@code firstFrameNs} / {@code lastFrameNs} are the host
     * monotonic-clock timestamps from {@link RadiotapFrame#timestampNs}
     * and let the UI compute capture duration and frames-per-second
     * without keeping its own clock. Both are 0 until the first frame
     * arrives.
     */
    public record Snapshot(long total, long mgmt, long ctrl, long data, long ext,
                           long beacons, long probeReq, long probeResp,
                           long deauth, long bytes,
                           long firstFrameNs, long lastFrameNs,
                           Map<String, BssidStat> byBssid) {

        /** Capture duration in nanoseconds; 0 before any frame is seen. */
        public long durationNs() {
            return (firstFrameNs == 0 || lastFrameNs <= firstFrameNs)
                    ? 0 : lastFrameNs - firstFrameNs;
        }

        /** Average frames per second over the capture, or 0 if not started. */
        public double framesPerSecond() {
            long ns = durationNs();
            return (ns <= 0 || total <= 0) ? 0 : (total * 1_000_000_000.0 / ns);
        }
    }

    private long total;
    private long mgmt;
    private long ctrl;
    private long data;
    private long ext;
    private long beacons;
    private long probeReq;
    private long probeResp;
    private long deauth;
    private long bytes;
    private long firstFrameNs;
    private long lastFrameNs;

    /**
     * Per-BSSID rolling counters. Bounded by {@link #MAX_BSSIDS} so a
     * busy office on probe-flood night does not OOM the UI - new
     * BSSIDs past the cap are silently dropped (existing entries keep
     * updating).
     */
    private static final int MAX_BSSIDS = 64;

    /**
     * Beacon timestamp ringbuffer size per BSSID. 32 samples ≈ 3.2 s
     * of beacons at the default 100 TU interval - long enough to spot
     * burst-jitter without inflating per-BSSID memory beyond ~256
     * bytes. The std-dev calculation uses whatever subset is currently
     * in the buffer.
     */
    private static final int BEACON_TS_SAMPLES = 32;

    private final Map<String, MutableBssid> byBssid = new HashMap<>();

    /** Mutable counterpart of {@link BssidStat}; see snapshot() for the copy. */
    private static final class MutableBssid {
        long frames;
        long bytes;
        int lastRssiDbm;
        long retries;
        long beaconFrames;
        long firstBeaconNs;
        long lastBeaconNs;
        /** Ringbuffer of beacon timestamps in ns. Filled lazily; see {@link #pushBeacon}. */
        final long[] beaconTs = new long[BEACON_TS_SAMPLES];
        int beaconTsCursor;
        int beaconTsFilled;
        long rateSumMbps;
        long rateSamples;
        /** Sentinel = "no sample yet"; translated to 0 in BssidStat. */
        int minRateMbps = Integer.MAX_VALUE;

        void pushBeacon(long ns) {
            beaconTs[beaconTsCursor] = ns;
            beaconTsCursor = (beaconTsCursor + 1) % BEACON_TS_SAMPLES;
            if (beaconTsFilled < BEACON_TS_SAMPLES) beaconTsFilled++;
        }

        /**
         * Standard deviation of inter-arrival times (in ms) across the
         * timestamps in the ringbuffer. 0 when fewer than 2 samples - a
         * single beacon has no IAT to average.
         */
        double beaconJitterMs() {
            if (beaconTsFilled < 2) return 0;
            // Reconstruct chronological order: oldest sits at cursor when
            // the buffer is full, otherwise at index 0.
            long[] sorted = new long[beaconTsFilled];
            if (beaconTsFilled < BEACON_TS_SAMPLES) {
                System.arraycopy(beaconTs, 0, sorted, 0, beaconTsFilled);
            } else {
                int oldest = beaconTsCursor;
                for (int i = 0; i < BEACON_TS_SAMPLES; i++) {
                    sorted[i] = beaconTs[(oldest + i) % BEACON_TS_SAMPLES];
                }
            }
            // Inter-arrival times in ms. Welford's online variance to
            // avoid two passes.
            double mean = 0;
            double m2 = 0;
            int n = 0;
            for (int i = 1; i < sorted.length; i++) {
                double iatMs = (sorted[i] - sorted[i - 1]) / 1_000_000.0;
                n++;
                double delta = iatMs - mean;
                mean += delta / n;
                m2 += delta * (iatMs - mean);
            }
            if (n < 2) return 0;
            return Math.sqrt(m2 / (n - 1));
        }

        double beaconObservedHz() {
            if (beaconFrames < 2 || lastBeaconNs <= firstBeaconNs) return 0;
            double secs = (lastBeaconNs - firstBeaconNs) / 1_000_000_000.0;
            // beaconFrames-1 intervals between beaconFrames samples.
            return (beaconFrames - 1) / secs;
        }
    }

    public synchronized void reset() {
        total = mgmt = ctrl = data = ext = 0;
        beacons = probeReq = probeResp = deauth = 0;
        bytes = 0;
        firstFrameNs = lastFrameNs = 0;
        byBssid.clear();
    }

    /**
     * Update tallies for one captured frame. Reads the 802.11 frame
     * control bytes directly from the supplied (radiotap-stripped) MAC
     * payload to keep the per-frame work to a few byte reads + branches.
     *
     * <p>Per-BSSID accounting runs for every management frame regardless
     * of whether radiotap supplied an RSSI value: many USB NICs (e.g.
     * NETGEAR A6210) omit the antenna-signal radiotap field, and
     * gating BSSID extraction on RSSI != 0 would silently zero out
     * top-talker / hidden-SSID statistics for those users.
     */
    public synchronized void accept(RadiotapFrame f) {
        total++;
        byte[] mac = f.frame();
        bytes += mac.length;
        if (firstFrameNs == 0) firstFrameNs = f.timestampNs();
        lastFrameNs = f.timestampNs();
        if (mac.length < 2) return;
        int fc0 = mac[0] & 0xff;
        int fc1 = mac[1] & 0xff;
        boolean retryBit = (fc1 & 0x08) != 0;
        int type = (fc0 >> 2) & 0x03;
        int subtype = (fc0 >> 4) & 0x0f;
        switch (type) {
            case RadiotapDecoder.TYPE_MGMT -> {
                mgmt++;
                switch (subtype) {
                    case RadiotapDecoder.MGMT_BEACON      -> beacons++;
                    case RadiotapDecoder.MGMT_PROBE_REQ   -> probeReq++;
                    case RadiotapDecoder.MGMT_PROBE_RESP  -> probeResp++;
                    case RadiotapDecoder.MGMT_DEAUTH      -> deauth++;
                    default -> { /* other mgmt - lumped into mgmt total */ }
                }
                // BSSID for mgmt frames is at MAC offset 16 (Address 3).
                // Probe requests have no BSSID address-3 (it's the
                // wildcard ff:ff:ff:ff:ff:ff or absent depending on
                // subtype) - skip them here so they don't clutter the
                // top talkers list.
                if (mac.length >= 22 && subtype != RadiotapDecoder.MGMT_PROBE_REQ) {
                    String bssid = formatMac(mac, 16);
                    if (!isAllOnes(mac, 16)) {
                        MutableBssid b = byBssid.get(bssid);
                        if (b == null) {
                            if (byBssid.size() >= MAX_BSSIDS) return;
                            b = new MutableBssid();
                            byBssid.put(bssid, b);
                        }
                        b.frames++;
                        b.bytes += mac.length;
                        if (f.rssiDbm() != 0) b.lastRssiDbm = f.rssiDbm();
                        if (retryBit) b.retries++;
                        if (subtype == RadiotapDecoder.MGMT_BEACON) {
                            b.beaconFrames++;
                            if (b.firstBeaconNs == 0) b.firstBeaconNs = f.timestampNs();
                            b.lastBeaconNs = f.timestampNs();
                            b.pushBeacon(f.timestampNs());
                        }
                        if (f.rateMbps() > 0) {
                            b.rateSumMbps += f.rateMbps();
                            b.rateSamples++;
                            if (f.rateMbps() < b.minRateMbps) b.minRateMbps = f.rateMbps();
                        }
                    }
                }
            }
            case RadiotapDecoder.TYPE_CTRL -> ctrl++;
            case RadiotapDecoder.TYPE_DATA -> data++;
            case RadiotapDecoder.TYPE_EXT  -> ext++;
            default -> { /* malformed, ignore */ }
        }
    }

    public synchronized Snapshot snapshot() {
        Map<String, BssidStat> copy = new HashMap<>(byBssid.size());
        for (Map.Entry<String, MutableBssid> e : byBssid.entrySet()) {
            MutableBssid b = e.getValue();
            double avgRate = b.rateSamples == 0 ? 0
                    : (double) b.rateSumMbps / b.rateSamples;
            int minRate = b.minRateMbps == Integer.MAX_VALUE ? 0 : b.minRateMbps;
            copy.put(e.getKey(), new BssidStat(
                    e.getKey(), b.frames, b.bytes, b.lastRssiDbm,
                    b.retries, b.beaconFrames,
                    b.beaconObservedHz(), b.beaconJitterMs(),
                    avgRate, minRate));
        }
        return new Snapshot(total, mgmt, ctrl, data, ext,
                beacons, probeReq, probeResp, deauth, bytes,
                firstFrameNs, lastFrameNs,
                Map.copyOf(copy));
    }

    private static boolean isAllOnes(byte[] data, int offset) {
        for (int i = 0; i < 6; i++) {
            if (data[offset + i] != (byte) 0xff) return false;
        }
        return true;
    }

    private static String formatMac(byte[] data, int offset) {
        StringBuilder sb = new StringBuilder(17);
        for (int i = 0; i < 6; i++) {
            if (i > 0) sb.append(':');
            int b = data[offset + i] & 0xff;
            if (b < 0x10) sb.append('0');
            sb.append(Integer.toHexString(b));
        }
        return sb.toString();
    }
}
