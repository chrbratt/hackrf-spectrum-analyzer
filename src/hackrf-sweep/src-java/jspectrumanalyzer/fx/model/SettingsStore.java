package jspectrumanalyzer.fx.model;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import jspectrumanalyzer.core.FrequencyAllocationTable;
import jspectrumanalyzer.core.FrequencyAllocations;
import jspectrumanalyzer.core.FrequencyRange;
import jspectrumanalyzer.core.HackRFSettings;
import shared.mvc.ModelValue;
import shared.mvc.ModelValue.ModelValueBoolean;
import shared.mvc.ModelValue.ModelValueInt;

/**
 * Pure in-memory implementation of {@link HackRFSettings}.
 * <p>
 * Extracted from the legacy Swing {@code HackRFSweepSpectrumAnalyzer} class so that the
 * model can be consumed by both the Swing UI (legacy) and the JavaFX UI (new) without
 * duplicating parameter declarations.
 */
public class SettingsStore implements HackRFSettings {

    public static final int FREQ_MIN_MHZ = 0;
    public static final int FREQ_MAX_MHZ = 7250;

    // Defaults reflect the most common starting point for this app: scanning
    // the 2.4 GHz Wi-Fi / Bluetooth band with the internal RF amp on and
    // moderate LNA/VGA. Users can change anything but they shouldn't have to
    // tune five knobs before the first sweep shows useful data.
    private final ModelValueBoolean antennaLNA = new ModelValueBoolean("RF amp", true);
    private final ModelValueBoolean antennaPower = new ModelValueBoolean("Antenna Power", false);
    private final ModelValueInt fftBinHz = new ModelValueInt("RBW", 50);
    private final ModelValueBoolean filterSpectrum = new ModelValueBoolean("Filter", false);
    private final ModelValue<FrequencyRange> frequency =
            new ModelValue<>("Frequency Range", new FrequencyRange(2400, 2483));
    private final ModelValue<FrequencyAllocationTable> frequencyAllocationTable =
            new ModelValue<>("Frequency Allocation Table", loadDefaultAllocationTable());
    private final ModelValueInt gainLNA = new ModelValueInt("LNA Gain", 24, 8, 0, 40);
    private final ModelValueInt gainTotal = new ModelValueInt("Gain", 52);
    private final ModelValueInt gainVGA = new ModelValueInt("VGA Gain", 8, 2, 0, 62);
    private final ModelValueBoolean isCapturingPaused = new ModelValueBoolean("Capturing Paused", false);
    private final ModelValueBoolean isRecordedVideo = new ModelValueBoolean("Recording Video", false);
    private final ModelValueBoolean isRecordedData = new ModelValueBoolean("Recording Data", false);
    private final ModelValueInt persistentDisplayDecayRate =
            new ModelValueInt("Persistence Time", 5);
    private final ModelValueInt peakFallRateSecs = new ModelValueInt("Peak Fall Rate", 5);
    private final ModelValueInt peakFallThreshold = new ModelValueInt("Peak Fall Threshold", 2);
    private final ModelValueInt peakHoldTime = new ModelValueInt("Peak Hold Time", 0);
    private final ModelValueBoolean persistentDisplay = new ModelValueBoolean("Persistent display", false);
    private final ModelValueInt samples = new ModelValueInt("Samples", 8192);
    private final ModelValueInt freqShift = new ModelValueInt("FreqShift", 0);
    private final ModelValueBoolean datestamp = new ModelValueBoolean("Datestamp", true);
    private final ModelValueBoolean showRealtime = new ModelValueBoolean("Show Realtime", false);
    private final ModelValueBoolean showAverage = new ModelValueBoolean("Show Average", false);
    private final ModelValueBoolean showPeaks = new ModelValueBoolean("Show Peaks", true);
    // Off by default: max-hold is a recording-style trace that confuses new
    // users when it lights up the spectrum from the very first sweep. It also
    // hides current realtime peaks. Users who want it can enable it on the
    // Params tab, where the decay timeout (below) is also exposed.
    private final ModelValueBoolean showMaxHold = new ModelValueBoolean("Show MaxHold", false);
    // Per-bin lifetime for the max-hold trace. 0 = legacy "infinite hold"
    // behaviour, otherwise each bin's held value resets to the current
    // sample after this many seconds without being beaten. Default 10 s
    // matches the user's request and keeps the trace usable in environments
    // where the spectrum drifts.
    private final ModelValueInt maxHoldDecaySeconds =
            new ModelValueInt("MaxHold Decay", 10, 1, 0, 3600);
    private final ModelValueBoolean showPeakMarker = new ModelValueBoolean("Show PeakMarker", false);
    private final ModelValueBoolean showMaxHoldMarker = new ModelValueBoolean("Show MaxHoldMarker", false);
    private final ModelValueBoolean debugDisplay = new ModelValueBoolean("Debug", false);
    private final ModelValue<BigDecimal> spectrumLineThickness =
            new ModelValue<>("Spectrum Line Thickness", new BigDecimal("1"));
    // Step 5 dB / 10 dB matches the granularity that's actually visually
    // meaningful on the waterfall colour ramp. Bounded so the slider snaps
    // to clean values and the tick labels stay readable in the narrow sidebar.
    // Palette defaults to the -100..-50 dBm window: that's where almost every
    // intentional terrestrial signal lives once a real antenna is plugged in,
    // so the waterfall paints meaningful colour out of the box. Step granularity
    // (5 / 10) is preserved so the slider snaps to clean labels.
    private final ModelValueInt spectrumPaletteSize = new ModelValueInt("Palette Size", 50, 5, 5, 150);
    private final ModelValueInt spectrumPaletteStart = new ModelValueInt("Palette Start", -100, 10, -150, 0);
    private final ModelValueInt amplitudeOffset = new ModelValueInt("Amplitude Offset", 0);
    private final ModelValueInt powerFluxCal = new ModelValueInt("Power Flux Calibration", 50);
    private final ModelValueInt avgIterations = new ModelValueInt("Average Iterations", 20);
    private final ModelValueInt avgOffset = new ModelValueInt("Average Offset", 0);
    private final ModelValueInt waterfallSpeed = new ModelValueInt("Waterfall Speed", 4);
    private final ModelValueBoolean spurRemoval = new ModelValueBoolean("Spur Removal", false);
    private final ModelValueBoolean waterfallVisible = new ModelValueBoolean("Waterfall Visible", true);

