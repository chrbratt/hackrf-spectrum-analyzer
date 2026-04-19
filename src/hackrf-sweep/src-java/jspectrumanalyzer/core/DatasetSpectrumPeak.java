package jspectrumanalyzer.core;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;

import org.jfree.data.xy.XYSeries;

import jspectrumanalyzer.core.jfc.XYSeriesImmutable;

public class DatasetSpectrumPeak extends DatasetSpectrum
{
	protected long		lastAdded			= System.currentTimeMillis();
	protected long[]	peakHoldTime;
	protected long[]	maxHoldUpdateTime;
	/**
	 * Per-bin lifetime for the max-hold trace, in milliseconds.
	 * 0 = legacy "infinite hold" behaviour. When positive, a bin whose held
	 * value has not been beaten for this long is reset to {@link #spectrumInitPower}
	 * so old peaks fade away on their own.
	 */
	protected volatile long maxHoldFalloutMillis = 0;
	/**
	 * When true, max-hold bins linearly interpolate from the original peak
	 * value toward the current live sample over their lifetime instead of
	 * snapping at the end. The chart's "Heatmap" theme turns this on so the
	 * red trace visibly settles down toward the live spectrum as it ages.
	 * Disabled by default - all other themes use the legacy binary drop.
	 */
	protected volatile boolean maxHoldSmoothFade = false;
	protected long		peakFalloutMillis	= 1000;
	protected long		peakHoldMillis;
	protected float		peakFallThreshold;
	protected int		iteration			= 0;
	protected float[]	sumVal;
	protected float[][]	spectrumVal;
	protected int		avgIterations;
	protected int		avgOffset;
	protected int		useMarkerHold;
	
	/**
	 * stores EMA decaying peaks
	 */
	protected float[]	spectrumPeak;

	/**
	 * stores real peaks and if {@link #spectrumPeak} falls more than preset value below it, start using values from {@link #spectrumPeak}
	 */
	protected float[]	spectrumMaxHold;
	/**
	 * The peak value as it was at the moment a bin was last beaten. When
	 * {@link #maxHoldSmoothFade} is enabled, {@link #spectrumMaxHold} is
	 * interpolated between this and the live sample over the bin's lifetime;
	 * keeping the original separately means the interpolation reads from a
	 * stable anchor instead of from the (already-decaying) display value.
	 */
	protected float[]	maxHoldOriginal;
	protected float[]	spectrumPeakHold;
	protected float[]	spectrumAverage;

	/**
	 * Snapshot buffers passed to JFreeChart on the FX thread. Populated on the
	 * processing thread by {@link #snapshotForChart} so the chart never reads
	 * arrays that the processing thread is still mutating.
	 */
	protected final float[] peakHoldSnapshot;
	protected final float[] maxHoldSnapshot;
	protected final float[] averageSnapshot;
	/**
	 * Per-bin age (in milliseconds) of the max-hold value at snapshot time.
	 * The custom fade renderer uses this to compute alpha / colour shift per
	 * bin without having to read mutable processing-thread state.
	 */
	protected final long[]  maxHoldAgeMillisSnapshot;
	
	public DatasetSpectrumPeak(float fftBinSizeHz, int freqStartMHz, int freqStopMHz, float spectrumInitPower,
			float peakFallThreshold, long peakFalloutMillis, long peakHoldMillis, int freqShift, int avgIterations,
			int avgOffset)
	{
		this(fftBinSizeHz,
				FrequencyPlan.single(new FrequencyRange(freqStartMHz, freqStopMHz)),
				spectrumInitPower, peakFallThreshold, peakFalloutMillis,
				peakHoldMillis, freqShift, avgIterations, avgOffset);
	}

