package jspectrumanalyzer.wifi;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jspectrumanalyzer.core.DatasetSpectrum;
import jspectrumanalyzer.fx.engine.SpectrumEngine;
import jspectrumanalyzer.fx.engine.SpectrumFrame;

/**
 * Heuristic classifier that scans the rolling spectrum for signals that
 * look like common non-Wi-Fi 2.4 GHz interferers and publishes a list of
 * detected sources.
 *
 * <h2>Approach</h2>
 * On every engine frame we update a per-bin exponential moving average
 * (EMA) of dBm plus an exponential moving variance, both with alpha
 * {@link #ALPHA}. The variance signal is the key feature: a stable
 * carrier (analog video) sits near zero, while a 50/60 Hz pulsed
 * microwave hovers in the tens.
 *
 * <p>Once every {@link #DETECT_INTERVAL_FRAMES} frames we walk the EMA
 * array, group contiguous bins above {@link #ACTIVE_THRESHOLD_DBM} into
 * "regions", and classify each region by a small rule set (see
 * {@link #classify}). Regions that overlap a known Wi-Fi AP centre
 * (within half-bandwidth) are dropped from the output - those are
 * already first-class citizens of the AP table.
 *
 * <h2>Why a rule engine and not ML?</h2>
 * The four target categories (microwave / analog video / BLE-BT
 * frequency hopping / unknown wideband) have very different
 * time-frequency footprints. A handful of bandwidth + variance
 * thresholds gets us 80 % of the value at 1 % of the complexity, with
 * the bonus that every classification can be explained back to the
 * user in one sentence (see {@link Interferer#explanation()}). A more
 * sophisticated ML model is a Phase-2 candidate.
 *
 * <h2>Known limitations</h2>
 * <ul>
 *   <li>Bluetooth/BLE detection is best-effort. Classic BT hops 1600
 *       times/s and BLE 40 channels at adv intervals; the HackRF sweep
 *       rate is far slower, so we can only flag "many narrow peaks
 *       across 2.4 GHz" as a candidate. False positives are common in
 *       a noisy office.</li>
 *   <li>5 GHz interferers other than rogue Wi-Fi are rare and not
 *       targeted; the rule set there only emits "unknown wideband".</li>
 *   <li>Detection is dBm-threshold based ({@link #ACTIVE_THRESHOLD_DBM}
 *       defaults to -70). Aggressive antennas / amplifier setups may
 *       want this raised; we expose it as a setter for future UI
 *       wiring.</li>
 * </ul>
 *
 * <h2>Threading</h2>
 * {@link #onFrame} is the only writer (engine thread). The latest AP
 * list is read via {@link AtomicReference} so the engine never blocks
 * on the polling thread that produces it.
 */
public final class InterfererClassifier {

    private static final Logger LOG = LoggerFactory.getLogger(InterfererClassifier.class);

    /**
     * EMA smoothing factor. 0.2 keeps the average responsive (reaches
     * ~95 % of a step change in ~14 frames at 1 Hz sweep rate) while
     * still dampening single-frame noise.
     */
    public static final double ALPHA = 0.2d;

    /** Cell counts as "active" when the EMA is above this dBm value. */
    public static final double ACTIVE_THRESHOLD_DBM = -70d;

    /** Run the detection step every Nth frame to avoid log spam / FX churn. */
    public static final int DETECT_INTERVAL_FRAMES = 10;

    /**
     * Minimum number of contiguous active bins to qualify as a region.
     * Set so that an isolated ping (one bin spike) is ignored and only
     * sustained activity makes the list.
     */
    public static final int MIN_REGION_BINS = 3;

    /** Wi-Fi AP overlap radius - regions whose centre falls within
     *  half-bandwidth of any known AP centre are filtered out. */
    private static final double AP_OVERLAP_HALFBW_MHZ = 12d;

    /** Microwave signature thresholds (2.4 GHz only). */
    private static final double MW_MIN_BW_MHZ = 18d;
    private static final double MW_MIN_VARIANCE = 6d;
    private static final double MW_BAND_LOW_MHZ = 2400d;
    private static final double MW_BAND_HIGH_MHZ = 2495d;

