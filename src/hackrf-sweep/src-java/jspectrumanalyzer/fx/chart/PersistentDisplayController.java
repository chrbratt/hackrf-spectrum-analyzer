package jspectrumanalyzer.fx.chart;

import java.awt.geom.Rectangle2D;

import org.jfree.chart.plot.XYPlot;

import jspectrumanalyzer.core.DatasetSpectrum;
import jspectrumanalyzer.core.PersistentDisplay;
import jspectrumanalyzer.fx.engine.SpectrumFrame;
import jspectrumanalyzer.fx.model.SettingsStore;

/**
 * Thin wrapper around {@link PersistentDisplay} that fixes the aspect-ratio bug in the
 * legacy code.
 * <p>
 * The legacy Swing app called
 * {@code persistentDisplay.setImageSize(area.getWidth()/4, area.getWidth()/4)}, forcing
 * the heatmap to be square even on wide plots. This controller instead uses the real
 * chart data area width and height, so the heatmap matches the plot aspect ratio.
 */
public final class PersistentDisplayController {

    private final PersistentDisplay persistentDisplay = new PersistentDisplay();
    private final SettingsStore settings;
    private final SpectrumChart spectrumChart;
    private double lastWidth = -1;
    private double lastHeight = -1;

    public PersistentDisplayController(SettingsStore settings, SpectrumChart chart) {
        this.settings = settings;
        this.spectrumChart = chart;
        settings.getPersistentDisplayDecayRate().addListener(() ->
                persistentDisplay.setPersistenceTime(settings.getPersistentDisplayDecayRate().getValue()));
        persistentDisplay.setPersistenceTime(settings.getPersistentDisplayDecayRate().getValue());
    }

    public PersistentDisplay getPersistentDisplay() {
        return persistentDisplay;
    }

    /**
     * Update buffer size to match the plot's current data area. Should be called on the
     * FX thread (via {@link SpectrumChart#setDataAreaListener}) when the chart data area
     * changes.
     */
    public void onDataAreaChanged(Rectangle2D area) {
        if (area == null) return;
        int w = (int) Math.max(32, Math.floor(area.getWidth()));
        int h = (int) Math.max(32, Math.floor(area.getHeight()));
        if (w == lastWidth && h == lastHeight) return;
        lastWidth = w;
        lastHeight = h;
        persistentDisplay.setImageSize(w, h);
        applyBackgroundImage();
    }

    /**
     * Must be invoked on the processing thread after each sweep frame. Reads the current
     * amplitude range from the chart and drives the persistent display's EMA.
     */
    public void accumulate(SpectrumFrame frame, boolean render) {
        if (!settings.isPersistentDisplayVisible().getValue()) return;
        DatasetSpectrum dataset = frame.dataset;
        if (dataset == null) return;
        XYPlot plot = spectrumChart.getChart().getXYPlot();
        float yMin = (float) plot.getRangeAxis().getRange().getLowerBound();
        float yMax = (float) plot.getRangeAxis().getRange().getUpperBound();
        persistentDisplay.drawSpectrum2(dataset, yMin, yMax, render);
        if (render) {
            applyBackgroundImage();
        }
    }

    private void applyBackgroundImage() {
        XYPlot plot = spectrumChart.getChart().getXYPlot();
        if (settings.isPersistentDisplayVisible().getValue()) {
            plot.setBackgroundImage(persistentDisplay.getDisplayImage().getValue());
        } else {
            plot.setBackgroundImage(null);
        }
    }
}
