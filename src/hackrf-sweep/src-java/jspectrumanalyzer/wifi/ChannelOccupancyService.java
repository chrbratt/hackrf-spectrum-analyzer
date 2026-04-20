package jspectrumanalyzer.wifi;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jspectrumanalyzer.core.DatasetSpectrum;
import jspectrumanalyzer.core.FrequencyPlan;
import jspectrumanalyzer.fx.engine.SpectrumEngine;
import jspectrumanalyzer.fx.engine.SpectrumFrame;

/**
 * Per-Wi-Fi-channel occupancy / duty cycle tracker driven by the HackRF
 * sweep stream.
 *
 * <p>For every full sweep the engine publishes via
 * {@link SpectrumEngine#addFrameConsumer}, this service walks each channel
 * in {@link WifiChannelCatalog#ALL}, averages the dBm values of the bins
 * that fall inside the channel's 20 MHz window, and records a single
 * {@code (timestamp, occupied)} event into a per-channel rolling
 * <em>time</em> window. Occupancy percent is computed as
 * "occupied samples / total samples" inside the most recent
 * {@link #getWindowMs()} milliseconds.
 *
 * <h2>Why a time window instead of a fixed sample count?</h2>
 * The spectrum frame rate varies with FFT size, RBW and span: from ~0.5 Hz
 * on a slow 1-7 GHz sweep to 8+ Hz on a tight 80 MHz Wi-Fi span. A fixed
 * 50-sample window therefore meant "anything between 6 s and a couple of
 * minutes" depending on the user's scan settings, which made the bars
 * twitchy on slow sweeps and unreadably brief on fast ones. Pinning the
 * window to wall-clock time keeps the duty cycle reading consistent across
 * scan presets and matches Chanalyzer's "rolling 5 minutes" default.
 *
 * <p>Channels that do not have any bins inside the current sweep (out of
 * range or inside a multi-segment plan gap) are skipped: their existing
 * window is retained so a later sweep that does cover them resumes the
 * trend rather than resetting it.
 *
 * <p>Threading: {@link #onFrame} runs on the engine's processing thread
 * and is the only writer. Listeners receive a fresh immutable
 * {@link Snapshot} from the same thread; FX consumers wrap their
 * callbacks in {@code Platform.runLater} just as they do for the Wi-Fi
 * scan service. The latest snapshot is stored in an
 * {@link AtomicReference} so newcomers can pull state without blocking.
 */
public final class ChannelOccupancyService {

    private static final Logger LOG = LoggerFactory.getLogger(ChannelOccupancyService.class);

    /**
     * Default rolling window in milliseconds. Five minutes is the same
     * default Chanalyzer ships with - long enough to catch a microwave
     * cycle or an intermittent video stream, short enough that quitting
     * a noisy app shows up on the bar within a useful time horizon.
     */
    public static final long DEFAULT_WINDOW_MS = 5 * 60_000L;

    /**
     * Hard cap on retained samples per channel. With one sample per sweep
     * even an 8 Hz sweep over a 5 minute window produces ~2400 entries
     * (~25 KB per channel). Cap at 4x that so a user who bumps the window
     * up to 20 minutes still cannot allocate unbounded memory.
     */
    public static final int MAX_SAMPLES_PER_CHANNEL = 10_000;

    /**
     * Default "the channel is in use" cut-off in dBm. Anything below this
     * is treated as background noise. -75 dBm matches the old WiPry default
     * and is conservative enough that nearby APs always count while a
     * distant office two floors away does not.
     */
    public static final double DEFAULT_THRESHOLD_DBM = -75d;

    /** Per-channel occupancy result. {@code occupancyPercent} is in [0,100]. */
    public record ChannelStat(WifiChannelCatalog.Channel channel,
                              double occupancyPercent,
                              double averageDbm,
                              int sampleCount,
                              long windowMs) {}

    /** Immutable snapshot of all known channels' current state. */
    public record Snapshot(List<ChannelStat> stats, double thresholdDbm, long windowMs) {}

    /** Wall-clock + occupancy boolean per recorded sample. */
    private record Sample(long timestampMs, boolean occupied) {}