    /** Analog video / baby monitor signature thresholds. */
    private static final double AV_MIN_BW_MHZ = 4d;
    private static final double AV_MAX_BW_MHZ = 22d;
    private static final double AV_MAX_VARIANCE = 2d;

    /** Bluetooth-like narrow region thresholds (2.4 GHz only). */
    private static final double BT_MAX_BW_MHZ = 3d;
    private static final int BT_MIN_NARROW_REGIONS = 4;

    /**
     * Recognised interferer categories. {@code UNKNOWN_WIDEBAND} is the
     * catch-all for "we found something non-Wi-Fi but cannot prove what
     * it is" - exposed so the user still sees that the spectrum has
     * traffic the AP table is missing.
     */
    public enum Type {
        MICROWAVE("Microwave oven"),
        ANALOG_VIDEO("Analog video / baby monitor"),
        BLUETOOTH_FH("Bluetooth / BLE (frequency hopping)"),
        UNKNOWN_WIDEBAND("Unknown wideband signal");

        private final String label;
        Type(String label) { this.label = label; }
        public String label() { return label; }
    }

    /** One detected non-Wi-Fi source. Immutable. */
    public record Interferer(
            Type type,
            double centerMhz,
            double bandwidthMhz,
            double avgDbm,
            double variance,
            String explanation) {}

    /** Snapshot of every interferer the latest detection pass found. */
    public record Snapshot(List<Interferer> sources, long generatedAtMs) {}

    private final WifiScanService wifiScanService;
    private final AtomicReference<Snapshot> latest =
            new AtomicReference<>(new Snapshot(Collections.emptyList(), 0));
    private final CopyOnWriteArrayList<Consumer<Snapshot>> listeners = new CopyOnWriteArrayList<>();

    /** EMA dBm and EMA variance per bin; (re)allocated on width change. */
    private double[] ema = new double[0];
    private double[] var = new double[0];
    private boolean[] initialized = new boolean[0];
    private int frameCounter = 0;
    /** Cached for region MHz lookups; refreshed on every frame. */
    private double[] freqMhzAt = new double[0];

    public InterfererClassifier(SpectrumEngine engine, WifiScanService wifiScanService) {
        this.wifiScanService = wifiScanService;
        engine.addFrameConsumer(this::onFrame);
    }

    public Snapshot getLatest() {
        return latest.get();
    }

    public void addListener(Consumer<Snapshot> l) {
        listeners.add(l);
        l.accept(latest.get());
    }

    public void removeListener(Consumer<Snapshot> l) {
        listeners.remove(l);
    }

    private void onFrame(SpectrumFrame frame) {
        if (frame == null || frame.dataset == null) return;
        DatasetSpectrum ds = frame.dataset;
        int len = ds.spectrumLength();
        if (len <= 0) return;
        float[] arr = ds.getSpectrumArray();
        if (arr == null || arr.length == 0) return;

        try {
            ensureCapacity(len, ds);
            updateStats(arr, len);
            if (++frameCounter % DETECT_INTERVAL_FRAMES == 0) {
                runDetection();
            }
        } catch (RuntimeException ex) {
            LOG.warn("InterfererClassifier update failed", ex);
        }
    }

    private void ensureCapacity(int len, DatasetSpectrum ds) {
        if (len != ema.length) {
            ema = new double[len];
            var = new double[len];
            initialized = new boolean[len];
            freqMhzAt = new double[len];
        }
        // Refresh per-bin MHz lookup. Cheap (one multiply per bin) and
        // avoids us calling rfFrequencyMHzAt twice per region during
        // detection.
        for (int i = 0; i < len; i++) {
            freqMhzAt[i] = ds.rfFrequencyMHzAt(i);
        }
    }

