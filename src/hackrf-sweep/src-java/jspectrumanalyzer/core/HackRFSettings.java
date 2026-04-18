package jspectrumanalyzer.core;

import java.math.BigDecimal;

import shared.mvc.ModelValue;
import shared.mvc.ModelValue.ModelValueBoolean;
import shared.mvc.ModelValue.ModelValueInt;

public interface HackRFSettings {
	public static abstract class HackRFEventAdapter implements HackRFEventListener {
		@Override
		public void captureStateChanged(boolean isCapturing) {

		}

		@Override
		public void hardwareStatusChanged(boolean hardwareSendingData) {

		}
	}

	public static interface HackRFEventListener {
		public void captureStateChanged(boolean isCapturing);

		public void hardwareStatusChanged(boolean hardwareSendingData);
	}

	public ModelValueBoolean getAntennaPowerEnable();

	public ModelValueInt getFFTBinHz();

	public ModelValue<FrequencyRange> getFrequency();

	public ModelValueInt getGainLNA();
	
	public ModelValueBoolean getAntennaLNA();
	
	public ModelValueInt getPersistentDisplayDecayRate();
	
	public ModelValueBoolean isDebugDisplay();

	public ModelValueInt getSamples();
	
	public ModelValueInt getFreqShift();

	public ModelValueInt getSpectrumPaletteSize();
	
	public ModelValueInt getAmplitudeOffset();
	
	public ModelValueInt getWaterfallSpeed();
	
	public ModelValueBoolean isPersistentDisplayVisible();
	public ModelValueBoolean isWaterfallVisible();
	
	public ModelValueBoolean isDatestampVisible();

	public ModelValueInt getSpectrumPaletteStart();
	
	public ModelValueInt getPeakFallRate();
	
	public ModelValueInt getPeakFallTrs();
	
	public ModelValueInt getPeakHoldTime();

	/**
	 * Per-bin lifetime for the max-hold trace, in seconds.
	 * 0 means "hold forever" (legacy behaviour); any positive value lets each
	 * bin reset to the current sample once it has not been beaten for this many
	 * seconds.
	 */
	public ModelValueInt getMaxHoldDecaySeconds();
	
	public ModelValueInt getAvgIterations();
	
	public ModelValueInt getAvgOffset();
	
	public ModelValueInt getPowerFluxCal();
	
	public ModelValue<FrequencyAllocationTable> getFrequencyAllocationTable();

	public ModelValue<BigDecimal> getSpectrumLineThickness();

	public ModelValueInt getGainVGA();

	public ModelValueBoolean isCapturingPaused();

	public ModelValueBoolean isRecordedData();
	
	public ModelValueBoolean isChartsRealtimeVisible();
	
	public ModelValueBoolean isChartsAverageVisible();

	public ModelValueBoolean isChartsPeaksVisible();
	
	public ModelValueBoolean isChartsMaxHoldVisible();
	
	public ModelValueBoolean isPeakMarkerVisible();
	
	public ModelValueBoolean isMaxHoldMarkerVisible();

	public ModelValueBoolean isFilterSpectrum();

	public ModelValueBoolean isSpurRemoval();

	public void registerListener(HackRFEventListener listener);

	public void removeListener(HackRFEventListener listener);
}
