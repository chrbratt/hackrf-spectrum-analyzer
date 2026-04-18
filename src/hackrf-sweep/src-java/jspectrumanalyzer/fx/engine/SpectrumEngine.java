package jspectrumanalyzer.fx.engine;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jspectrumanalyzer.core.DatasetSpectrumPeak;
import jspectrumanalyzer.core.FFTBins;
import jspectrumanalyzer.core.HackRFSettings.HackRFEventListener;
import jspectrumanalyzer.core.SpurFilter;
import jspectrumanalyzer.fx.model.SettingsStore;
import jspectrumanalyzer.nativebridge.HackRFSweepDataCallback;
import jspectrumanalyzer.nativebridge.HackRFSweepNativeBridge;

/**
 * Owns the hardware pipeline independent of any UI toolkit.
 * <p>
 * Responsibilities:
 * <ul>
 *   <li>Manage the launcher / sweep / processing threads (extracted from the legacy
 *       Swing {@code HackRFSweepSpectrumAnalyzer}).</li>
 *   <li>Run spur filtering, peak / average / max hold calculations on the processing
 *       thread.</li>
 *   <li>Publish a {@link SpectrumFrame} to registered consumers once per completed
 *       sweep. Consumers are invoked on the processing thread; they must forward work
 *       to their own UI thread (e.g. {@code Platform.runLater}) as needed.</li>
 * </ul>
 */
public final class SpectrumEngine implements HackRFSweepDataCallback {

    private static final Logger LOG = LoggerFactory.getLogger(SpectrumEngine.class);

    private static final float SPECTRUM_INIT_POWER = -150f;

    /**
     * Builds a single-thread {@link ExecutorService} whose worker is a
     * named daemon, with all uncaught exceptions routed through SLF4J.
     * Daemon flag is critical: if the native sweep DLL ever hangs in
     * {@code stopSweep}, the JVM can still exit on user logout.
     */
    private static ExecutorService namedSingleThread(String name) {
        ThreadFactory tf = r -> {
            Thread t = new Thread(r, name);
            t.setDaemon(true);
            t.setUncaughtExceptionHandler((thr, ex) ->
                    LOG.error("Uncaught exception in {}", thr.getName(), ex));
            return t;
        };
        return Executors.newSingleThreadExecutor(tf);
    }

    private final SettingsStore settings;

    private final ArrayBlockingQueue<FFTBins> hwProcessingQueue = new ArrayBlockingQueue<>(1000);
    private final ArrayBlockingQueue<Integer> launchCommands = new ArrayBlockingQueue<>(1);
    private final ReentrantLock lock = new ReentrantLock();

    private final List<Consumer<SpectrumFrame>> frameConsumers = new CopyOnWriteArrayList<>();

    /**
     * Three single-thread executors, one per concurrent role. They are
     * created up-front and shut down once on engine shutdown; sweep and
     * processing tasks are submitted/cancelled per restart cycle. Keeping
     * one executor per role (rather than a shared cached pool) means
     * thread names in jstack / profilers tell us exactly which subsystem
     * is busy, and a misbehaving worker can't starve the others.
     */
    private final ExecutorService launcherExec   = namedSingleThread("SpectrumEngine-launcher");
    private final ExecutorService sweepExec      = namedSingleThread("hackrf_sweep");
    private final ExecutorService processingExec = namedSingleThread("SpectrumEngine-processing");

    private volatile Future<?> launcherFuture;
    private volatile Future<?> sweepFuture;
    private volatile Future<?> processingFuture;
    private volatile boolean forceStopSweep = false;
    private volatile boolean shuttingDown = false;
    private volatile boolean hwSendingData = false;

    private volatile DatasetSpectrumPeak datasetSpectrum;
    private volatile SpurFilter spurFilter;

    public SpectrumEngine(SettingsStore settings) {
        this.settings = settings;
    }

    public void addFrameConsumer(Consumer<SpectrumFrame> consumer) {
        frameConsumers.add(consumer);
    }

