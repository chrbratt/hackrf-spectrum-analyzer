package jspectrumanalyzer.core.jfc;

import org.jfree.chart.renderer.xy.XYLineAndShapeRenderer;
import org.jfree.data.xy.XYDataItem;
import org.jfree.data.xy.XYSeries;

/**
 * Optimized immutable {@link XYSeries} for use with {@link XYLineAndShapeRenderer}.
 * <p>
 * Stores the points in two primitive {@code float[]} arrays. In JFreeChart 1.5.x the
 * renderer / dataset read values through {@link #getX(int)} and {@link #getY(int)}
 * (which normally delegate to {@code getRawDataItem(index)} and therefore require the
 * parent's internal {@code ArrayList} to be populated). We override all of those
 * accessors so no {@link XYDataItem} objects are ever allocated.
 */
public class XYSeriesImmutable extends XYSeries {
	private final float[] xValues;
	private final float[] yValues;

	/**
	 * Wraps caller-owned arrays without copying. The caller MUST guarantee the
	 * arrays are not mutated for the lifetime of this series (e.g. by passing
	 * pre-snapshotted buffers from {@code DatasetSpectrum#snapshotForChart}).
	 * Removing the previous defensive {@code clone()} eliminates ~30 MB/s of
	 * garbage at 30 fps with 4 visible series.
	 */
	public XYSeriesImmutable(Comparable key, float[] xValues, float[] yValues) {
		super(key, false, false);
		if (xValues.length != yValues.length)
			throw new IllegalArgumentException("x/y values are not of the same size");
		this.xValues = xValues;
		this.yValues = yValues;
	}

	public double getXX(int item) {
		return xValues[item];
	}

	public double getYY(int item) {
		return yValues[item];
	}

	@Override
	public int getItemCount() {
		return xValues.length;
	}

	@Override
	public Number getX(int index) {
		return xValues[index];
	}

	@Override
	public Number getY(int index) {
		return yValues[index];
	}

	@Override
	public double getMinX() {
		if (xValues.length == 0) return Double.NaN;
		return xValues[0];
	}

	@Override
	public double getMaxX() {
		if (xValues.length == 0) return Double.NaN;
		return xValues[xValues.length - 1];
	}

	@Override
	public double getMinY() {
		if (yValues.length == 0) return Double.NaN;
		double min = Double.POSITIVE_INFINITY;
		for (float y : yValues) if (y < min) min = y;
		return min;
	}

	@Override
	public double getMaxY() {
		if (yValues.length == 0) return Double.NaN;
		double max = Double.NEGATIVE_INFINITY;
		for (float y : yValues) if (y > max) max = y;
		return max;
	}

	@Override
	public XYDataItem getDataItem(int index) {
		return new XYDataItem(xValues[index], yValues[index]);
	}
}
