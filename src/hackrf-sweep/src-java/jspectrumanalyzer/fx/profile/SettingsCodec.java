package jspectrumanalyzer.fx.profile;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jspectrumanalyzer.core.FrequencyPlan;
import jspectrumanalyzer.core.FrequencyRange;
import jspectrumanalyzer.fx.chart.GraphTheme;
import jspectrumanalyzer.fx.model.SettingsStore;
import jspectrumanalyzer.ui.WaterfallPalette;
import shared.mvc.ModelValue;
import shared.mvc.ModelValue.ModelValueBoolean;
import shared.mvc.ModelValue.ModelValueInt;

/**
 * Translates a {@link SettingsStore} to and from a flat {@link Properties} map
 * so a user "profile" can be written to disk and restored later.
 *
 * <p>Only the parameters a user tweaks while hunting signals are included
 * (frequency, resolution, gain, trace modes, processing and look). Hardware-
 * and session-specific state - the selected HackRF serial, the Wi-Fi adapter,
 * the running flag, freeze and CSV recording - is deliberately left out: those
 * belong to "the device in front of me right now", not to a reusable profile.
 *
 * <p>{@link #apply} is tolerant: every key is restored independently and a
 * missing or malformed value is skipped (and logged) rather than aborting the
 * whole load, so a hand-edited or older profile file still restores whatever it
 * can. It must be called on the JavaFX application thread because writing a
 * {@link ModelValue} fires its UI listeners synchronously.
 */
public final class SettingsCodec {

    private static final Logger LOG = LoggerFactory.getLogger(SettingsCodec.class);

    private SettingsCodec() {
    }

    /** Read the current settings into a fresh {@link Properties} snapshot. */
    public static Properties capture(SettingsStore s) {
        Properties p = new Properties();

        // Frequency (single range + optional multi-segment plan).
        FrequencyRange range = s.getFrequency().getValue();
        if (range != null) {
            p.setProperty("frequency.startMHz", Integer.toString(range.getStartMHz()));
            p.setProperty("frequency.endMHz", Integer.toString(range.getEndMHz()));
        }
        FrequencyPlan plan = s.getFrequencyPlan().getValue();
        if (plan != null) {
            p.setProperty("frequencyPlan", encodePlan(plan));
        }

        // Resolution.
        putInt(p, "fftBinHz", s.getFFTBinHz());
        putInt(p, "samples", s.getSamples());

        // Gain / front-end.
        putInt(p, "gainLNA", s.getGainLNA());
        putInt(p, "gainVGA", s.getGainVGA());
        putBool(p, "antennaPower", s.getAntennaPowerEnable());
        putBool(p, "antennaLNA", s.getAntennaLNA());

        // Averaging / peak / max-hold processing.
        putInt(p, "avgIterations", s.getAvgIterations());
        putInt(p, "avgOffset", s.getAvgOffset());
        putInt(p, "peakFallRate", s.getPeakFallRate());
        putInt(p, "peakFallThreshold", s.getPeakFallTrs());
        putInt(p, "peakHoldTime", s.getPeakHoldTime());
        putInt(p, "maxHoldDecaySeconds", s.getMaxHoldDecaySeconds());

        // Trace visibility.
        putBool(p, "showRealtime", s.isChartsRealtimeVisible());
        putBool(p, "showAverage", s.isChartsAverageVisible());
        putBool(p, "showPeaks", s.isChartsPeaksVisible());
        putBool(p, "showMaxHold", s.isChartsMaxHoldVisible());
        putBool(p, "showPeakMarker", s.isPeakMarkerVisible());
        putBool(p, "showMaxHoldMarker", s.isMaxHoldMarkerVisible());

        // Processing toggles.
        putBool(p, "spurRemoval", s.isSpurRemoval());

        // Calibration / offsets.
        putInt(p, "amplitudeOffset", s.getAmplitudeOffset());
        putInt(p, "powerFluxCal", s.getPowerFluxCal());
        putInt(p, "freqShift", s.getFreqShift());

        // Look & feel.
        p.setProperty("graphTheme", s.getGraphTheme().getValue().name());
        p.setProperty("waterfallTheme", s.getWaterfallTheme().getValue().name());
        putInt(p, "waterfallSpeed", s.getWaterfallSpeed());
        putInt(p, "waterfallSensitivity", s.getWaterfallSensitivity());
        putBool(p, "waterfallFunnel", s.isWaterfallFunnel());
        putBool(p, "waterfallVisible", s.isWaterfallVisible());
        putBool(p, "persistentDisplay", s.isPersistentDisplayVisible());
        putInt(p, "persistentDecayRate", s.getPersistentDisplayDecayRate());
        putInt(p, "apMarkerOpacity", s.getApMarkerOpacity());
        putBool(p, "datestamp", s.isDatestampVisible());
        BigDecimal thickness = s.getSpectrumLineThickness().getValue();
        if (thickness != null) {
            p.setProperty("spectrumLineThickness", thickness.toPlainString());
        }

        return p;
    }