    /**
     * Bring the engine online but do <strong>not</strong> start streaming.
     * The user is expected to flip {@code settings.isRunningRequested()} to
     * {@code true} via the UI Start/Stop toggle - the listener wired below
     * will then call {@link #restartSweep()}. This guarantees the app boots
     * without grabbing the radio (so device selection works first).
     */
    public void start() {
        startLauncherThread();
        wireSettingsObservers();
        wireRunStateObserver();
        wireMaxHoldDecayObserver();
        Runtime.getRuntime().addShutdownHook(new Thread(this::shutdown, "SpectrumEngine-shutdown"));
    }

    /**
     * Push the user-configured max-hold lifetime straight to the dataset
     * without restarting the sweep. The dataset's setter is volatile and
     * the processing thread re-reads it once per sweep, so changes apply
     * within one frame of the next paint.
     */
    private void wireMaxHoldDecayObserver() {
        Runnable apply = () -> {
            DatasetSpectrumPeak ds = datasetSpectrum;
            if (ds != null) {
                ds.setMaxHoldFalloutMillis(settings.getMaxHoldDecaySeconds().getValue() * 1000L);
            }
        };
        settings.getMaxHoldDecaySeconds().addListener(apply);
    }

    public void shutdown() {
        shuttingDown = true;
        stopSweep();
        Future<?> lf = launcherFuture;
        if (lf != null) lf.cancel(true);
        // shutdownNow interrupts the worker (which our launcher loop
        // checks via Thread.interrupted()) and prevents new submits.
        launcherExec.shutdownNow();
        sweepExec.shutdownNow();
        processingExec.shutdownNow();
    }

    public DatasetSpectrumPeak getDatasetSpectrum() {
        return datasetSpectrum;
    }

    /**
     * Reset peaks, max-hold and average traces so that historical peaks from a
     * previous frequency range or before the user changed gain stop bleeding
     * into the current display. Safe to call from any thread.
     */
    public void resetTraces() {
        DatasetSpectrumPeak ds = datasetSpectrum;
        if (ds != null) {
            ds.resetPeaks();
            ds.resetMaxHold();
            ds.resetAverage();
        }
    }

    @Override
    public void newSpectrumData(boolean sweepStarted, double[] frequencyStart,
                                float fftBinWidthHz, float[] signalPowerdBm) {
        fireHardwareStateChanged(true);
        if (!hwProcessingQueue.offer(new FFTBins(sweepStarted, frequencyStart, fftBinWidthHz, signalPowerdBm))) {
            LOG.warn("Processing queue full, dropping sample (UI thread is too slow)");
        }
    }

    private void wireSettingsObservers() {
        // Per the agreed UX: while STOPPED, settings changes do nothing
        // (the user can adjust freely without grabbing the radio); while
        // RUNNING, they restart the sweep just like the legacy behaviour.
        Runnable restartIfRunning = this::restartSweepIfRunning;
        settings.getFrequency().addListener(restartIfRunning);
        // A change in the multi-range plan (preset switch, single->multi or
        // back) needs the same treatment as a single-range change: restart
        // the sweep so the new segment list is pushed to libhackrf.
        settings.getFrequencyPlan().addListener(restartIfRunning);
        settings.getFFTBinHz().addListener(restartIfRunning);
        settings.getSamples().addListener(restartIfRunning);
        settings.getGainLNA().addListener(restartIfRunning);
        settings.getGainVGA().addListener(restartIfRunning);
        settings.getAntennaPowerEnable().addListener(restartIfRunning);
        settings.getAntennaLNA().addListener(restartIfRunning);
        settings.getAvgIterations().addListener(restartIfRunning);

        // Picking a different physical device requires fully re-opening
        // libhackrf, which is exactly what restartSweep() does anyway.
        settings.getSelectedSerial().addListener(restartIfRunning);
    }

    /**
     * Bridges the {@code runningRequested} model bool to the actual
     * sweep lifecycle. The model owns the truth ("is the user asking us to
     * stream?"); the engine simply queues a reconcile-to-state request on
     * the launcher thread so the FX thread never blocks on
     * {@link #stopSweep()} (which can take seconds when waiting for the
     * native side to shut down).
     */
    private void wireRunStateObserver() {
        settings.isRunningRequested().addListener(this::requestReconcile);
    }

