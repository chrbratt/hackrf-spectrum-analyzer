package jspectrumanalyzer.core;

import java.util.ArrayList;
import java.util.Arrays;

import org.jfree.data.xy.XYDataItem;
import org.jfree.data.xy.XYSeries;

import jspectrumanalyzer.core.jfc.XYSeriesImmutable;

public class DatasetSpectrum implements Cloneable
{
	/**
	 * caching decreases GC usage
	 */
	private final boolean useCached	= false;
	protected ArrayList<ArrayList<XYDataItem>> cachedDataItems	= new ArrayList<>();
	protected int cachedDataItemsIndex	= 0;
	protected  final float	fftBinSizeHz;

	/**
	 * Frequency plan that defines the sweep coverage. For the legacy
	 * single-range case this is a single-segment plan and behaves
	 * identically to the previous implementation (logical MHz == RF MHz, no
	 * gaps, no separator lines on the chart).
	 */
	protected final FrequencyPlan plan;

	protected  final long	freqStartHz;
	protected  final int	freqStartMHz;

	protected  final int	freqStopMHz;
	protected  final int	freqShift;
	protected  float[]		spectrum;
	protected  float		spectrumInitPower;

	/**
	 * Per-bin <b>logical</b> frequency in MHz (stitched, not RF). For a
	 * single-segment plan logical MHz equals RF MHz. For a multi-segment
	 * plan the gaps are removed so JFreeChart paints contiguous data and the
	 * waterfall canvas (which uses {@code pixelX = width/size * i}) lines up
	 * with the chart x-axis automatically. Use {@link #rfFrequencyMHzAt(int)}
	 * when the actual radio frequency of a bin is needed (peak markers,
	 * allocations, tooltips).
	 */
	protected final float[] frequencyAxisMHz;

	/**
	 * Stable snapshot of {@link #spectrum} taken on the processing thread
	 * before publishing a frame to the chart. The chart reads from this
	 * snapshot on the FX thread, so we never see torn updates while the
	 * processing thread continues writing.
	 */
	protected final float[] spectrumSnapshot;

	/** Legacy constructor - wraps the (start, stop) pair in a single-segment plan. */
	public DatasetSpectrum(float fftBinSizeHz, int freqStartMHz, int freqStopMHz,
			float spectrumInitPower, int freqShift) {
		this(fftBinSizeHz,
				FrequencyPlan.single(new FrequencyRange(freqStartMHz, freqStopMHz)),
				spectrumInitPower, freqShift);
	}

	/**
	 * Plan-aware constructor. For multi-segment plans the {@code spectrum}
	 * array contains <em>only</em> bins for sweepable frequencies - gaps
	 * between segments are not allocated, so neither chart nor waterfall
	 * waste pixels on dead air.
	 */
	public DatasetSpectrum(float fftBinSizeHz, FrequencyPlan plan,
			float spectrumInitPower, int freqShift) {
		this.fftBinSizeHz = fftBinSizeHz;
		this.plan = plan;
		this.freqStartMHz = plan.firstStartMHz();
		this.freqStartHz = (long) plan.firstStartMHz() * 1_000_000L;
		this.freqStopMHz = plan.lastEndMHz();
		this.freqShift = freqShift;
		this.spectrumInitPower = spectrumInitPower;
		int datapoints = plan.totalBinCount(fftBinSizeHz);
		spectrum = new float[datapoints];
		Arrays.fill(spectrum, spectrumInitPower);

		frequencyAxisMHz = new float[datapoints];
		float binWidthMHz = fftBinSizeHz / 1_000_000f;
		for (int i = 0; i < datapoints; i++) {
			// Logical x: bins are uniformly spaced on the stitched axis,
			// regardless of any gaps in the underlying RF plan. freqShift is
			// applied verbatim so the user-facing scale matches the spectrum
			// chart axis.
			frequencyAxisMHz[i] = i * binWidthMHz + freqShift;
		}

		spectrumSnapshot = new float[datapoints];
		Arrays.fill(spectrumSnapshot, spectrumInitPower);

		if (useCached) {
			for (int j = 0; j < 5; j++) {
				ArrayList<XYDataItem> list	= new ArrayList<>();
				for (int i = 0; i < datapoints; i++) {
					list.add(new XYDataItem(frequencyAxisMHz[i], 0));
				}
				cachedDataItems.add(list);
			}
		}
	}