	/**
	 * Plan-aware constructor. All per-bin buffers are sized via
	 * {@link FrequencyPlan#totalBinCount(double)}, so multi-segment plans
	 * allocate exactly the bins they will use - no gap padding.
	 */
	public DatasetSpectrumPeak(float fftBinSizeHz, FrequencyPlan plan,
			float spectrumInitPower, float peakFallThreshold,
			long peakFalloutMillis, long peakHoldMillis, int freqShift,
			int avgIterations, int avgOffset)
	{
		super(fftBinSizeHz, plan, spectrumInitPower, freqShift);

		this.peakFalloutMillis = peakFalloutMillis;
		this.peakHoldMillis = peakHoldMillis;
		this.spectrumInitPower = spectrumInitPower;
		this.peakFallThreshold = peakFallThreshold;
		this.avgIterations = avgIterations;
		this.avgOffset = avgOffset;
		// Reuse the bin count the parent already computed from the plan -
		// keeps every per-bin array in lockstep with spectrum.length.
		int datapoints = spectrum.length;
		spectrumPeak = new float[datapoints];
		Arrays.fill(spectrumPeak, spectrumInitPower);
		spectrumPeakHold = new float[datapoints];
		Arrays.fill(spectrumPeakHold, spectrumInitPower);
		spectrumMaxHold = new float[datapoints];
		Arrays.fill(spectrumMaxHold, spectrumInitPower);
		maxHoldOriginal = new float[datapoints];
		Arrays.fill(maxHoldOriginal, spectrumInitPower);
		spectrumAverage = new float[datapoints];
		Arrays.fill(spectrumAverage, spectrumInitPower);
		spectrumVal = new float[avgIterations][datapoints];
		for (float[] row : spectrumVal) 
            Arrays.fill(row, spectrumInitPower);
		sumVal = new float[datapoints];
		Arrays.fill(sumVal, avgIterations * spectrumInitPower);
		peakHoldTime = new long[datapoints];
		Arrays.fill(peakHoldTime, System.currentTimeMillis());
		maxHoldUpdateTime = new long[datapoints];
		Arrays.fill(maxHoldUpdateTime, System.currentTimeMillis());

		peakHoldSnapshot = new float[datapoints];
		maxHoldSnapshot = new float[datapoints];
		averageSnapshot = new float[datapoints];
		maxHoldAgeMillisSnapshot = new long[datapoints];
		Arrays.fill(peakHoldSnapshot, spectrumInitPower);
		Arrays.fill(maxHoldSnapshot, spectrumInitPower);
		Arrays.fill(averageSnapshot, spectrumInitPower);
	}

	public void setPeakFalloutMillis(long peakFalloutMillis) {
		this.peakFalloutMillis = peakFalloutMillis;
	}
	
	public void setPeakFallThreshold(int peakFallThreshold) {
		this.peakFallThreshold = peakFallThreshold;
	}
	
	public void setPeakHoldMillis(long peakHoldMillis) {
		this.peakHoldMillis = peakHoldMillis;
	}

	/**
	 * Live-update the per-bin max-hold lifetime. {@code 0} disables the decay
	 * (returns to legacy infinite hold). Safe to call from any thread; the
	 * processing thread reads this value once per sweep.
	 */
	public void setMaxHoldFalloutMillis(long millis) {
		this.maxHoldFalloutMillis = Math.max(0L, millis);
	}

	/**
	 * Toggle between binary drop (false, default) and smooth value-fade
	 * (true) for the max-hold trace. Safe to call from any thread.
	 */
	public void setMaxHoldSmoothFade(boolean smooth) {
		this.maxHoldSmoothFade = smooth;
	}
	
	public void setAvgIterations(int avgIterations) {
		this.avgIterations = avgIterations;
	}
	
	public void setAvgOffset(int avgOffset) {
		this.avgOffset = avgOffset;
	}
	
	public void copyTo(DatasetSpectrumPeak filtered)
	{
		super.copyTo(filtered);
		System.arraycopy(spectrumPeak, 0, filtered.spectrumPeak, 0, spectrumPeak.length);
		System.arraycopy(spectrumPeakHold, 0, filtered.spectrumPeakHold, 0, spectrumPeakHold.length);
	}

	/**
	 * Fills data to {@link XYSeries}, uses x units in MHz
	 * @param series
	 */
	public void fillPeaksToXYSeries(XYSeries series)
	{
		fillToXYSeriesPriv(series, spectrumPeakHold);
//		fillToXYSeriesPriv(series, spectrumPeak);
	}
	
	public XYSeriesImmutable createPeaksDataset(String name) {
		return new XYSeriesImmutable(name, frequencyAxisMHz, peakHoldSnapshot);
	}

	public XYSeriesImmutable createMaxHoldDataset(String name) {
		return new XYSeriesImmutable(name, frequencyAxisMHz, maxHoldSnapshot);
	}

	public XYSeriesImmutable createAverageDataset(String name) {
		return new XYSeriesImmutable(name, frequencyAxisMHz, averageSnapshot);
	}

	/**
	 * Copy the live working buffers into the snapshot buffers used by the
	 * chart on the FX thread. Only copies what the chart will actually read.
	 */
	public void snapshotForChart(boolean peaks, boolean average,
	                             boolean maxHold, boolean realtime) {
		super.snapshotForChart(realtime);
		if (peaks) {
			System.arraycopy(spectrumPeakHold, 0, peakHoldSnapshot, 0, peakHoldSnapshot.length);
		}
		if (maxHold) {
			System.arraycopy(spectrumMaxHold, 0, maxHoldSnapshot, 0, maxHoldSnapshot.length);
			// Compute per-bin age in one pass so the renderer can read it
			// off-thread without touching the live timestamp array.
			long now = System.currentTimeMillis();
			for (int i = 0; i < maxHoldAgeMillisSnapshot.length; i++) {
				maxHoldAgeMillisSnapshot[i] = now - maxHoldUpdateTime[i];
			}
		}
		if (average) {
			System.arraycopy(spectrumAverage, 0, averageSnapshot, 0, averageSnapshot.length);
		}
	}