    private void restartSweepIfRunning() {
        if (settings.isRunningRequested().getValue()) {
            requestReconcile();
        }
    }

    private void startLauncherThread() {
        launcherFuture = launcherExec.submit(() -> {
            while (!shuttingDown && !Thread.currentThread().isInterrupted()) {
                try {
                    launchCommands.take();
                    reconcileToRunningRequested();
                } catch (InterruptedException e) {
                    return;
                } catch (Exception e) {
                    LOG.error("Launcher thread reconcile failed", e);
                }
            }
        });
    }

    /**
     * Queue a reconcile request: the launcher thread will read
     * {@code settings.isRunningRequested()} and either (re)start or stop
     * the sweep accordingly. Repeated calls are coalesced - only the latest
     * request is honoured, so spamming Stop or rapid setting changes can
     * never queue up multiple worker restarts.
     */
    public synchronized void requestReconcile() {
        if (!launchCommands.offer(0)) {
            launchCommands.clear();
            launchCommands.offer(0);
        }
    }

    /** Backwards-compatible alias used by callers that meant "restart". */
    public synchronized void restartSweep() {
        requestReconcile();
    }

    /**
     * Runs on the launcher thread. Brings the sweep into the state the model
     * asks for - blocking I/O (native shutdown, thread joins) happens here,
     * never on the FX thread.
     */
    private void reconcileToRunningRequested() {
        if (settings.isRunningRequested().getValue()) {
            restartSweepExecute();
        } else {
            stopSweep();
            fireHardwareStateChanged(false);
        }
    }

    private void restartSweepExecute() {
        stopSweep();
        sweepFuture = sweepExec.submit(() -> {
            try {
                forceStopSweep = false;
                sweepLoop();
            } catch (IOException e) {
                LOG.error("Sweep loop crashed", e);
            }
        });
    }

    /** Cap on how long we will wait for a single native stop attempt. */
    private static final long NATIVE_STOP_JOIN_MS = 2_000;
    /** How many times we re-issue {@code hackrf_sweep_lib_stop()} before giving up. */
    private static final int  NATIVE_STOP_RETRIES = 5;

    private void stopSweep() {
        forceStopSweep = true;
        Future<?> sf = sweepFuture;
        if (sf != null) {
            // The legacy code spun in a 20 ms sleep loop hammering
            // hackrf_sweep_lib_stop() until the worker exited. We instead
            // ask the native side to stop, then block on Future.get with a
            // bounded timeout. If the worker still hasn't exited after a
            // few rounds we log, cancel(true) (interrupt) and move on so a
            // misbehaving DLL can never freeze the launcher (and hence
            // the Stop button).
            for (int i = 0; i < NATIVE_STOP_RETRIES && !sf.isDone(); i++) {
                HackRFSweepNativeBridge.stop();
                if (waitFor(sf, NATIVE_STOP_JOIN_MS)) break;
            }
            if (!sf.isDone()) {
                LOG.warn("hackrf_sweep worker did not exit after {} ms; cancelling",
                        NATIVE_STOP_RETRIES * NATIVE_STOP_JOIN_MS);
                sf.cancel(true);
            }
            sweepFuture = null;
        }
        Future<?> pf = processingFuture;
        if (pf != null) {
            pf.cancel(true); // delivers interrupt; runProcessing checks isInterrupted
            if (!waitFor(pf, NATIVE_STOP_JOIN_MS)) {
                LOG.warn("Processing thread did not exit within {} ms; abandoning",
                        NATIVE_STOP_JOIN_MS);
            }
            processingFuture = null;
        }
    }

