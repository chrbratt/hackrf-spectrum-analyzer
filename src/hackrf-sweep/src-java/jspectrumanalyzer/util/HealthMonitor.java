package jspectrumanalyzer.util;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import javafx.application.Platform;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import jspectrumanalyzer.fx.engine.SpectrumEngine;

/**
 * Lightweight periodic health probe that prints a one-line summary of
 * runtime conditions every {@link #DEFAULT_INTERVAL_SEC} seconds. Aims
 * to make performance regressions and lockups visible from
 * {@code .\gradlew run} console output without attaching a profiler.
 *
 * <h2>What it samples</h2>
 * <ul>
 *   <li><b>Heap</b> - used / max in MiB. Steadily climbing = leak.</li>
 *   <li><b>GC</b> - total GC time spent during the last interval.
 *       Big numbers (&gt; 500 ms / 5 s) = collector under pressure,
 *       expect stutter.</li>
 *   <li><b>FX latency</b> - round-trip of an empty
 *       {@link Platform#runLater}. Above ~250 ms means the FX thread
 *       is overloaded; that is the symptom that precedes a freeze.</li>
 *   <li><b>Sweep rate</b> - frames/sec actually produced by the
 *       engine processing thread. 0 fps while running = lockup
 *       (engine consumer is hung or stuck on a frame consumer).</li>
 *   <li><b>Queue depth</b> - {@code current/capacity} on the engine
 *       producer queue. At/near capacity means the consumer can't
 *       keep up with libhackrf's push rate.</li>
 *   <li><b>Drops</b> - new dropped-sample count this interval. Any
 *       non-zero value is a hint that the consumer is overloaded
 *       even if the queue is currently shallow (drops happen when
 *       the queue rejects an offer).</li>
 * </ul>
 *
 * <h2>Output format</h2>
 * <pre>
 * [health] heap 234/512 MiB (45%), GC 12ms/5s, FX lat 3ms, sweep 31fps, q 4/1000, drops +0
 * </pre>
 * Lines exceeding any of the configured warn thresholds are logged at
 * {@code WARN} and prefixed with a {@code !} marker per dimension; the
 * normal cadence is {@code INFO}.
 *
 * <h2>Disabling</h2>
 * The monitor is on by default; pass
 * {@code -Dhealth.disable=true} on the JVM command line to skip it
 * entirely. The interval is configurable via
 * {@code -Dhealth.intervalSec=N} (default 5).
 *
 * <h2>Cost</h2>
 * One scheduled task on a dedicated daemon thread, awakening every N
 * seconds. Each tick reads JMX counters (cheap) and posts one
 * {@code Platform.runLater}. Total overhead is in the microseconds
 * per tick; never on the hot path.
 */
public final class HealthMonitor {

    private static final Logger LOG = LoggerFactory.getLogger(HealthMonitor.class);

    public static final int DEFAULT_INTERVAL_SEC = 5;

    /** Warn threshold: heap used / heap max. */
    private static final double HEAP_WARN_FRACTION = 0.85;
    /** Warn threshold: cumulative GC time per interval, milliseconds. */
    private static final long GC_WARN_MS_PER_INTERVAL = 500L;
    /** Warn threshold: FX-thread runLater round-trip, milliseconds. */
    private static final long FX_LATENCY_WARN_MS = 250L;
    /** Warn threshold: queue fill ratio. */
    private static final double QUEUE_WARN_FRACTION = 0.50;

    private final SpectrumEngine engine;
    private final ScheduledExecutorService scheduler;
    private final long intervalSec;

    private final MemoryMXBean memBean = ManagementFactory.getMemoryMXBean();
    private final List<GarbageCollectorMXBean> gcBeans =
            ManagementFactory.getGarbageCollectorMXBeans();

    private long lastGcMillis = totalGcMillis();
    private long lastFramesProduced;
    private long lastDroppedSamples;
    private long lastChunksReceived;
    private long lastTickNs = System.nanoTime();

    public HealthMonitor(SpectrumEngine engine) {
        this(engine, intervalFromSysProp());
    }