	/**
	 * Age (ms since last beaten) of every max-hold bin at the moment of the
	 * latest {@link #snapshotForChart} call. Returned reference is read-only
	 * and shared across frames; the chart should not retain it across calls.
	 */
	public long[] getMaxHoldAgeMillisSnapshot() {
		return maxHoldAgeMillisSnapshot;
	}

	/**
	 * The fallout window the max-hold values were aged against in the most
	 * recent processing pass. Exposed so the renderer can normalise per-bin
	 * age to a [0, 1] ratio using exactly the same number the dataset used.
	 */
	public long getMaxHoldFalloutMillis() {
		return maxHoldFalloutMillis;
	}
	
	public double[] calculateSpectrumPeakPower(int PowerFluxCalibration){
		double powerSum	= 0;
		double powerFluxSum = 0;
		double[] out = new double[4];
		float maxAmp = spectrumInitPower;
		double maxFreq = freqStartMHz + freqShift;
		// Quantise the peak label to one bin width so the on-screen number
		// stops jittering at sub-Hz precision. Plan-aware: rfFrequencyMHzAt
		// returns the actual RF MHz of bin i across all segments.
		double freqStep = fftBinSizeHz / 1_000_000d;
		double quantum = freqStep > 0 ? Math.round(1d / freqStep) : 1d;
		for (int i = 0; i < spectrumPeakHold.length; i++) {
			if (spectrumPeakHold[i] > -95) {powerSum += Math.pow(10, spectrumPeakHold[i] / 10);} /*convert dB to mW to sum power in linear form*/
			if (spectrumPeakHold[i] > maxAmp) {
				maxAmp = spectrumPeakHold[i];
				double rfMHz = rfFrequencyMHzAt(i);
				maxFreq = Math.round(quantum * rfMHz) / quantum + freqShift;
			}
		}
		powerFluxSum = (powerSum * Math.pow(10,(PowerFluxCalibration/10f))) * (4 * Math.PI * Math.pow(maxFreq / 1E3, 2) * 1E18) / Math.pow(299792458, 2);
		powerSum	= 10 * Math.log10(powerSum); /*convert back to dB*/
		out[0] = powerSum;
		out[1] = (double) Math.round(10 * maxAmp) / 10;
		out[2] = maxFreq;
		out[3] = roundToSignificantFigures(powerFluxSum,2);
		return out;
	}

	public double[] calculateMarkerHold(){
		double[] out = new double[2];
		float maxAmpHold = spectrumInitPower;
		double maxFreqHold = freqStartMHz + freqShift;
		double freqStep = fftBinSizeHz / 1_000_000d;
		double quantum = freqStep > 0 ? Math.round(1d / freqStep) : 1d;

		for (int i = 0; i < spectrumMaxHold.length; i++) {
			if (spectrumMaxHold[i] > maxAmpHold) {
				maxAmpHold = spectrumMaxHold[i];
				double rfMHz = rfFrequencyMHzAt(i);
				maxFreqHold = Math.round(quantum * rfMHz) / quantum + freqShift;
			}
		}
		out[0] = (double) Math.round(10 * maxAmpHold) / 10;
		out[1] = maxFreqHold;
		return out;
	}
	
	public static double roundToSignificantFigures(double num, int n) {
	    if(num == 0) {
	        return 0;
	    }

	    final double d = Math.ceil(Math.log10(num));
	    final int power = n - (int) d;

	    final double magnitude = Math.pow(10, power);
	    final long shifted = Math.round(num*magnitude);
	    final double res = shifted/magnitude;
	    return res;
	}
	
	private long debugLastPeakRerfreshTime	= 0;
	public void refreshPeakSpectrum()
	{
		if (false) {
			long debugMinPeakRefreshTime	= 100;
			if (System.currentTimeMillis()-debugLastPeakRerfreshTime < debugMinPeakRefreshTime)
				return;
			debugLastPeakRerfreshTime	= System.currentTimeMillis();
		}
		
		long timeDiffFromPrevValueMillis = System.currentTimeMillis() - lastAdded;
		if (timeDiffFromPrevValueMillis < 1)
			timeDiffFromPrevValueMillis = 1;
		
		lastAdded = System.currentTimeMillis();
		
//		peakFallThreshold = 10;
//		peakFalloutMillis	= 30000;
		for (int spectrIndex = 0; spectrIndex < spectrum.length; spectrIndex++)
		{
			//float spectrumVal = spectrum[spectrIndex];
			if (spectrum[spectrIndex] > spectrumPeakHold[spectrIndex])
			{
				spectrumPeakHold[spectrIndex] = spectrumPeak[spectrIndex] = spectrum[spectrIndex];
				peakHoldTime[spectrIndex] = System.currentTimeMillis();
			}
			spectrumPeak[spectrIndex] = (float) EMA.calculateTimeDependent(spectrum[spectrIndex], spectrumPeak[spectrIndex],
					timeDiffFromPrevValueMillis, peakFalloutMillis);

			if (spectrumPeakHold[spectrIndex] - spectrumPeak[spectrIndex] > peakFallThreshold
					&& System.currentTimeMillis() - peakHoldTime[spectrIndex] > peakHoldMillis)
			{
				spectrumPeakHold[spectrIndex] = spectrumPeak[spectrIndex];
			}
		}
	}
	