    /** Restore every recognised key from {@code p} onto {@code s} (FX thread). */
    public static void apply(Properties p, SettingsStore s) {
        // Frequency first so the engine retunes once to the profile's band.
        Integer start = parseInt(p, "frequency.startMHz");
        Integer end = parseInt(p, "frequency.endMHz");
        if (start != null && end != null && end > start) {
            applyValue(s.getFrequency(), new FrequencyRange(start, end));
        }
        // A profile without a plan key means single-range: clear any active plan.
        applyValue(s.getFrequencyPlan(), decodePlan(p.getProperty("frequencyPlan")));

        applyInt(p, "fftBinHz", s.getFFTBinHz());
        applyInt(p, "samples", s.getSamples());

        applyInt(p, "gainLNA", s.getGainLNA());
        applyInt(p, "gainVGA", s.getGainVGA());
        applyBool(p, "antennaPower", s.getAntennaPowerEnable());
        applyBool(p, "antennaLNA", s.getAntennaLNA());

        applyInt(p, "avgIterations", s.getAvgIterations());
        applyInt(p, "avgOffset", s.getAvgOffset());
        applyInt(p, "peakFallRate", s.getPeakFallRate());
        applyInt(p, "peakFallThreshold", s.getPeakFallTrs());
        applyInt(p, "peakHoldTime", s.getPeakHoldTime());
        applyInt(p, "maxHoldDecaySeconds", s.getMaxHoldDecaySeconds());

        applyBool(p, "showRealtime", s.isChartsRealtimeVisible());
        applyBool(p, "showAverage", s.isChartsAverageVisible());
        applyBool(p, "showPeaks", s.isChartsPeaksVisible());
        applyBool(p, "showMaxHold", s.isChartsMaxHoldVisible());
        applyBool(p, "showPeakMarker", s.isPeakMarkerVisible());
        applyBool(p, "showMaxHoldMarker", s.isMaxHoldMarkerVisible());

        applyBool(p, "spurRemoval", s.isSpurRemoval());

        applyInt(p, "amplitudeOffset", s.getAmplitudeOffset());
        applyInt(p, "powerFluxCal", s.getPowerFluxCal());
        applyInt(p, "freqShift", s.getFreqShift());

        applyEnum(p, "graphTheme", GraphTheme.class, s.getGraphTheme());
        applyEnum(p, "waterfallTheme", WaterfallPalette.class, s.getWaterfallTheme());
        applyInt(p, "waterfallSpeed", s.getWaterfallSpeed());
        applyInt(p, "waterfallSensitivity", s.getWaterfallSensitivity());
        applyBool(p, "waterfallFunnel", s.isWaterfallFunnel());
        applyBool(p, "waterfallVisible", s.isWaterfallVisible());
        applyBool(p, "persistentDisplay", s.isPersistentDisplayVisible());
        applyInt(p, "persistentDecayRate", s.getPersistentDisplayDecayRate());
        applyInt(p, "apMarkerOpacity", s.getApMarkerOpacity());
        applyBool(p, "datestamp", s.isDatestampVisible());

        String thickness = p.getProperty("spectrumLineThickness");
        if (thickness != null) {
            try {
                s.getSpectrumLineThickness().setValue(new BigDecimal(thickness.trim()));
            } catch (NumberFormatException e) {
                LOG.warn("Skipping malformed spectrumLineThickness '{}'", thickness);
            }
        }
    }

