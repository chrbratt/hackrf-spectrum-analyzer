package jspectrumanalyzer.wifi;

import java.util.Arrays;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jspectrumanalyzer.core.DatasetSpectrum;
import jspectrumanalyzer.fx.engine.SpectrumEngine;
import jspectrumanalyzer.fx.engine.SpectrumFrame;

/**
 * Rolling 2D histogram of {@code (frequency bin, power dBm)} cell counts
 * driven by the HackRF sweep stream. The output is the data backing the
 * "density chart" view that mirrors Chanalyzer's iconic visualization:
 * vertical streaks where signals have spent the most time during the
 * accumulation window, fading where they were transient.
 *
 * <h2>Data model</h2>
 * <ul>
 *   <li><b>X axis</b> = the dataset's bin indices (1:1 mapping). The
 *       chart re-uses the spectrum's existing bin layout so a reset is
 *       only needed when the user re-tunes the radio.</li>
 *   <li><b>Y axis</b> = dBm rows from {@link #TOP_DBM} (row 0) down to
 *       {@code TOP_DBM - HEIGHT_DBM} (row {@code HEIGHT-1}), 1 dB per
 *       row. Out-of-range samples are clamped to the nearest edge so
 *       extreme spikes still show up at the top / bottom of the chart
 *       instead of disappearing.</li>
 *   <li><b>Cell value</b> = number of frames whose bin landed in that
 *       cell. Effectively monotonically increasing until the user
 *       triggers a {@link #reset()}; the view is responsible for
 *       log-normalising against {@link Snapshot#maxCount()} so the
 *       colour ramp stays useful.</li>
 * </ul>
 *
 * <h2>Why a flat {@code int[]} grid?</h2>
 * A {@code int[width*height]} buffer is one allocation, cache-friendly
 * for the per-frame bin loop ({@code idx = x * HEIGHT + row}), and
 * cheap to defensive-copy for snapshot delivery. {@code int[][]} would
 * cost an extra indirection per access plus N allocations per reset,
 * for no observable readability gain.
 *
 * <h2>Reset triggers</h2>
 * The grid is rebuilt from scratch the first time a frame arrives whose
 * bin count differs from the current grid width. Re-tuning the radio
 * (different start/stop frequency, different RBW) almost always changes
 * the bin count, so this catches the common case without us having to
 * eavesdrop on the engine's frequency-plan listener. Same-bin-count
 * retunes (e.g. swapping antennas on the same span) keep the existing
 * accumulation - usually the right thing because the user wants the
 * comparison and a stale density chart from a previous span would just
 * be wrong frequencies anyway.
 *
 * <h2>Threading</h2>
 * {@link #onFrame} runs on the engine processing thread and is the only
 * writer; the snapshot is published via an {@link AtomicReference}.
 * Listeners receive the snapshot on the same engine thread - FX
 * consumers wrap their callbacks in {@code Platform.runLater}, just as
 * they do for {@link ChannelOccupancyService}.
 *
 * <h2>Hard caps</h2>
 * Width is capped at {@link #MAX_WIDTH} so a malformed frame report
 * cannot ask us to allocate gigabytes. Counts are clamped at
 * {@link Integer#MAX_VALUE} so extremely long accumulation runs cannot
 * overflow.
 */
public final class DensityHistogramService {

    private static final Logger LOG = LoggerFactory.getLogger(DensityHistogramService.class);

    /**
     * Top of the displayed dBm range (row 0). -20 dBm is generous enough
     * to cover even an antenna pressed against an AP without saturating
     * the chart, while still leaving most of the rows for the noise
     * floor where the action is.
     */
    public static final double TOP_DBM = -20d;

    /**
     * Bottom of the displayed dBm range, in dB below {@link #TOP_DBM}.
     * 100 dB at 1 dB per row gives a 100-pixel-tall density chart with
     * a usable -20..-120 dBm window, which matches the dynamic range
     * the HackRF can resolve in a typical Wi-Fi sweep.
     */
    public static final int HEIGHT_DBM = 100;

    /** Number of vertical bins (one per dB). */
    public static final int HEIGHT = HEIGHT_DBM;