	public void refreshMaxHoldSpectrum()
	{
		// Snapshot the volatile fields so a concurrent UI change can't make us
		// flip mid-loop between "decaying" and "infinite hold" or between
		// smooth-fade and binary-drop.
		final long fallout = maxHoldFalloutMillis;
		final boolean smooth = maxHoldSmoothFade;
		final long now = System.currentTimeMillis();
		for (int spectrIndex = 0; spectrIndex < spectrum.length; spectrIndex++)
		{
			float sample = spectrum[spectrIndex];
			if (sample >= maxHoldOriginal[spectrIndex])
			{
				// New peak (or tie). Reset both the displayed value and the
				// anchor we interpolate from.
				maxHoldOriginal[spectrIndex] = sample;
				spectrumMaxHold[spectrIndex] = sample;
				maxHoldUpdateTime[spectrIndex] = now;
				continue;
			}

			if (fallout <= 0) {
				// Infinite hold: keep whatever the original peak was forever.
				spectrumMaxHold[spectrIndex] = maxHoldOriginal[spectrIndex];
				continue;
			}

			long age = now - maxHoldUpdateTime[spectrIndex];
			if (age >= fallout) {
				// Lifetime expired: snap back to live and reset the anchor.
				maxHoldOriginal[spectrIndex] = sample;
				spectrumMaxHold[spectrIndex] = sample;
				maxHoldUpdateTime[spectrIndex] = now;
			} else if (smooth) {
				// Linear interpolation: at age=0 we show the original peak,
				// at age=fallout we'd show the live sample. The eye reads
				// this as the peak "settling down" toward live, which is
				// visually clearer than the original binary drop.
				float k = (float) age / (float) fallout;
				spectrumMaxHold[spectrIndex] =
						maxHoldOriginal[spectrIndex]
						+ (sample - maxHoldOriginal[spectrIndex]) * k;
			} else {
				// Binary drop: hold the original peak until lifetime expires.
				spectrumMaxHold[spectrIndex] = maxHoldOriginal[spectrIndex];
			}
		}
	}
	
	public void refreshAverageSpectrum()
	{
		for (int spectrIndex = 0; spectrIndex < spectrum.length; spectrIndex++)
		{
			float previousVal = spectrumVal[iteration][spectrIndex];
			spectrumVal[iteration][spectrIndex] = spectrum[spectrIndex];
			sumVal[spectrIndex] = sumVal[spectrIndex] + spectrum[spectrIndex] - previousVal;
			spectrumAverage[spectrIndex] = sumVal[spectrIndex]/avgIterations + avgOffset + 10;
		}
		iteration++;
		if (iteration == avgIterations) { iteration = 0; }
	}
	
	private float getMax(float[] inputArray){ 
	   float maxValue = inputArray[0]; 
	   for(int i=1;i < inputArray.length;i++){ 
	      if(inputArray[i] > maxValue){ 
	         maxValue = inputArray[i]; 
	      } 
	   } 
	   return maxValue; 
	}

	public void resetPeaks()
	{
		Arrays.fill(spectrumPeak, spectrumInitPower);
		Arrays.fill(spectrumPeakHold, spectrumInitPower);
	}
	
	public void resetMaxHold()
	{
		Arrays.fill(spectrumMaxHold, spectrumInitPower);
		Arrays.fill(maxHoldOriginal, spectrumInitPower);
		Arrays.fill(maxHoldUpdateTime, System.currentTimeMillis());
	}
	
	public void resetAverage()
	{
		Arrays.fill(spectrumAverage, spectrumInitPower);
	}

	@Override protected Object clone() throws CloneNotSupportedException
	{
		DatasetSpectrumPeak copy = (DatasetSpectrumPeak) super.clone();
		copy.spectrumPeakHold = spectrumPeakHold.clone();
		copy.spectrumPeak = spectrumPeak.clone();
		return super.clone();
	}

}