    private final List<Deque<Sample>> windows;
    private final double[] lastAvgDbm = new double[WifiChannelCatalog.ALL.size()];
    private final boolean[] hasReading = new boolean[WifiChannelCatalog.ALL.size()];

    private volatile double thresholdDbm = DEFAULT_THRESHOLD_DBM;
    private volatile long windowMs = DEFAULT_WINDOW_MS;

    private final AtomicReference<Snapshot> latest;
    private final List<Consumer<Snapshot>> listeners = new CopyOnWriteArrayList<>();

    /**
     * Throttle for {@link #publishSnapshot()}. The rolling window keeps
     * absorbing every frame so the percentage stays accurate; only the
     * fan-out (one immutable snapshot allocation + one
     * {@code Platform.runLater} per UI listener) is rate-limited so the
     * FX queue cannot accumulate per-frame snapshots when the UI is
     * busy. 5 Hz matches the density chart and keeps the percentage
     * column visibly live without flooding.
     */
    private static final long PUBLISH_INTERVAL_MS = 200;
    private long lastPublishMs = 0;

    public ChannelOccupancyService(SpectrumEngine engine) {
        int n = WifiChannelCatalog.ALL.size();
        List<Deque<Sample>> w = new ArrayList<>(n);
        for (int i = 0; i < n; i++) w.add(new ArrayDeque<>());
        this.windows = w;
        this.latest = new AtomicReference<>(
                new Snapshot(emptyStats(DEFAULT_WINDOW_MS),
                        DEFAULT_THRESHOLD_DBM, DEFAULT_WINDOW_MS));
        engine.addFrameConsumer(this::onFrame);
    }

    public Snapshot getLatest() {
        return latest.get();
    }

    public double getThresholdDbm() {
        return thresholdDbm;
    }

    public void setThresholdDbm(double v) {
        this.thresholdDbm = v;
    }

    public long getWindowMs() {
        return windowMs;
    }

    /**
     * Resize the rolling window. Existing samples are not rewritten; old
     * ones are pruned naturally on the next {@link #onFrame} pass.
     */
    public void setWindowMs(long ms) {
        if (ms < 1000) ms = 1000;
        this.windowMs = ms;
    }

    public void addListener(Consumer<Snapshot> l) {
        listeners.add(l);
        l.accept(latest.get());
    }

    public void removeListener(Consumer<Snapshot> l) {
        listeners.remove(l);
    }

    /**
     * Engine-thread callback: walk every channel in the catalogue, average
     * its in-window dBm values, append a timestamped sample, and prune
     * anything older than the rolling window. Cheap by design (O(channels
     * * binsPerChannel) with both factors small) so it stays well below
     * the engine's queue fill threshold.
     */
    private void onFrame(SpectrumFrame frame) {
        if (frame == null || frame.dataset == null) return;
        DatasetSpectrum ds = frame.dataset;
        int len = ds.spectrumLength();
        if (len <= 0) return;
        FrequencyPlan plan = ds.getPlan();
        if (plan == null) return;
        float[] arr = ds.getSpectrumArray();
        if (arr == null || arr.length == 0) return;

        try {
            double cutoff = thresholdDbm;
            long now = System.currentTimeMillis();
            long cutoffTime = now - windowMs;
            for (int i = 0; i < WifiChannelCatalog.ALL.size(); i++) {
                WifiChannelCatalog.Channel ch = WifiChannelCatalog.ALL.get(i);
                AvgResult res = analyseChannel(arr, ds, ch.lowMhz(), ch.highMhz());
                Deque<Sample> q = windows.get(i);
                if (res.valid) {
                    // Display the peak bin so the user sees the actual loudest
                    // signal in the channel. The previous mean-of-bins reading
                    // got diluted by 100+ noise-floor bins to -90 dBm whenever
                    // a Wi-Fi burst only filled a fraction of the channel,
                    // which made the threshold check (peak > cutoff) and the
                    // displayed dBm value disagree visually.
                    lastAvgDbm[i] = res.peakDbm;
                    hasReading[i] = true;
                    // Peak above threshold => "something is transmitting in
                    // this channel right now". Same heuristic real spectrum
                    // analyzers use for "channel busy" detection and matches
                    // what the user sees as a peak on the chart.
                    q.addLast(new Sample(now, res.peakDbm > cutoff));
                    if (q.size() > MAX_SAMPLES_PER_CHANNEL) q.removeFirst();
                }
                while (!q.isEmpty() && q.peekFirst().timestampMs() < cutoffTime) {
                    q.removeFirst();
                }
            }
            if (now - lastPublishMs >= PUBLISH_INTERVAL_MS) {
                lastPublishMs = now;
                publishSnapshot();
            }
        } catch (RuntimeException ex) {
            LOG.warn("Occupancy update failed", ex);
        }
    }