    /** EMA + EMA-of-squared-deviation update per bin. */
    private void updateStats(float[] arr, int len) {
        double a = ALPHA;
        double oneMinusA = 1 - a;
        for (int i = 0; i < len; i++) {
            double dbm = arr[i];
            if (!initialized[i]) {
                ema[i] = dbm;
                var[i] = 0;
                initialized[i] = true;
                continue;
            }
            double prevEma = ema[i];
            double newEma = oneMinusA * prevEma + a * dbm;
            double diff = dbm - newEma;
            ema[i] = newEma;
            var[i] = oneMinusA * var[i] + a * (diff * diff);
        }
    }

    /**
     * Walk the EMA / variance arrays, find contiguous regions above the
     * activity threshold, classify each, drop ones overlapping known
     * APs, and publish the result.
     */
    private void runDetection() {
        List<Region> regions = findRegions();
        if (regions.isEmpty()) {
            publish(Collections.emptyList());
            return;
        }
        List<WifiAccessPoint> aps = wifiScanService == null
                ? Collections.emptyList() : wifiScanService.getLatest();

        List<Region> nonWifi = new ArrayList<>();
        for (Region r : regions) {
            if (overlapsKnownAp(r, aps)) continue;
            nonWifi.add(r);
        }

        // Bluetooth / BLE detection is global ("many narrow regions in
        // 2.4 GHz") rather than per-region, so handle it before per-
        // region classification consumes the candidates.
        List<Interferer> out = new ArrayList<>();
        Interferer bt = detectBluetooth(nonWifi);
        if (bt != null) out.add(bt);

        for (Region r : nonWifi) {
            // Skip narrow 2.4 GHz regions that were already aggregated
            // into the Bluetooth detection; counting them twice would
            // be both confusing and incorrect.
            if (bt != null && r.bandwidthMhz() <= BT_MAX_BW_MHZ
                    && in24Band(r)) continue;
            Interferer iv = classify(r);
            if (iv != null) out.add(iv);
        }
        publish(out);
    }

    private List<Region> findRegions() {
        List<Region> out = new ArrayList<>();
        int n = ema.length;
        int start = -1;
        for (int i = 0; i < n; i++) {
            boolean active = initialized[i] && ema[i] > ACTIVE_THRESHOLD_DBM;
            if (active && start < 0) {
                start = i;
            } else if (!active && start >= 0) {
                addRegion(out, start, i - 1);
                start = -1;
            }
        }
        if (start >= 0) addRegion(out, start, n - 1);
        return out;
    }

    private void addRegion(List<Region> out, int from, int to) {
        if (to - from + 1 < MIN_REGION_BINS) return;
        double sumDbm = 0;
        double sumVar = 0;
        int count = to - from + 1;
        for (int j = from; j <= to; j++) {
            sumDbm += ema[j];
            sumVar += var[j];
        }
        double avgDbm = sumDbm / count;
        double avgVar = sumVar / count;
        double startMhz = freqMhzAt[from];
        double stopMhz = freqMhzAt[to];
        double bandwidthMhz = Math.max(0, stopMhz - startMhz);
        double centerMhz = (startMhz + stopMhz) / 2;
        out.add(new Region(centerMhz, bandwidthMhz, avgDbm, avgVar));
    }

    private static boolean overlapsKnownAp(Region r, List<WifiAccessPoint> aps) {
        for (WifiAccessPoint ap : aps) {
            // Bonded centre is the actual RF midpoint of the channel block;
            // for 80 / 160 MHz APs this can be tens of MHz away from the
            // primary that the OS reports. Using the bonded centre keeps
            // the "is this region just a known Wi-Fi AP?" mask aligned
            // with what the spectrum trace actually shows.
            double apCenter = ap.bondedCenterMhz();
            double apHalfBw = Math.max(AP_OVERLAP_HALFBW_MHZ, ap.bandwidthMhz() / 2.0);
            // Simple centre-distance check; cheaper than full overlap
            // math and adequate given AP_OVERLAP_HALFBW_MHZ slack.
            if (Math.abs(r.centerMhz() - apCenter) <= apHalfBw + r.bandwidthMhz() / 2.0) {
                return true;
            }
        }
        return false;
    }

