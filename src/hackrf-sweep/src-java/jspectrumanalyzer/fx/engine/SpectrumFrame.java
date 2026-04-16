package jspectrumanalyzer.fx.engine;

import jspectrumanalyzer.core.DatasetSpectrumPeak;

/**
 * Immutable snapshot delivered by {@link SpectrumEngine} to its consumers once per
 * completed sweep. The dataset reference is shared (not cloned); consumers that need to
 * keep data across frames must copy it themselves.
 */
public final class SpectrumFrame {

    public final DatasetSpectrumPeak dataset;
    public final double peakAmplitudeDbm;
    public final double peakFrequencyMHz;
    public final double totalPowerDbm;
    public final String powerFluxDensity;
    public final double maxHoldAmplitudeDbm;
    public final double maxHoldFrequencyMHz;
    public final boolean showPeaks;
    public final boolean showAverage;
    public final boolean showMaxHold;
    public final boolean showRealtime;

    public SpectrumFrame(DatasetSpectrumPeak dataset,
                         double peakAmplitudeDbm, double peakFrequencyMHz,
                         double totalPowerDbm, String powerFluxDensity,
                         double maxHoldAmplitudeDbm, double maxHoldFrequencyMHz,
                         boolean showPeaks, boolean showAverage, boolean showMaxHold, boolean showRealtime) {
        this.dataset = dataset;
        this.peakAmplitudeDbm = peakAmplitudeDbm;
        this.peakFrequencyMHz = peakFrequencyMHz;
        this.totalPowerDbm = totalPowerDbm;
        this.powerFluxDensity = powerFluxDensity;
        this.maxHoldAmplitudeDbm = maxHoldAmplitudeDbm;
        this.maxHoldFrequencyMHz = maxHoldFrequencyMHz;
        this.showPeaks = showPeaks;
        this.showAverage = showAverage;
        this.showMaxHold = showMaxHold;
        this.showRealtime = showRealtime;
    }
}