    // ---- capture helpers -------------------------------------------------

    private static void putInt(Properties p, String key, ModelValueInt m) {
        p.setProperty(key, Integer.toString(m.getValue()));
    }

    private static void putBool(Properties p, String key, ModelValueBoolean m) {
        p.setProperty(key, Boolean.toString(m.getValue()));
    }

    // ---- apply helpers ---------------------------------------------------

    private static <T> void applyValue(ModelValue<T> m, T value) {
        try {
            m.setValue(value);
        } catch (RuntimeException e) {
            LOG.warn("Skipping value for '{}': {}", m, e.getMessage());
        }
    }

    private static void applyInt(Properties p, String key, ModelValueInt m) {
        Integer v = parseInt(p, key);
        if (v == null) return;
        int value = m.isBounded() ? snap(v, m.getMin(), m.getMax(), m.getStep()) : v;
        try {
            m.setValue(value);
        } catch (RuntimeException e) {
            LOG.warn("Skipping int '{}'={}: {}", key, v, e.getMessage());
        }
    }

    private static void applyBool(Properties p, String key, ModelValueBoolean m) {
        String raw = p.getProperty(key);
        if (raw == null) return;
        m.setValue(Boolean.parseBoolean(raw.trim()));
    }

    private static <E extends Enum<E>> void applyEnum(Properties p, String key,
            Class<E> type, ModelValue<E> m) {
        String raw = p.getProperty(key);
        if (raw == null) return;
        try {
            m.setValue(Enum.valueOf(type, raw.trim()));
        } catch (IllegalArgumentException e) {
            LOG.warn("Skipping unknown {} value '{}'", type.getSimpleName(), raw);
        }
    }

    private static Integer parseInt(Properties p, String key) {
        String raw = p.getProperty(key);
        if (raw == null) return null;
        try {
            return Integer.valueOf(raw.trim());
        } catch (NumberFormatException e) {
            LOG.warn("Skipping malformed integer '{}'={}", key, raw);
            return null;
        }
    }

    /** Clamp to [lo, hi] and snap onto the model's step grid. */
    private static int snap(int v, int lo, int hi, int step) {
        int clamped = Math.max(lo, Math.min(hi, v));
        if (step <= 1) return clamped;
        int snapped = lo + Math.round((clamped - lo) / (float) step) * step;
        if (snapped > hi) snapped -= step;
        if (snapped < lo) snapped = lo;
        return snapped;
    }

    // ---- frequency plan encoding ----------------------------------------

    /** Encode a plan as {@code "start0-end0,start1-end1,..."} in MHz. */
    static String encodePlan(FrequencyPlan plan) {
        StringBuilder sb = new StringBuilder();
        List<FrequencyRange> segments = plan.segments();
        for (int i = 0; i < segments.size(); i++) {
            if (i > 0) sb.append(',');
            FrequencyRange seg = segments.get(i);
            sb.append(seg.getStartMHz()).append('-').append(seg.getEndMHz());
        }
        return sb.toString();
    }

    /** Inverse of {@link #encodePlan}; returns {@code null} for blank/invalid input. */
    static FrequencyPlan decodePlan(String encoded) {
        if (encoded == null || encoded.isBlank()) return null;
        try {
            List<FrequencyRange> segments = new ArrayList<>();
            for (String part : encoded.split(",")) {
                String[] bounds = part.trim().split("-");
                if (bounds.length != 2) return null;
                int start = Integer.parseInt(bounds[0].trim());
                int end = Integer.parseInt(bounds[1].trim());
                segments.add(new FrequencyRange(start, end));
            }
            return segments.isEmpty() ? null : new FrequencyPlan(segments);
        } catch (RuntimeException e) {
            LOG.warn("Skipping malformed frequencyPlan '{}': {}", encoded, e.getMessage());
            return null;
        }
    }
}