    /**
     * Aggregate the narrow regions in 2.4 GHz that look like a hopping
     * BT/BLE radio. We require {@link #BT_MIN_NARROW_REGIONS} narrow
     * peaks scattered across the band and report a single combined
     * "interferer" rather than one entry per hop.
     */
    private Interferer detectBluetooth(List<Region> regions) {
        int narrow = 0;
        double sumDbm = 0;
        double sumBw = 0;
        double minMhz = Double.POSITIVE_INFINITY;
        double maxMhz = Double.NEGATIVE_INFINITY;
        for (Region r : regions) {
            if (!in24Band(r)) continue;
            if (r.bandwidthMhz() > BT_MAX_BW_MHZ) continue;
            narrow++;
            sumDbm += r.avgDbm();
            sumBw += r.bandwidthMhz();
            if (r.centerMhz() < minMhz) minMhz = r.centerMhz();
            if (r.centerMhz() > maxMhz) maxMhz = r.centerMhz();
        }
        if (narrow < BT_MIN_NARROW_REGIONS) return null;
        double centerMhz = (minMhz + maxMhz) / 2;
        // Variance is N/A for the aggregate; report 0 so the UI doesn't
        // make claims it cannot back up.
        return new Interferer(Type.BLUETOOTH_FH, centerMhz, sumBw,
                sumDbm / narrow, 0,
                String.format("%d narrow peaks (<%.0f MHz) across 2.4 GHz",
                        narrow, BT_MAX_BW_MHZ));
    }

    private Interferer classify(Region r) {
        if (in24Band(r)
                && r.bandwidthMhz() >= MW_MIN_BW_MHZ
                && r.variance() >= MW_MIN_VARIANCE) {
            return new Interferer(Type.MICROWAVE, r.centerMhz(),
                    r.bandwidthMhz(), r.avgDbm(), r.variance(),
                    String.format("Wide (%.0f MHz) + flickers (var %.1f) in 2.4 GHz",
                            r.bandwidthMhz(), r.variance()));
        }
        if (r.bandwidthMhz() >= AV_MIN_BW_MHZ
                && r.bandwidthMhz() <= AV_MAX_BW_MHZ
                && r.variance() <= AV_MAX_VARIANCE) {
            return new Interferer(Type.ANALOG_VIDEO, r.centerMhz(),
                    r.bandwidthMhz(), r.avgDbm(), r.variance(),
                    String.format("Stable carrier (var %.1f) at %.0f MHz, BW %.0f MHz",
                            r.variance(), r.centerMhz(), r.bandwidthMhz()));
        }
        if (r.bandwidthMhz() > AV_MAX_BW_MHZ) {
            return new Interferer(Type.UNKNOWN_WIDEBAND, r.centerMhz(),
                    r.bandwidthMhz(), r.avgDbm(), r.variance(),
                    String.format("Wide (%.0f MHz) signal not matching any AP",
                            r.bandwidthMhz()));
        }
        return null;
    }

    private static boolean in24Band(Region r) {
        return r.centerMhz() >= MW_BAND_LOW_MHZ
                && r.centerMhz() <= MW_BAND_HIGH_MHZ;
    }

    private void publish(List<Interferer> sources) {
        Snapshot snap = new Snapshot(
                Collections.unmodifiableList(new ArrayList<>(sources)),
                System.currentTimeMillis());
        latest.set(snap);
        for (Consumer<Snapshot> l : listeners) {
            try {
                l.accept(snap);
            } catch (RuntimeException ex) {
                LOG.warn("Interferer listener threw", ex);
            }
        }
    }

    /**
     * Reset all per-bin EMAs. Useful on a "Clear traces" button so a
     * past microwave run does not bias the variance for a long time.
     */
    public synchronized void reset() {
        Arrays.fill(initialized, false);
        Arrays.fill(ema, 0);
        Arrays.fill(var, 0);
        frameCounter = 0;
        publish(Collections.emptyList());
    }

    /** Per-region working-set record (not exposed to callers). */
    private record Region(double centerMhz, double bandwidthMhz,
                          double avgDbm, double variance) {}
}