    private void publishSnapshot() {
        long w = windowMs;
        List<ChannelStat> out = new ArrayList<>(WifiChannelCatalog.ALL.size());
        for (int i = 0; i < WifiChannelCatalog.ALL.size(); i++) {
            WifiChannelCatalog.Channel ch = WifiChannelCatalog.ALL.get(i);
            Deque<Sample> q = windows.get(i);
            int n = q.size();
            double pct = 0;
            if (n > 0) {
                int count = 0;
                for (Sample s : q) if (s.occupied()) count++;
                pct = 100.0 * count / n;
            }
            double avg = hasReading[i] ? lastAvgDbm[i] : Double.NaN;
            out.add(new ChannelStat(ch, pct, avg, n, w));
        }
        Snapshot snap = new Snapshot(Collections.unmodifiableList(out), thresholdDbm, w);
        latest.set(snap);
        for (Consumer<Snapshot> l : listeners) {
            try {
                l.accept(snap);
            } catch (RuntimeException ex) {
                LOG.warn("Occupancy listener threw", ex);
            }
        }
    }

    /**
     * Walk the bins that fall inside {@code [lowMhz, highMhz]} and return
     * the peak dBm reading in the channel. Skips channels whose range is
     * entirely outside the covered sweep (returns {@code valid=false}).
     *
     * <p>We deliberately use the peak instead of the linear-power mean.
     * With ~100 bins per 20 MHz channel and a noise floor at ~-100 dBm/bin,
     * the mean gets diluted to ~-87 dBm even when a single bin is showing a
     * -68 dBm Wi-Fi burst. That made the duty cycle bars stay flat at 0 %
     * on visibly busy channels, because the mean never crossed the -75 dBm
     * threshold. Peak power answers the right question for a duty-cycle
     * tracker - "is the channel hot at this moment?" - and lines up with
     * what the user sees as a peak on the spectrum trace.
     *
     * <p>True integrated channel power (sum of bin power scaled by RBW) is
     * a different and more involved measurement; if we ever want it we can
     * add it as a second field, but the duty-cycle threshold check should
     * stay on the peak.
     */
    private static AvgResult analyseChannel(float[] arr, DatasetSpectrum ds,
                                             int lowMhz, int highMhz) {
        double peak = Double.NEGATIVE_INFINITY;
        int count = 0;
        int len = arr.length;
        for (int i = 0; i < len; i++) {
            double mhz = ds.rfFrequencyMHzAt(i);
            if (mhz < lowMhz || mhz > highMhz) continue;
            double dbm = arr[i];
            if (dbm < -150) continue;
            if (dbm > peak) peak = dbm;
            count++;
        }
        if (count == 0) {
            return new AvgResult(false, 0);
        }
        return new AvgResult(true, peak);
    }

    private static List<ChannelStat> emptyStats(long windowMs) {
        List<ChannelStat> out = new ArrayList<>(WifiChannelCatalog.ALL.size());
        for (WifiChannelCatalog.Channel ch : WifiChannelCatalog.ALL) {
            out.add(new ChannelStat(ch, 0, Double.NaN, 0, windowMs));
        }
        return Collections.unmodifiableList(out);
    }

    /**
     * Result of {@link #analyseChannel}. {@code peakDbm} is the loudest bin
     * inside the channel's frequency window; ignore when {@code valid} is
     * {@code false}.
     */
    private record AvgResult(boolean valid, double peakDbm) {}
}