    /**
     * Block up to {@code timeoutMs} for {@code f} to finish. Returns true
     * when the future completed (normally, exceptionally or via cancel),
     * false on timeout. Interrupted state is preserved on the caller.
     */
    private static boolean waitFor(Future<?> f, long timeoutMs) {
        try {
            f.get(timeoutMs, TimeUnit.MILLISECONDS);
            return true;
        } catch (TimeoutException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return f.isDone();
        } catch (ExecutionException e) {
            // Worker threw; logged at the source. We still treat the
            // future as "finished" because the thread is gone.
            return true;
        } catch (java.util.concurrent.CancellationException e) {
            return true;
        }
    }

    private static final long SWEEP_FAIL_THRESHOLD_MS = 500;
    private static final long SWEEP_RETRY_MIN_MS = 1000;
    private static final long SWEEP_RETRY_MAX_MS = 15_000;

    private void sweepLoop() throws IOException {
        lock.lock();
        try {
            processingFuture = processingExec.submit(() -> {
                try {
                    runProcessing();
                } catch (InterruptedException e) {
                    // Expected: stopSweep() cancels us on every settings change
                    // and on shutdown. Restore interrupt flag and exit silently.
                    Thread.currentThread().interrupt();
                } catch (Exception e) {
                    LOG.error("Processing thread crashed", e);
                }
            });

            int consecutiveFailures = 0;
            while (!forceStopSweep) {
                // getEffectivePlan() folds the legacy single-range UI and the
                // multi-range preset into one source of truth so the engine
                // doesn't care which path drove the change.
                jspectrumanalyzer.core.FrequencyPlan plan = settings.getEffectivePlan();
                LOG.info("Starting hackrf_sweep {} RBW {} kHz samples {} lna {} vga {}",
                        plan,
                        settings.getFFTBinHz().getValue(),
                        settings.getSamples().getValue(),
                        settings.getGainLNA().getValue(),
                        settings.getGainVGA().getValue());
                fireHardwareStateChanged(false);

                long started = System.nanoTime();
                HackRFSweepNativeBridge.start(this,
                        plan,
                        settings.getFFTBinHz().getValue() * 1000,
                        settings.getSamples().getValue(),
                        settings.getGainLNA().getValue(),
                        settings.getGainVGA().getValue(),
                        settings.getAntennaPowerEnable().getValue(),
                        settings.getAntennaLNA().getValue(),
                        settings.getSelectedSerial().getValue());
                long elapsedMs = (System.nanoTime() - started) / 1_000_000L;
                fireHardwareStateChanged(false);

                if (forceStopSweep) break;

                // If hackrf_sweep returns almost immediately, hackrf_open() failed
                // (device busy / unplugged / invalid params). Back off exponentially
                // so we don't spam the native usage-text at 1 Hz.
                long sleepMs;
                if (elapsedMs < SWEEP_FAIL_THRESHOLD_MS) {
                    consecutiveFailures = Math.min(consecutiveFailures + 1, 10);
                    sleepMs = Math.min(SWEEP_RETRY_MAX_MS,
                            SWEEP_RETRY_MIN_MS * (1L << Math.min(consecutiveFailures - 1, 4)));
                    if (consecutiveFailures == 1) {
                        LOG.warn("hackrf_sweep exited immediately (no device?). "
                                + "Backing off {} ms before retry.", sleepMs);
                    }
                } else {
                    consecutiveFailures = 0;
                    sleepMs = SWEEP_RETRY_MIN_MS;
                }
                try {
                    Thread.sleep(sleepMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        } finally {
            lock.unlock();
            fireHardwareStateChanged(false);
        }
    }

    private void runProcessing() throws InterruptedException {
        FFTBins bin1 = hwProcessingQueue.take();
        float binHz = bin1.fftBinWidthHz;

        // Use the same plan the native side was started with - keeps the
        // dataset's bin layout in lockstep with the segments the sweep is
        // actually producing samples for.
        jspectrumanalyzer.core.FrequencyPlan plan = settings.getEffectivePlan();
        datasetSpectrum = new DatasetSpectrumPeak(binHz,
                plan,
                SPECTRUM_INIT_POWER,
                settings.getPeakFallTrs().getValue(),
                settings.getPeakFallRate().getValue() * 1000L,
                settings.getPeakHoldTime().getValue() * 1000L,
                settings.getFreqShift().getValue(),
                settings.getAvgIterations().getValue(),
                settings.getAvgOffset().getValue());
        // Apply the current decay setting straight away so the very first
        // sweep after restart respects the user's choice (otherwise the
        // dataset would default to "infinite hold" until the next change).
        datasetSpectrum.setMaxHoldFalloutMillis(
                settings.getMaxHoldDecaySeconds().getValue() * 1000L);

        float maxPeakJitterdB = 6;
        float peakThresholdAboveNoise = 4;
        int maxPeakBins = 4;
        int validIterations = 25;
        spurFilter = new SpurFilter(maxPeakJitterdB, peakThresholdAboveNoise, maxPeakBins,
                validIterations, datasetSpectrum);

        while (!Thread.currentThread().isInterrupted()) {
            FFTBins bins = hwProcessingQueue.take();
            if (settings.isCapturingPaused().getValue()) {
                continue;
            }

            boolean triggerChartRefresh = bins.fullSweepDone;
            if (bins.freqStart != null && bins.sigPowdBm != null) {
                int ampOffset = settings.getAmplitudeOffset().getValue();
                for (int i = 0; i < bins.sigPowdBm.length; i++) {
                    bins.sigPowdBm[i] -= (30 - ampOffset);
                }
                datasetSpectrum.addNewData(bins);
            }

            if (!triggerChartRefresh) {
                continue;
            }

            if (settings.isSpurRemoval().getValue()) {
                spurFilter.filterDataset();
            }

            double peakAmp = 0, peakFreq = 0, totalPower = 0, maxHoldAmp = 0, maxHoldFreq = 0;
            String powerFlux = "";

            boolean showPeaks = settings.isChartsPeaksVisible().getValue();
            if (showPeaks) {
                datasetSpectrum.refreshPeakSpectrum();
                double[] spp = datasetSpectrum.calculateSpectrumPeakPower(
                        settings.getPowerFluxCal().getValue());
                totalPower = spp[0];
                peakAmp = spp[1];
                peakFreq = spp[2];
                powerFlux = String.valueOf(spp[3]);
            }

            boolean showAverage = settings.isChartsAverageVisible().getValue();
            if (showAverage) {
                datasetSpectrum.refreshAverageSpectrum();
            }

            boolean showMaxHold = settings.isChartsMaxHoldVisible().getValue();
            if (showMaxHold) {
                datasetSpectrum.refreshMaxHoldSpectrum();
                if (settings.isMaxHoldMarkerVisible().getValue()) {
                    double[] mh = datasetSpectrum.calculateMarkerHold();
                    maxHoldAmp = mh[0];
                    maxHoldFreq = mh[1];
                }
            }

            boolean showRealtime = settings.isChartsRealtimeVisible().getValue();
            // Snapshot once per frame so the FX-thread chart render and the
            // processing thread don't fight over the same float arrays.
            datasetSpectrum.snapshotForChart(showPeaks, showAverage, showMaxHold, showRealtime);

            SpectrumFrame frame = new SpectrumFrame(datasetSpectrum,
                    peakAmp, peakFreq, totalPower, powerFlux,
                    maxHoldAmp, maxHoldFreq,
                    showPeaks, showAverage, showMaxHold,
                    showRealtime);

            for (Consumer<SpectrumFrame> consumer : frameConsumers) {
                try {
                    consumer.accept(frame);
                } catch (Exception e) {
                    LOG.error("Frame consumer threw", e);
                }
            }
        }
    }

    private void fireHardwareStateChanged(boolean sendingData) {
        if (this.hwSendingData == sendingData) {
            return;
        }
        this.hwSendingData = sendingData;
        List<HackRFEventListener> listeners = settings.snapshotListeners();
        for (HackRFEventListener listener : listeners) {
            try {
                listener.hardwareStatusChanged(sendingData);
            } catch (Exception e) {
                LOG.error("HW state listener threw", e);
            }
        }
    }
}
