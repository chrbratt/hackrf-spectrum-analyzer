package jspectrumanalyzer.fx.engine;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

import jspectrumanalyzer.core.DatasetSpectrumPeak;
import jspectrumanalyzer.core.FFTBins;
import jspectrumanalyzer.core.FrequencyRange;
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

    private static final float SPECTRUM_INIT_POWER = -150f;

    private final SettingsStore settings;

    private final ArrayBlockingQueue<FFTBins> hwProcessingQueue = new ArrayBlockingQueue<>(1000);
    private final ArrayBlockingQueue<Integer> launchCommands = new ArrayBlockingQueue<>(1);
    private final ReentrantLock lock = new ReentrantLock();

    private final List<Consumer<SpectrumFrame>> frameConsumers = new CopyOnWriteArrayList<>();

    private volatile Thread launcherThread;
    private volatile Thread sweepThread;
    private volatile Thread processingThread;
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

    public void start() {
        startLauncherThread();
        wireSettingsObservers();
        restartSweep();
        Runtime.getRuntime().addShutdownHook(new Thread(this::shutdown, "SpectrumEngine-shutdown"));
    }

    public void shutdown() {
        shuttingDown = true;
        stopSweep();
        Thread t = launcherThread;
        if (t != null) {
            t.interrupt();
        }
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
            System.err.println("SpectrumEngine: queue full, dropping sample");
        }
    }

    private void wireSettingsObservers() {
        Runnable restart = this::restartSweep;
        settings.getFrequency().addListener(restart);
        settings.getFFTBinHz().addListener(restart);
        settings.getSamples().addListener(restart);
        settings.getGainLNA().addListener(restart);
        settings.getGainVGA().addListener(restart);
        settings.getAntennaPowerEnable().addListener(restart);
        settings.getAntennaLNA().addListener(restart);
        settings.getAvgIterations().addListener(restart);
        settings.getLogDetail().addListener(restart);
        settings.getVideoArea().addListener(restart);
        settings.getVideoFormat().addListener(restart);
        settings.getVideoResolution().addListener(restart);
        settings.getVideoFrameRate().addListener(restart);
    }

    private void startLauncherThread() {
        launcherThread = new Thread(() -> {
            Thread.currentThread().setName("SpectrumEngine-launcher");
            while (!shuttingDown) {
                try {
                    launchCommands.take();
                    restartSweepExecute();
                } catch (InterruptedException e) {
                    return;
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        });
        launcherThread.setDaemon(true);
        launcherThread.start();
    }

    /**
     * Queue a restart request. Repeated calls are coalesced so only the latest request
     * is honoured.
     */
    public synchronized void restartSweep() {
        if (!launchCommands.offer(0)) {
            launchCommands.clear();
            launchCommands.offer(0);
        }
    }

    private void restartSweepExecute() {
        stopSweep();
        sweepThread = new Thread(() -> {
            Thread.currentThread().setName("hackrf_sweep");
            try {
                forceStopSweep = false;
                sweepLoop();
            } catch (IOException e) {
                e.printStackTrace();
            }
        });
        sweepThread.start();
    }

    private void stopSweep() {
        forceStopSweep = true;
        Thread t = sweepThread;
        if (t != null) {
            while (t.isAlive()) {
                forceStopSweep = true;
                HackRFSweepNativeBridge.stop();
                try {
                    Thread.sleep(20);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            }
            try {
                t.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            sweepThread = null;
        }
        Thread p = processingThread;
        if (p != null) {
            p.interrupt();
            try {
                p.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            processingThread = null;
        }
    }

    private static final long SWEEP_FAIL_THRESHOLD_MS = 500;
    private static final long SWEEP_RETRY_MIN_MS = 1000;
    private static final long SWEEP_RETRY_MAX_MS = 15_000;

    private void sweepLoop() throws IOException {
        lock.lock();
        try {
            processingThread = new Thread(() -> {
                Thread.currentThread().setName("SpectrumEngine-processing");
                try {
                    runProcessing();
                } catch (InterruptedException e) {
                    // Expected: stopSweep() interrupts us on every settings change
                    // and on shutdown. Nothing to log.
                    Thread.currentThread().interrupt();
                } catch (Exception e) {
                    e.printStackTrace();
                }
            });
            processingThread.start();

            int consecutiveFailures = 0;
            while (!forceStopSweep) {
                FrequencyRange freq = settings.getFrequency().getValue();
                System.out.println("SpectrumEngine: starting hackrf_sweep "
                        + freq.getStartMHz() + "-" + freq.getEndMHz() + " MHz "
                        + " RBW " + settings.getFFTBinHz().getValue() + " kHz"
                        + " samples " + settings.getSamples().getValue()
                        + " lna " + settings.getGainLNA().getValue()
                        + " vga " + settings.getGainVGA().getValue());
                fireHardwareStateChanged(false);

                long started = System.nanoTime();
                HackRFSweepNativeBridge.start(this,
                        freq.getStartMHz(), freq.getEndMHz(),
                        settings.getFFTBinHz().getValue() * 1000,
                        settings.getSamples().getValue(),
                        settings.getGainLNA().getValue(),
                        settings.getGainVGA().getValue(),
                        settings.getAntennaPowerEnable().getValue(),
                        settings.getAntennaLNA().getValue());
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
                        System.err.println("SpectrumEngine: hackrf_sweep exited immediately "
                                + "(no device?). Backing off " + sleepMs + " ms before retry.");
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

        FrequencyRange freq = settings.getFrequency().getValue();
        datasetSpectrum = new DatasetSpectrumPeak(binHz,
                freq.getStartMHz(), freq.getEndMHz(),
                SPECTRUM_INIT_POWER,
                settings.getPeakFallTrs().getValue(),
                settings.getPeakFallRate().getValue() * 1000L,
                settings.getPeakHoldTime().getValue() * 1000L,
                settings.getFreqShift().getValue(),
                settings.getAvgIterations().getValue(),
                settings.getAvgOffset().getValue());

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
                    e.printStackTrace();
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
                e.printStackTrace();
            }
        }
    }
}
