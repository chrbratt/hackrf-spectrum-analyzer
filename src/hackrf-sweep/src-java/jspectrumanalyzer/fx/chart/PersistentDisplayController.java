package jspectrumanalyzer.fx.chart;

import java.awt.geom.Rectangle2D;
import java.util.concurrent.atomic.AtomicBoolean;

import javafx.application.Platform;

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

    /**
     * Coalesces background-image updates posted from the processing thread.
     * If FX hasn't drained the previous request yet we simply let the next
     * render reuse the same {@link Platform#runLater} slot; the background
     * image points to a single {@code BufferedImage} that already holds the
     * most recent pixels, so dropping intermediate notifications is lossless.
     */
    private final AtomicBoolean fxUpdatePending = new AtomicBoolean(false);

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
        // Apply a small dead-band so a one-pixel shimmer in the chart layout
        // (caused by tick-label width changes when amplitudes scroll past
        // round numbers) doesn't constantly tear down and re-allocate the
        // EMA buffer, which would prevent calibration from ever completing.
        if (Math.abs(w - lastWidth) < 4 && Math.abs(h - lastHeight) < 4) return;
        lastWidth = w;
        lastHeight = h;
        persistentDisplay.setImageSize(w, h);
        applyBackgroundImageOnFx();
    }

    /**
     * Invoked on the {@code SpectrumEngine} processing thread after each sweep frame.
     * Runs the EMA / pixel-render off-FX, then schedules the chart-mutation step
     * (which must touch {@link XYPlot#setBackgroundImage} and therefore the FX
     * GraphicsContext) on the FX thread. Wrapped in a guard so a transient JFreeChart
     * hiccup never bubbles up and kills the engine's frame consumer.
     */
    public void accumulate(SpectrumFrame frame, boolean render) {
        if (!settings.isPersistentDisplayVisible().getValue()) return;
        DatasetSpectrum dataset = frame.dataset;
        if (dataset == null) return;
        try {
            XYPlot plot = spectrumChart.getChart().getXYPlot();
            // Range axis is configured once at construction and never mutated, so
            // reading it from the processing thread is a safe reference snapshot.
            float yMin = (float) plot.getRangeAxis().getRange().getLowerBound();
            float yMax = (float) plot.getRangeAxis().getRange().getUpperBound();
            persistentDisplay.drawSpectrum2(dataset, yMin, yMax, render);
            if (render) {
                scheduleFxBackgroundUpdate();
            }
        } catch (Throwable t) {
            t.printStackTrace();
        }
    }

    /**
     * Coalesce repeated render notifications: only one runLater is in flight at a
     * time. The most recent pixels in the {@code BufferedImage} are picked up by
     * whichever drain wins.
     */
    private void scheduleFxBackgroundUpdate() {
        if (fxUpdatePending.compareAndSet(false, true)) {
            Platform.runLater(() -> {
                try {
                    applyBackgroundImageOnFx();
                } finally {
                    fxUpdatePending.set(false);
                }
            });
        }
    }

    /** Must be invoked on the FX thread. */
    private void applyBackgroundImageOnFx() {
        try {
            XYPlot plot = spectrumChart.getChart().getXYPlot();
            if (settings.isPersistentDisplayVisible().getValue()) {
                plot.setBackgroundImage(persistentDisplay.getDisplayImage().getValue());
            } else {
                plot.setBackgroundImage(null);
            }
        } catch (Throwable t) {
            t.printStackTrace();
        }
    }
}