    /**
     * Hard cap on grid width. Real Wi-Fi sweeps top out around ~6000
     * bins (5160 MHz span at the finest RBW); 16384 is a safe ceiling
     * that still allocates only ~6 MB ({@code 16384*100*4} bytes).
     *
     * <p>Frames wider than this are <strong>decimated</strong> (every
     * {@code stride}-th bin contributes, with a per-stride max-hold so
     * peaks survive). The previous behaviour was to silently truncate
     * to the cap, which hid the upper 60% of multi-range Wi-Fi sweeps
     * (e.g. 41312 bins for 2.4&nbsp;+&nbsp;5&nbsp;+&nbsp;6&nbsp;GHz @ 50&nbsp;kHz RBW)
     * and spammed one DEBUG log per frame.
     */
    public static final int MAX_WIDTH = 16_384;

    /**
     * Immutable snapshot delivered to listeners. {@code grid} is a
     * defensive copy in row-major order ({@code row*width + x} index);
     * {@code maxCount} is precomputed so the view never has to scan the
     * whole grid just to find the normaliser.
     */
    public record Snapshot(
            int width,
            int height,
            int[] grid,
            int maxCount,
            double startMHz,
            double stopMHz,
            double topDbm,
            double bottomDbm,
            long frameCount) {

        /** Cell count at column {@code x}, row {@code y} (0 = top). */
        public int countAt(int x, int y) {
            if (x < 0 || x >= width || y < 0 || y >= height) return 0;
            return grid[y * width + x];
        }
    }

    private final AtomicReference<Snapshot> latest =
            new AtomicReference<>(emptySnapshot());
    private final CopyOnWriteArrayList<Consumer<Snapshot>> listeners = new CopyOnWriteArrayList<>();

    /**
     * Mutated only on the engine thread inside {@link #onFrame} (and
     * {@link #reset()} which is also called from the engine thread via
     * the public method's {@code synchronized}). The snapshot publish
     * happens through {@link #latest}; nobody reads {@code grid}
     * concurrently with the writer, so no extra synchronisation is
     * needed on the array itself.
     */
    private int[] grid = new int[0];
    private int width = 0;
    private int maxCount = 0;
    private double startMHz = 0;
    private double stopMHz = 0;
    private long frameCount = 0;

    /**
     * Throttle for {@link #publishSnapshot()}. The grid keeps absorbing
     * every frame for accuracy; only the snapshot fan-out (defensive
     * copy of up to {@code MAX_WIDTH * HEIGHT * 4 bytes} ≈ 6 MB plus
     * one {@code Platform.runLater} per listener) is rate-limited.
     * 5 Hz is fast enough that the density chart still feels live but
     * slow enough that the FX queue cannot accumulate megabytes of
     * pending snapshots when the chart is slow to repaint - which is
     * exactly the failure mode that froze the app after a few minutes.
     */
    private static final long PUBLISH_INTERVAL_MS = 200;
    private long lastPublishMs = 0;

    /**
     * Stride used to fold a wide frame into the capped grid. Cached so
     * {@link #onFrame} doesn't recompute it (and re-log the warning)
     * on every frame when the bin layout is stable. Reset whenever the
     * grid is re-allocated.
     */
    private int decimationStride = 1;
    private int lastReportedFrameLen = -1;

