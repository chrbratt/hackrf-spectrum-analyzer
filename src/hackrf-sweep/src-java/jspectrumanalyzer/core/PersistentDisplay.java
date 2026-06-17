package jspectrumanalyzer.core;

import java.awt.Color;
import java.awt.image.BufferedImage;

import jspectrumanalyzer.ui.GraphicsToolkit;
import jspectrumanalyzer.ui.ColorPalette;
import jspectrumanalyzer.ui.HotIronBluePalette;
import shared.mvc.ModelValue;

public class PersistentDisplay {
	/**
	 * Image represented by single float array
	 */
	private static class FloatImage {
		private final float[]	data;
		private final int		width, height;

		public FloatImage(int width, int height) {
			data = new float[width * height];
			this.width = width;
			this.height = height;
		}

		public float add(int x, int y, float power) {
			return data[y * width + x] += power;
		}

		public float get(int x, int y) {
			return data[y * width + x];
		}

		public int getIndex(int x, int y) {
			return y * width + x;
		}

		public void multiplyAllValues(float value) {
			for (int i = 0; i < data.length; i++) {
				data[i] *= value;
			}
		}

		public void set(int x, int y, float value) {
			data[y * width + x] = value;
		}

		public void subtractAllValues(float value) {
			for (int i = 0; i < data.length; i++) {
				data[i] -= value;
			}
		}
	}

	public static float map(float in, float in_min, float in_max, float out_min, float out_max) {
		return (in - in_min) * (out_max - out_min) / (in_max - in_min) + out_min;
	}

	public static int map(int x, int in_min, int in_max, int out_min, int out_max) {
		return (x - in_min) * (out_max - out_min) / (in_max - in_min) + out_min;
	}

	private boolean						calibrated			= false;
	private boolean						calibrating			= false;
	private long						calibrationStarted	= 0;
	private final long					calibrationTime		= 1000;
	private ModelValue<BufferedImage>	displayImage		= new ModelValue<BufferedImage>("", null);
	private FloatImage					imagePowerAccumulated;
	private int							incomingDataCounter	= 0;
	/**
	 * Active palette for the heatmap. Volatile because the user can swap themes
	 * from the Display tab while a sweep is running; the next render reads the
	 * new instance. Old already-rendered pixels keep their original colours -
	 * the heatmap accumulates over time so re-rasterising would smear themes.
	 */
	private volatile ColorPalette		palette				= new HotIronBluePalette();
	private int							persistenceTimeSecs	= 5;
	private float						updatesPerSecond	= 1;

	/**
	 * Fixed accumulation ceiling for a continuously-present (frequency, power)
	 * cell. Deliberately decoupled from the persistence time: the old code tied
	 * the ceiling to {@code updatesPerSecond * persistenceTimeSecs}, which let a
	 * strong signal pile up so high that decaying it back to the zero threshold
	 * took roughly 13 half-lives - so a stopped signal lingered ~20 s even with
	 * "Persistence time" set to 5 s. A fixed ceiling keeps the dynamic range
	 * bounded so the slider maps to the real fade-out time.
	 */
	private static final float			MAX_FILL			= 80f;
	/**
	 * Fraction of {@link #MAX_FILL} at which a cell is treated as "gone" and
	 * cleared to the background. A saturated cell decays from {@code MAX_FILL}
	 * to this floor in about {@code persistenceTimeSecs} seconds.
	 */
	private static final float			FADE_FLOOR_FRACTION	= 0.02f;

	public PersistentDisplay() {
		setImageSize(320, 240);
	}

	public void drawSpectrum2(DatasetSpectrum datasetSpectrum, float yMin, float yMax, boolean renderImage) {
		drawSpectrumFloat(datasetSpectrum, yMin, yMax, renderImage);
	}

