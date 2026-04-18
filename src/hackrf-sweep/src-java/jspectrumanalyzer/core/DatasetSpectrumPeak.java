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
	
	public DatasetSpectrumPeak(float fftBinSizeHz, int freqStartMHz, int freqStopMHz, float spectrumInitPower,
			float peakFallThreshold, long peakFalloutMillis, long peakHoldMillis, int freqShift, int avgIterations,
			int avgOffset)
	{
		super(fftBinSizeHz, freqStartMHz, freqStopMHz, spectrumInitPower, freqShift);

		this.peakFalloutMillis = peakFalloutMillis;
		this.peakHoldMillis = peakHoldMillis;
		this.spectrumInitPower = spectrumInitPower;
		this.peakFallThreshold = peakFallThreshold;
		this.avgIterations = avgIterations;
		this.avgOffset = avgOffset;
		int datapoints = (int) (Math.ceil(freqStopMHz - freqStartMHz) * 1000000d / fftBinSizeHz);
		spectrum = new float[datapoints];
		Arrays.fill(spectrum, spectrumInitPower);
		spectrumPeak = new float[datapoints];
		Arrays.fill(spectrumPeak, spectrumInitPower);
		spectrumPeakHold = new float[datapoints];
		Arrays.fill(spectrumPeakHold, spectrumInitPower);
		spectrumMaxHold = new float[datapoints];
		Arrays.fill(spectrumMaxHold, spectrumInitPower);
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
		}
		if (average) {
			System.arraycopy(spectrumAverage, 0, averageSnapshot, 0, averageSnapshot.length);
		}
	}
	
	public double[] calculateSpectrumPeakPower(int PowerFluxCalibration){
		double powerSum	= 0;
		double powerFluxSum = 0;
		double[] out = new double[4];
		float maxAmp = spectrumInitPower;
		double maxFreq = freqStartMHz + freqShift;
		double freqStep = fftBinSizeHz / 1000000d;
		for (int i = 0; i < spectrumPeakHold.length; i++) {
			if (spectrumPeakHold[i] > -95) {powerSum += Math.pow(10, spectrumPeakHold[i] / 10);} /*convert dB to mW to sum power in linear form*/
			if (spectrumPeakHold[i] > maxAmp) {
				maxAmp = spectrumPeakHold[i];
				maxFreq = (double)Math.round(Math.round(1 / freqStep) * (freqStartMHz + freqStep * i)) / Math.round(1 / freqStep) + freqShift;
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
		double freqStep = fftBinSizeHz / 1000000d;

		for (int i = 0; i < spectrumMaxHold.length; i++) {
			if (spectrumMaxHold[i] > maxAmpHold) {
				maxAmpHold = spectrumMaxHold[i];
				maxFreqHold = (double)Math.round(Math.round(1 / freqStep) * (freqStartMHz + freqStep * i)) / Math.round(1 / freqStep) + freqShift;
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
		// Snapshot the volatile field so a concurrent UI change can't make us
		// flip mid-loop between "decaying" and "infinite hold".
		final long fallout = maxHoldFalloutMillis;
		final long now = System.currentTimeMillis();
		for (int spectrIndex = 0; spectrIndex < spectrum.length; spectrIndex++)
		{
			float sample = spectrum[spectrIndex];
			if (sample > spectrumMaxHold[spectrIndex])
			{
				spectrumMaxHold[spectrIndex] = sample;
				maxHoldUpdateTime[spectrIndex] = now;
			}
			else if (fallout > 0 && (now - maxHoldUpdateTime[spectrIndex]) >= fallout)
			{
				// Bin's stored peak has aged out without being beaten - drop
				// it back to the live sample so old activity stops painting
				// the trace forever.
				spectrumMaxHold[spectrIndex] = sample;
				maxHoldUpdateTime[spectrIndex] = now;
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