    public DensityHistogramService(SpectrumEngine engine) {
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

    /**
     * Discard the accumulated histogram. The next frame rebuilds the
     * grid at the bin layout that frame reports. Safe to call from any
     * thread; callers typically wire it to a "Reset density" button.
     */
    public synchronized void reset() {
        Arrays.fill(grid, 0);
        maxCount = 0;
        frameCount = 0;
        publishSnapshot();
    }

    private void onFrame(SpectrumFrame frame) {
        if (frame == null || frame.dataset == null) return;
        DatasetSpectrum ds = frame.dataset;
        int frameLen = ds.spectrumLength();
        if (frameLen <= 0) return;
        float[] arr = ds.getSpectrumArray();
        if (arr == null || arr.length == 0) return;

        try {
            ensureCapacity(frameLen, ds);
            int stride = decimationStride;
            int local = 0;
            // Decimate by max-pooling: each output column accumulates
            // the strongest bin in its [stride] window. This preserves
            // peaks across the full span (the previous truncate-to-cap
            // dropped the upper portion of wide multi-range sweeps).
            for (int x = 0; x < width; x++) {
                int srcStart = x * stride;
                int srcEnd = srcStart + stride;
                if (srcEnd > frameLen) srcEnd = frameLen;
                if (srcStart >= srcEnd) break;
                double dbm = arr[srcStart];
                for (int j = srcStart + 1; j < srcEnd; j++) {
                    double v = arr[j];
                    if (v > dbm) dbm = v;
                }
                int row;
                if (dbm >= TOP_DBM) {
                    row = 0;
                } else if (dbm <= TOP_DBM - HEIGHT_DBM + 1) {
                    row = HEIGHT - 1;
                } else {
                    row = (int) (TOP_DBM - dbm);
                    if (row < 0) row = 0;
                    else if (row >= HEIGHT) row = HEIGHT - 1;
                }
                int idx = row * width + x;
                int v = grid[idx];
                if (v < Integer.MAX_VALUE) {
                    v++;
                    grid[idx] = v;
                    if (v > local) local = v;
                }
            }
            if (local > maxCount) maxCount = local;
            frameCount++;
            long now = System.currentTimeMillis();
            if (now - lastPublishMs >= PUBLISH_INTERVAL_MS) {
                lastPublishMs = now;
                publishSnapshot();
            }
        } catch (RuntimeException ex) {
            LOG.warn("DensityHistogram update failed", ex);
        }
    }

    /**
     * Resize / re-allocate the grid when the bin count of the incoming
     * frame differs from the current width. Also remembers the visible
     * MHz range so the snapshot can render an X-axis without the view
     * having to know about the dataset directly.
     */
    private void ensureCapacity(int frameLen, DatasetSpectrum ds) {
        // Compute stride needed to fold frameLen bins down to <= MAX_WIDTH
        // columns. stride=1 means 1:1 mapping (the common case).
        int stride = (frameLen + MAX_WIDTH - 1) / MAX_WIDTH;
        if (stride < 1) stride = 1;
        int targetWidth = (frameLen + stride - 1) / stride;
        if (targetWidth > MAX_WIDTH) targetWidth = MAX_WIDTH;

        if (frameLen != lastReportedFrameLen) {
            lastReportedFrameLen = frameLen;
            if (stride > 1) {
                // Log once per bin-layout change rather than per frame so
                // the console isn't drowned in DEBUG lines at sweep rate.
                LOG.info("DensityHistogram: {} bins exceeds cap {}; "
                        + "decimating with stride {} to {} columns "
                        + "(max-hold per group preserves peaks).",
                        frameLen, MAX_WIDTH, stride, targetWidth);
            }
        }

        if (targetWidth == width && stride == decimationStride
                && grid.length == width * HEIGHT) {
            // Bin layout unchanged - keep the accumulation but refresh
            // the cached MHz range so the view's X-axis labels stay in
            // sync with whatever the engine is actually sweeping.
            startMHz = ds.rfFrequencyMHzAt(0);
            stopMHz = ds.rfFrequencyMHzAt(frameLen - 1);
            return;
        }
        grid = new int[targetWidth * HEIGHT];
        width = targetWidth;
        decimationStride = stride;
        maxCount = 0;
        frameCount = 0;
        startMHz = ds.rfFrequencyMHzAt(0);
        stopMHz = ds.rfFrequencyMHzAt(frameLen - 1);
    }

    private void publishSnapshot() {
        // Defensive copy so listeners cannot stomp on the writer's array
        // (and so the snapshot stays internally consistent if a reset
        // happens between the publish and the listener actually
        // reading).
        int[] copy = Arrays.copyOf(grid, grid.length);
        Snapshot snap = new Snapshot(width, HEIGHT, copy, maxCount,
                startMHz, stopMHz, TOP_DBM, TOP_DBM - HEIGHT_DBM, frameCount);
        latest.set(snap);
        for (Consumer<Snapshot> l : listeners) {
            try {
                l.accept(snap);
            } catch (RuntimeException ex) {
                LOG.warn("Density listener threw", ex);
            }
        }
    }

    private static Snapshot emptySnapshot() {
        return new Snapshot(0, HEIGHT, new int[0], 0, 0, 0,
                TOP_DBM, TOP_DBM - HEIGHT_DBM, 0);
    }
}