	/**
	 * Adds new data to spectrum's dataset. Samples that fall in a gap
	 * between segments (only possible for multi-segment plans) are silently
	 * dropped - the native side may still send a few near a segment edge if
	 * the tuning step doesn't divide the gap evenly.
	 *
	 * @return true if the whole spectrum was refreshed once
	 */
	public boolean addNewData(FFTBins fftBins)
	{
		boolean triggerRefresh = false;
		triggerRefresh	= fftBins.fullSweepDone;

		for (int binsIndex = 0; binsIndex < fftBins.freqStart.length; binsIndex++)
		{
			double freqStart = fftBins.freqStart[binsIndex];
			int spectrIndex = plan.rfHzToGlobalBin(freqStart, fftBinSizeHz);
			if (spectrIndex < 0 || spectrIndex >= spectrum.length)
				continue;
			spectrum[spectrIndex] = fftBins.sigPowdBm[binsIndex];
		}

		return triggerRefresh;
	}
	
	public DatasetSpectrum cloneMe()
	{
		DatasetSpectrum copy;
		try
		{
			copy = (DatasetSpectrum) clone();
		}
		catch (CloneNotSupportedException e)
		{
			// DatasetSpectrum implements Cloneable so this branch is
			// unreachable; convert to AssertionError to fail fast instead
			// of returning null and crashing later with NPE.
			throw new AssertionError("DatasetSpectrum.clone()", e);
		}
		return copy;
	}

	/**
	 * Copies spectrum to destination dataset
	 * @param filtered
	 */
	public void copyTo(DatasetSpectrum filtered)
	{
		System.arraycopy(spectrum, 0, filtered.spectrum, 0, spectrum.length);
	}

	/**
	 * Creates {@link XYSeriesImmutable} from spectrum data 
	 * @param name
	 * @return
	 */
	public XYSeriesImmutable createSpectrumDataset(String name) {
		return new XYSeriesImmutable(name, frequencyAxisMHz, spectrumSnapshot);
	}

	/**
	 * Copy live working arrays into snapshot buffers. Must be called on the
	 * processing thread between {@code refresh*Spectrum()} and publishing the
	 * {@code SpectrumFrame} so the FX render reads stable data.
	 */
	public void snapshotForChart(boolean realtime) {
		if (realtime) {
			System.arraycopy(spectrum, 0, spectrumSnapshot, 0, spectrum.length);
		}
	}
	
	/**
	 * Fills data to {@link XYSeries}, uses x units in MHz
	 * @param series
	 */
	public void fillToXYSeries(XYSeries series)
	{
		fillToXYSeriesPriv(series, spectrum);
	}


	public float getFFTBinSizeHz()
	{
		return fftBinSizeHz;
	}
	
	public int getFreqStartMHz()
	{
		return freqStartMHz;
	}

	public int getFreqStopMHz()
	{
		return freqStopMHz;
	}
	
	public int getFreqShift()
	{
		return freqShift;
	}

	public FrequencyPlan getPlan() {
		return plan;
	}

	/**
	 * Translates index of spectrum to actual <b>RF</b> frequency in Hz
	 * (plan-aware). Use this anywhere a real radio frequency is needed (peak
	 * marker labels, allocation overlay, persistent display calibration).
	 */
	public double getFrequency(int index)
	{
		return plan.globalBinToRfMHz(index, fftBinSizeHz) * 1_000_000d;
	}

	/**
	 * Actual RF frequency in MHz (no shift) of the bin at {@code index}.
	 * Returned value is suitable for human-readable peak markers.
	 */
	public double rfFrequencyMHzAt(int index) {
		return plan.globalBinToRfMHz(index, fftBinSizeHz);
	}

	public float getPower(int index)
	{
		return spectrum[index];
	}

	public float[] getSpectrumArray()
	{
		return spectrum;
	}

	public void resetSpectrum()
	{
		Arrays.fill(spectrum, spectrumInitPower);
	}
	public void setSpectrumInitPower(float spectrumInitPower)
	{
		this.spectrumInitPower = spectrumInitPower;
	}
	
	public int spectrumLength()
	{
		return spectrum.length;
	}

	@Override protected Object clone() throws CloneNotSupportedException
	{
		DatasetSpectrum copy	= (DatasetSpectrum) super.clone();
		copy.spectrum			= spectrum.clone();
		return copy;
	}
	
	protected void fillToXYSeriesPriv(XYSeries series, float[] spectrum){
		series.clear();
		if (!useCached){
			for (int i = 0; i < spectrum.length; i++)
			{
				series.add(frequencyAxisMHz[i], spectrum[i]);
			}
		}
		else{
			ArrayList<XYDataItem> items	= cachedDataItems.get(cachedDataItemsIndex);
			for (int i = 0; i < spectrum.length; i++)
			{
				XYDataItem item	= items.get(i);
				item.setY(spectrum[i]);
				series.add(item);
			}
			cachedDataItemsIndex	= (cachedDataItemsIndex+1)%cachedDataItems.size();
		}
	}
}