	public void drawSpectrumFloat(DatasetSpectrum datasetSpectrum, float yMin, float yMax, boolean renderImage) {
		if (!calibrated) {
			if (!calibrating) {
				calibrating = true;
				calibrationStarted = System.currentTimeMillis();
				incomingDataCounter = 0;
			} else {
				incomingDataCounter++;
				long t = System.currentTimeMillis() - calibrationStarted;
				if (t >= calibrationTime) {
					updatesPerSecond = (float) incomingDataCounter / (t / 1000f);
					// Use the dataset's actual bin count: for multi-segment
					// plans the (stop - start) span includes gaps that are
					// NOT allocated, so the legacy formula would oversize the
					// image and waste GPU upload bandwidth.
					int bins = datasetSpectrum.spectrumLength();
					BufferedImage image = displayImage.getValue();
					if (bins < image.getWidth()) {
						setImageSize(bins, image.getHeight());
					}
					calibrated = true;
					calibrating = false;

					if (updatesPerSecond < 1)
						updatesPerSecond = 1;
				}
			}
			return;
		}

		BufferedImage image = this.displayImage.getValue();
		FloatImage imagePowerAccumulated = this.imagePowerAccumulated;

		if (image == null)
			return;

		float rawImagePowerArr[] = imagePowerAccumulated.data;

		/**
		 * Phosphor-style exponential decay with an explicit time constant.
		 * The per-frame factor is chosen so a cell sitting at {@link #MAX_FILL}
		 * fades down to {@code MAX_FILL * FADE_FLOOR_FRACTION} (the clear-to-
		 * background threshold) in about {@code persistenceTimeSecs} seconds,
		 * regardless of frame rate. Cells that keep getting hit - including
		 * low-duty-cycle bursts - are topped back up below, so recurring
		 * signals stay lit and only signals that actually stop fade away.
		 */
		float secs = Math.max(1f, persistenceTimeSecs);
		float fadeFrames = Math.max(1f, secs * updatesPerSecond);
		float decay = (float) Math.pow(FADE_FLOOR_FRACTION, 1.0 / fadeFrames);
		imagePowerAccumulated.multiplyAllValues(decay);

		float[] spectrum = datasetSpectrum.getSpectrumArray();
		int width = image.getWidth();
		int height = image.getHeight();
		float hDivYRange = (-height) / (yMax - yMin);

		// Each render cycle the accumulator gets log-compressed and mapped
		// through the palette to produce the colour image. The +1 per hit
		// (capped at MAX_FILL) means brighter = hit more often, so the heatmap
		// still reads as an occupancy density, not just a recency flag.
		for (int i = 0; i < spectrum.length; i++) {
			float power = spectrum[i];
			int x = i * width / spectrum.length;
			int y = (int) ((power - yMin) * hDivYRange + height);

			if (x >= 0 && y >= 0 && x < width && y < height) {
				int index = imagePowerAccumulated.getIndex(x, y);
				if (imagePowerAccumulated.data[index] < MAX_FILL)
					imagePowerAccumulated.data[index] += 1f;
			}
		}

		if (renderImage) {
			float maxValue = Float.MIN_NORMAL;
			for (int i = 0; i < rawImagePowerArr.length; i++) {
				float value = rawImagePowerArr[i];
				if (value > maxValue)
					maxValue = value;
			}

			float setToZeroThreshold = MAX_FILL * FADE_FLOOR_FRACTION;
			float minOutToLog = 1.0f;
			float maxOutToLog = 100;
			float logMin = (float) Math.log10(minOutToLog);
			float logMax = (float) Math.log10(maxOutToLog);
			// Snapshot the volatile palette once for this whole frame so a
			// concurrent setPalette() can't tear the heatmap mid-render.
			ColorPalette p = palette;
			for (int x = 0; x < width; x++) {
				for (int y = 0; y < height; y++) {
					float val = imagePowerAccumulated.get(x, y);
					if (val < setToZeroThreshold) {
						imagePowerAccumulated.set(x, y, 0);
						val = 0;
					}

					if (val == 0) {
						image.setRGB(x, y, Color.black.getRGB());
					} else {
						float outPower = (float) Math.log10(
								map(val, 0, maxValue, minOutToLog, maxOutToLog));
						float normalized = map(outPower, logMin, logMax, 0.15f, 0.95f);
						Color color = p.getColorNormalized(normalized);
						image.setRGB(x, y, color.getRGB());
					}
				}
			}
		}
	}

	public ModelValue<BufferedImage> getDisplayImage() {
		return displayImage;
	}

	public int getPersistenceTime() {
		return persistenceTimeSecs;
	}

	public void reset() {
		BufferedImage image = displayImage.getValue();
		if (image != null) {
			setImageSize(image.getWidth(), image.getHeight());
		}
	}

	public void setImageSize(int width, int height) {
		if (width < 1 || height < 1)
			return;

		BufferedImage current = displayImage.getValue();
		if (current != null && current.getWidth() == width && current.getHeight() == height) {
			return;
		}

		calibrated = false;
		calibrating = false;

		displayImage.setValue(GraphicsToolkit.createAcceleratedImageOpaque(width, height));
		imagePowerAccumulated = new FloatImage(width, height);
	}

	public void setPersistenceTime(int persistenceTimeSecs) {
		this.persistenceTimeSecs = persistenceTimeSecs;
	}

	/**
	 * Replace the colour ramp used for new pixels. Existing accumulated pixels
	 * keep the colour they were last rendered with; switching themes mid-stream
	 * is mostly visible in the freshly painted areas after the call.
	 */
	public void setPalette(ColorPalette newPalette) {
		if (newPalette == null) return;
		this.palette = newPalette;
	}
}