    // Device selection lives in the model (not in SpectrumEngine) so any UI
    // that needs to react - e.g. the status bar showing the active board name
    // when Start is pressed - can listen on it the same way as on RBW or gain.
    //
    // selectedSerial: empty string means "first available device" (matches
    //   what the native bridge passes to hackrf_open_by_serial).
    // runningRequested: false at startup so the user explicitly clicks Start
    //   in the new UI; SpectrumEngine no longer auto-starts on boot.
    private final ModelValue<String> selectedSerial = new ModelValue<>("Selected Serial", "");
    private final ModelValueBoolean runningRequested =
            new ModelValueBoolean("Sweep Running", false);
    private final ModelValue<String> logDetail = new ModelValue<>("Data Log Interval", "SEC");
    private final ModelValue<String> videoArea = new ModelValue<>("Video Area", "SPEC");
    private final ModelValue<String> videoFormat = new ModelValue<>("Video Format", "GIF");
    private final ModelValueInt videoResolution = new ModelValueInt("Video Resolution", 540);
    private final ModelValueInt videoFrameRate = new ModelValueInt("Video Framerate", 15);

    private final List<HackRFEventListener> listeners = new ArrayList<>();

    @Override public ModelValueBoolean getAntennaPowerEnable() { return antennaPower; }
    @Override public ModelValueInt getFFTBinHz() { return fftBinHz; }
    @Override public ModelValue<FrequencyRange> getFrequency() { return frequency; }
    @Override public ModelValueInt getGain() { return gainTotal; }
    @Override public ModelValueInt getGainLNA() { return gainLNA; }
    @Override public ModelValueBoolean getAntennaLNA() { return antennaLNA; }
    @Override public ModelValueInt getPersistentDisplayDecayRate() { return persistentDisplayDecayRate; }
    @Override public ModelValueBoolean isDebugDisplay() { return debugDisplay; }
    @Override public ModelValueInt getSamples() { return samples; }
    @Override public ModelValueInt getFreqShift() { return freqShift; }
    @Override public ModelValueInt getSpectrumPaletteSize() { return spectrumPaletteSize; }
    @Override public ModelValueInt getAmplitudeOffset() { return amplitudeOffset; }
    @Override public ModelValueInt getWaterfallSpeed() { return waterfallSpeed; }
    @Override public ModelValueBoolean isPersistentDisplayVisible() { return persistentDisplay; }
    @Override public ModelValueBoolean isWaterfallVisible() { return waterfallVisible; }
    @Override public ModelValueBoolean isDatestampVisible() { return datestamp; }
    @Override public ModelValueInt getSpectrumPaletteStart() { return spectrumPaletteStart; }
    @Override public ModelValueInt getPeakFallRate() { return peakFallRateSecs; }
    @Override public ModelValueInt getPeakFallTrs() { return peakFallThreshold; }
    @Override public ModelValueInt getPeakHoldTime() { return peakHoldTime; }
    @Override public ModelValueInt getMaxHoldDecaySeconds() { return maxHoldDecaySeconds; }
    @Override public ModelValueInt getAvgIterations() { return avgIterations; }
    @Override public ModelValueInt getAvgOffset() { return avgOffset; }
    @Override public ModelValueInt getPowerFluxCal() { return powerFluxCal; }
    @Override public ModelValue<FrequencyAllocationTable> getFrequencyAllocationTable() { return frequencyAllocationTable; }
    @Override public ModelValue<BigDecimal> getSpectrumLineThickness() { return spectrumLineThickness; }
    @Override public ModelValue<String> getLogDetail() { return logDetail; }
    @Override public ModelValue<String> getVideoArea() { return videoArea; }
    @Override public ModelValue<String> getVideoFormat() { return videoFormat; }
    @Override public ModelValueInt getVideoResolution() { return videoResolution; }
    @Override public ModelValueInt getVideoFrameRate() { return videoFrameRate; }
    @Override public ModelValueInt getGainVGA() { return gainVGA; }
    @Override public ModelValueBoolean isCapturingPaused() { return isCapturingPaused; }
    @Override public ModelValueBoolean isRecordedVideo() { return isRecordedVideo; }
    @Override public ModelValueBoolean isRecordedData() { return isRecordedData; }
    @Override public ModelValueBoolean isChartsRealtimeVisible() { return showRealtime; }
    @Override public ModelValueBoolean isChartsAverageVisible() { return showAverage; }
    @Override public ModelValueBoolean isChartsPeaksVisible() { return showPeaks; }
    @Override public ModelValueBoolean isChartsMaxHoldVisible() { return showMaxHold; }
    @Override public ModelValueBoolean isPeakMarkerVisible() { return showPeakMarker; }
    @Override public ModelValueBoolean isMaxHoldMarkerVisible() { return showMaxHoldMarker; }
    @Override public ModelValueBoolean isFilterSpectrum() { return filterSpectrum; }
    @Override public ModelValueBoolean isSpurRemoval() { return spurRemoval; }

    public ModelValue<String> getSelectedSerial() { return selectedSerial; }
    public ModelValueBoolean isRunningRequested() { return runningRequested; }

    @Override
    public synchronized void registerListener(HackRFEventListener listener) {
        listeners.add(listener);
    }

    @Override
    public synchronized void removeListener(HackRFEventListener listener) {
        listeners.remove(listener);
    }

    public synchronized List<HackRFEventListener> snapshotListeners() {
        return new ArrayList<>(listeners);
    }

    private static FrequencyAllocationTable loadDefaultAllocationTable() {
        try {
            return new FrequencyAllocations().getTable().get("- Slovakia.csv");
        } catch (Exception e) {
            System.err.println("SettingsStore: no default frequency allocation table: " + e);
            return null;
        }
    }
}