    public HealthMonitor(SpectrumEngine engine, long intervalSec) {
        this.engine = engine;
        this.intervalSec = Math.max(1, intervalSec);
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "HealthMonitor");
            t.setDaemon(true);
            return t;
        });
    }

    /** Read {@code -Dhealth.intervalSec=N}, default {@link #DEFAULT_INTERVAL_SEC}. */
    private static long intervalFromSysProp() {
        String raw = System.getProperty("health.intervalSec");
        if (raw == null) return DEFAULT_INTERVAL_SEC;
        try {
            return Math.max(1, Long.parseLong(raw.trim()));
        } catch (NumberFormatException ex) {
            return DEFAULT_INTERVAL_SEC;
        }
    }

    /**
     * Begin sampling. Returns immediately; the first tick fires
     * {@link #intervalSec} seconds later. No-op (and logs at INFO) if
     * the {@code health.disable} system property is true.
     */
    public void start() {
        if (Boolean.getBoolean("health.disable")) {
            LOG.info("[health] disabled via -Dhealth.disable=true");
            return;
        }
        // Snapshot baselines once so the first tick reports a sensible
        // delta rather than the full lifetime totals.
        lastGcMillis = totalGcMillis();
        lastFramesProduced = engine.getFramesProducedCount();
        lastDroppedSamples = engine.getDroppedSampleCount();
        lastChunksReceived = engine.getChunksReceivedCount();
        lastTickNs = System.nanoTime();
        scheduler.scheduleAtFixedRate(this::tick,
                intervalSec, intervalSec, TimeUnit.SECONDS);
        LOG.info("[health] monitor started, interval {}s "
                + "(disable with -Dhealth.disable=true)", intervalSec);
    }

    public void stop() {
        scheduler.shutdownNow();
    }

    private void tick() {
        try {
            sampleAndLog();
        } catch (Throwable t) {
            // Never let monitor exceptions propagate into the
            // scheduler, that would kill the recurring task silently.
            LOG.warn("[health] tick failed", t);
        }
    }

    private void sampleAndLog() {
        long now = System.nanoTime();
        double elapsedSec = Math.max(0.001, (now - lastTickNs) / 1e9);
        lastTickNs = now;

        // Heap.
        MemoryUsage heap = memBean.getHeapMemoryUsage();
        long heapUsedMiB = heap.getUsed() >> 20;
        long heapMaxMiB = (heap.getMax() <= 0 ? heap.getCommitted() : heap.getMax()) >> 20;
        double heapFraction = heapMaxMiB > 0 ? (double) heapUsedMiB / heapMaxMiB : 0;
        boolean heapWarn = heapFraction >= HEAP_WARN_FRACTION;

        // GC delta.
        long gcNow = totalGcMillis();
        long gcDelta = gcNow - lastGcMillis;
        lastGcMillis = gcNow;
        boolean gcWarn = gcDelta >= GC_WARN_MS_PER_INTERVAL;

        // Engine counters.
        long framesNow = engine.getFramesProducedCount();
        long framesDelta = framesNow - lastFramesProduced;
        lastFramesProduced = framesNow;
        long fps = Math.round(framesDelta / elapsedSec);

        long chunksNow = engine.getChunksReceivedCount();
        long chunksDelta = chunksNow - lastChunksReceived;
        lastChunksReceived = chunksNow;
        long chunksPerSec = Math.round(chunksDelta / elapsedSec);

        long dropsNow = engine.getDroppedSampleCount();
        long dropsDelta = dropsNow - lastDroppedSamples;
        lastDroppedSamples = dropsNow;

        int qSize = engine.getProcessingQueueSize();
        int qCap = engine.getProcessingQueueCapacity();
        boolean qWarn = qCap > 0 && (double) qSize / qCap >= QUEUE_WARN_FRACTION;

        boolean paused  = engine.isCapturingPaused();
        boolean running = engine.isRunningRequested();
        // Diagnostic: only flag "0 fps" as a problem if the user
        // actually wants the sweep running and isn't paused. Otherwise
        // 0 fps is the correct, expected state.
        boolean engineWedged = running && !paused
                && fps == 0L
                && (chunksPerSec > 0 || framesNow > 0);
        // Diagnostic: callback silence while we expected data is the
        // strongest signal that the native side stopped delivering.
        boolean nativeSilent = running && !paused
                && chunksPerSec == 0
                && chunksNow > 0;

        // FX latency: post an empty runLater, measure round-trip.
        // Run async so the monitor thread doesn't block on FX, but
        // if FX is so wedged that the previous tick's probe never
        // returned we still record that fact via fxLatencyMs.
        long fxLatencyMs = measureFxLatency();
        boolean fxWarn = fxLatencyMs >= FX_LATENCY_WARN_MS;

        boolean anyWarn = heapWarn || gcWarn || fxWarn || qWarn
                || dropsDelta > 0 || engineWedged || nativeSilent;
        // Tag at the end captures the engine state that explains a 0-fps
        // line: "paused" (deliberate), "stopped" (Start not pressed), or
        // empty (running normally).
        String stateTag;
        if (!running)      stateTag = " [stopped]";
        else if (paused)   stateTag = " [paused]";
        else if (nativeSilent) stateTag = " [no native callbacks!]";
        else if (engineWedged) stateTag = " [consumer hung!]";
        else stateTag = "";
        String line = String.format(
                "[health] heap %d/%d MiB (%d%%)%s, GC %dms/%ds%s, FX lat %dms%s, "
                + "sweep %dfps%s, chunks %d/s, q %d/%d%s, drops +%d%s%s",
                heapUsedMiB, heapMaxMiB, (int) Math.round(heapFraction * 100),
                heapWarn ? "!" : "",
                gcDelta, intervalSec, gcWarn ? "!" : "",
                fxLatencyMs, fxWarn ? "!" : "",
                fps, engineWedged ? "!" : "",
                chunksPerSec,
                qSize, qCap, qWarn ? "!" : "",
                dropsDelta, dropsDelta > 0 ? "!" : "",
                stateTag);
        if (anyWarn) {
            LOG.warn(line);
        } else {
            LOG.info(line);
        }
    }

    private long totalGcMillis() {
        long total = 0;
        for (GarbageCollectorMXBean b : gcBeans) {
            long t = b.getCollectionTime();
            if (t > 0) total += t;
        }
        return total;
    }

    /**
     * Measure FX-thread responsiveness. Posts a no-op runLater and
     * blocks the monitor thread for up to 1 s waiting for it to fire.
     * Returns the round-trip in ms, or 1000 if the FX thread didn't
     * answer within the budget (which itself is the signal we care
     * about - a wedged FX thread shows up as "FX lat 1000ms!").
     */
    private long measureFxLatency() {
        AtomicLong fxAt = new AtomicLong(-1);
        long submitNs = System.nanoTime();
        try {
            Platform.runLater(() -> fxAt.set(System.nanoTime()));
        } catch (IllegalStateException ex) {
            // FX toolkit not started or already shut down. Reporting
            // 0 here keeps the line readable; the absence of any FX
            // work is itself diagnostic.
            return 0L;
        }
        long deadline = submitNs + TimeUnit.SECONDS.toNanos(1);
        while (fxAt.get() < 0 && System.nanoTime() < deadline) {
            try {
                Thread.sleep(2);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        long answered = fxAt.get();
        if (answered < 0) return 1000L;
        return Math.max(0L, (answered - submitNs) / 1_000_000L);
    }
}
