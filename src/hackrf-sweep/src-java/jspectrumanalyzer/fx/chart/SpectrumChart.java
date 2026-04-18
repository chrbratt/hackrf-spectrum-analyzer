package jspectrumanalyzer.fx.chart;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.GradientPaint;
import java.awt.geom.Rectangle2D;
import java.text.DecimalFormat;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import org.jfree.chart.ChartFactory;
import org.jfree.chart.JFreeChart;
import org.jfree.chart.axis.NumberAxis;
import org.jfree.chart.axis.NumberTickUnit;
import org.jfree.chart.event.ChartChangeEvent;
import org.jfree.chart.event.ChartChangeListener;
import org.jfree.chart.fx.ChartViewer;
import org.jfree.chart.plot.DatasetRenderingOrder;
import org.jfree.chart.plot.PlotOrientation;
import org.jfree.chart.plot.XYPlot;
import org.jfree.chart.renderer.xy.XYAreaRenderer;
import org.jfree.chart.renderer.xy.XYLineAndShapeRenderer;
import org.jfree.data.xy.XYSeriesCollection;

import jspectrumanalyzer.core.DatasetSpectrumPeak;
import jspectrumanalyzer.core.FrequencyPlan;
import jspectrumanalyzer.fx.engine.SpectrumFrame;
import jspectrumanalyzer.fx.model.SettingsStore;

/**
 * Hosts the existing {@link JFreeChart} inside a JavaFX {@link ChartViewer}.
 *
 * <p>The chart is split across two datasets so we can layer different
 * renderers without losing the shared X-axis:
 * <ul>
 *   <li><b>Dataset 0</b> &mdash; line series (peaks, average, max-hold) drawn
 *       with {@link XYLineAndShapeRenderer}. Each series picks its colour from
 *       {@link Palette}.</li>
 *   <li><b>Dataset 1</b> &mdash; the realtime trace, drawn with
 *       {@link XYAreaRenderer} so we get a translucent fill under the line for
 *       the modern "FFT shadow" look. Render order is REVERSE so dataset 1
 *       paints first (behind) and the line series sit on top.</li>
 * </ul>
 *
 * <p>All colours, fonts and strokes used here are also exported via
 * {@link Palette} so the on-screen legend overlay shows the exact same
 * mapping the chart uses.
 */
public final class SpectrumChart {

    /** Y-axis range (dBm). Hard-coded; the persistent-display heatmap reads
     *  these so it can normalise pixels off-FX without touching the chart. */
    public static final float Y_MIN_DBM = -100f;
    public static final float Y_MAX_DBM = -10f;

    /**
     * Chart trace colours, exported so {@code LegendOverlay} renders identical
     * chips.
     *
     * <p>The {@code _FX} mirrors are derived from the AWT constants at class-init
     * time so the two stay in lock-step automatically; consumers in the FX layer
     * (the legend in particular) read those and therefore don't need to import
     * {@code java.awt}.
     */
    public static final class Palette {
        public static final Color PEAKS    = new Color(0x5BE572);
        public static final Color AVERAGE  = new Color(0xF4C45A);
        public static final Color MAX_HOLD = new Color(0xFF6B6B);
        public static final Color REALTIME = new Color(0x7BB6FF);

        public static final javafx.scene.paint.Color PEAKS_FX    = toFx(PEAKS);
        public static final javafx.scene.paint.Color AVERAGE_FX  = toFx(AVERAGE);
        public static final javafx.scene.paint.Color MAX_HOLD_FX = toFx(MAX_HOLD);
        public static final javafx.scene.paint.Color REALTIME_FX = toFx(REALTIME);

        private static javafx.scene.paint.Color toFx(Color c) {
            return javafx.scene.paint.Color.rgb(c.getRed(), c.getGreen(), c.getBlue());
        }
        private Palette() {}
    }

    // Background and chrome.
    private static final Color BG_TOP    = new Color(0x14, 0x14, 0x1C);
    private static final Color BG_BOTTOM = new Color(0x0A, 0x0A, 0x10);
    private static final Color GRID      = new Color(255, 255, 255, 28);   // ~11% alpha
    private static final Color CROSSHAIR = new Color(255, 255, 255, 80);   // ~31% alpha
    private static final Color AXIS_LINE = new Color(255, 255, 255, 60);
    private static final Color LABEL     = new Color(0xC8, 0xC8, 0xD0);
    private static final Color TITLE     = new Color(0xE6, 0xE6, 0xEC);

    // Font shared by axis label + tick labels. Segoe UI is on every Windows
    // install since Vista; on other OSes Java falls back to Dialog which is
    // close enough that nothing looks broken.
    private static final Font AXIS_LABEL_FONT = new Font("Segoe UI", Font.PLAIN, 12);
    private static final Font AXIS_TICK_FONT  = new Font("Segoe UI", Font.PLAIN, 11);

    // Dashed strokes for grid + crosshair. Cheap, anti-aliased, much less
    // visually heavy than the solid 1px lines we used to ship.
    private static final BasicStroke GRID_STROKE = new BasicStroke(
            0.5f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 1f, new float[]{1f, 4f}, 0f);
    private static final BasicStroke CROSSHAIR_STROKE = new BasicStroke(
            1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 1f, new float[]{2f, 3f}, 0f);

    // Realtime fill = realtime colour at low alpha. Solid (rather than a
    // GradientPaint) keeps the look stable when the chart resizes - a
    // pixel-space gradient would shift visibly while dragging the splitter.
    private static final Color REALTIME_FILL = new Color(
            Palette.REALTIME.getRed(), Palette.REALTIME.getGreen(), Palette.REALTIME.getBlue(), 70);

    private final JFreeChart chart;
    private final ChartViewer chartViewer;
    private final XYSeriesCollection lineDataset;
    private final XYSeriesCollection areaDataset;
    private final XYLineAndShapeRenderer lineRenderer;
    private final XYAreaRenderer areaRenderer;
    private final SettingsStore settings;

    private Rectangle2D lastDataArea = new Rectangle2D.Double(0, 0, 1, 1);

    public SpectrumChart(SettingsStore settings) {
        this.settings = settings;
        lineDataset = new XYSeriesCollection();
        areaDataset = new XYSeriesCollection();
        chart = ChartFactory.createXYLineChart("", "Frequency (MHz)", "Amplitude (dBm)",
                lineDataset, PlotOrientation.VERTICAL, false, false, false);

        XYPlot plot = chart.getXYPlot();
        // Vertical gradient: marginally brighter at the top, deeper near the
        // bottom. Coordinates are in pixels (Java2D user space). Picking a
        // span far larger than any realistic chart height makes the gradient
        // look smooth even on very tall windows; the ends just clamp.
        plot.setBackgroundPaint(new GradientPaint(0f, 0f, BG_TOP, 0f, 2000f, BG_BOTTOM));
        plot.setDomainGridlinePaint(GRID);
        plot.setRangeGridlinePaint(GRID);
        plot.setDomainGridlineStroke(GRID_STROKE);
        plot.setRangeGridlineStroke(GRID_STROKE);
        plot.setOutlineVisible(false);
        plot.setDomainCrosshairPaint(CROSSHAIR);
        plot.setRangeCrosshairPaint(CROSSHAIR);
        plot.setDomainCrosshairStroke(CROSSHAIR_STROKE);
        plot.setRangeCrosshairStroke(CROSSHAIR_STROKE);

        NumberAxis rangeAxis = (NumberAxis) plot.getRangeAxis();
        rangeAxis.setAutoRange(false);
        rangeAxis.setRange(Y_MIN_DBM, Y_MAX_DBM);
        rangeAxis.setTickUnit(new NumberTickUnit(10, new DecimalFormat("###")));
        rangeAxis.setLabelPaint(LABEL);
        rangeAxis.setTickLabelPaint(LABEL);
        rangeAxis.setAxisLinePaint(AXIS_LINE);
        rangeAxis.setLabelFont(AXIS_LABEL_FONT);
        rangeAxis.setTickLabelFont(AXIS_TICK_FONT);

        applyDomainAxisForCurrentPlan();

        // Line renderer used for peaks / average / max-hold.
        lineRenderer = new XYLineAndShapeRenderer();
        lineRenderer.setDefaultShapesVisible(false);
        lineRenderer.setDefaultStroke(new BasicStroke(
                settings.getSpectrumLineThickness().getValue().floatValue()));
        lineRenderer.setAutoPopulateSeriesStroke(false);
        lineRenderer.setAutoPopulateSeriesPaint(false);
        // Stop the renderer from creating one ChartEntity per data point. With
        // 15 000+ bins per series at 30 fps that would produce ~1.8 M throwaway
        // entities every second, which dominated GC time after a minute or two.
        lineRenderer.setDefaultCreateEntities(false);
        plot.setRenderer(0, lineRenderer);

        // Area renderer used only for the realtime trace. AREA-only mode
        // skips per-bin shape entities (we have ~15 000 bins per sweep);
        // setOutline(true) re-adds the line along the top of the fill so the
        // realtime trace still has a crisp edge against the translucent body.
        areaRenderer = new XYAreaRenderer(XYAreaRenderer.AREA);
        areaRenderer.setOutline(true);
        areaRenderer.setDefaultStroke(new BasicStroke(
                Math.max(0.6f, settings.getSpectrumLineThickness().getValue().floatValue() * 0.7f)));
        areaRenderer.setAutoPopulateSeriesStroke(false);
        areaRenderer.setAutoPopulateSeriesPaint(false);
        areaRenderer.setAutoPopulateSeriesOutlinePaint(false);
        areaRenderer.setAutoPopulateSeriesOutlineStroke(false);
        areaRenderer.setDefaultCreateEntities(false);
        plot.setDataset(1, areaDataset);
        plot.setRenderer(1, areaRenderer);
        // REVERSE order: dataset 1 (area, behind) drawn first, dataset 0
        // (line series, in front) drawn last. Without this the area would
        // cover the peak/avg/max lines.
        plot.setDatasetRenderingOrder(DatasetRenderingOrder.REVERSE);

        if (chart.getTitle() != null) {
            chart.getTitle().setVisible(false);
            chart.getTitle().setPaint(TITLE);
        }
        // Match the plot background to the chart background so the modest
        // padding around the plot area also picks up the gradient.
        chart.setBackgroundPaint(BG_BOTTOM);

        chartViewer = new ChartViewer(chart);
        chartViewer.getStyleClass().add("spectrum-chart");

        // Plan or single-range change -> rebuild the domain axis. We swap the
        // axis instance entirely (rather than just calling setRange) because
        // moving between single- and multi-segment plans changes the axis
        // type (NumberAxis vs StitchedNumberAxis) and the tick label format.
        Runnable rebuild = () -> javafx.application.Platform.runLater(this::applyDomainAxisForCurrentPlan);
        settings.getFrequency().addListener(rebuild);
        settings.getFrequencyPlan().addListener(rebuild);
        settings.getFreqShift().addListener(rebuild);
    }

    /**
     * Replace the chart's domain axis to match the current effective plan.
     *
     * <p>{@link DatasetSpectrumPeak} always plots in <em>logical</em> MHz
     * (0..plan.totalLogicalSpan), so the axis must span the same logical
     * range. {@link StitchedNumberAxis} both sets that range and translates
     * tick labels back to real RF MHz, which is exactly what we need for
     * single- and multi-segment plans alike. For a single segment it draws no
     * separator lines and behaves like a stock {@link NumberAxis}.
     *
     * <p>Must be called on the FX thread (mutates the chart).
     */
    private void applyDomainAxisForCurrentPlan() {
        FrequencyPlan plan = settings.getEffectivePlan();
        int shift = settings.getFreqShift().getValue();
        XYPlot plot = chart.getXYPlot();
        StitchedNumberAxis newAxis = new StitchedNumberAxis("Frequency (MHz)", plan, shift);
        newAxis.setLabelPaint(LABEL);
        newAxis.setTickLabelPaint(LABEL);
        newAxis.setAxisLinePaint(AXIS_LINE);
        newAxis.setLabelFont(AXIS_LABEL_FONT);
        newAxis.setTickLabelFont(AXIS_TICK_FONT);
        plot.setDomainAxis(newAxis);
    }

    public ChartViewer getViewer() {
        return chartViewer;
    }

    public JFreeChart getChart() {
        return chart;
    }

    public Rectangle2D getDataArea() {
        return chartViewer.getCanvas().getRenderingInfo() != null
                && chartViewer.getCanvas().getRenderingInfo().getPlotInfo() != null
                ? chartViewer.getCanvas().getRenderingInfo().getPlotInfo().getDataArea()
                : lastDataArea;
    }

    /**
     * Register a listener called on the FX thread whenever the plot's data area
     * (in canvas pixels) changes. Used to resize the waterfall canvas and persistent
     * display buffers to the actual plot width.
     */
    public void setDataAreaListener(Consumer<Rectangle2D> listener) {
        // Coalesce rapid chart-change bursts (e.g. split-pane drags or 30 fps
        // dataset notifies) into at most one FX delivery per frame. We store
        // the latest area in a hand-off slot and only post a runLater if
        // there isn't one already pending; the runLater drains the slot.
        AtomicReference<Rectangle2D> pending = new AtomicReference<>();
        AtomicBoolean scheduled = new AtomicBoolean(false);
        chart.addChangeListener(new ChartChangeListener() {
            @Override
            public void chartChanged(ChartChangeEvent event) {
                Rectangle2D area = getDataArea();
                if (area == null) return;
                if (area.getWidth() == lastDataArea.getWidth()
                        && area.getHeight() == lastDataArea.getHeight()
                        && area.getX() == lastDataArea.getX()
                        && area.getY() == lastDataArea.getY()) {
                    return;
                }
                lastDataArea = area;
                pending.set(area);
                if (scheduled.compareAndSet(false, true)) {
                    javafx.application.Platform.runLater(() -> {
                        scheduled.set(false);
                        Rectangle2D latest = pending.getAndSet(null);
                        if (latest != null) listener.accept(latest);
                    });
                }
            }
        });
    }

    /**
     * Must be called on the FX thread. Replaces the series in both datasets
     * with the ones selected by {@code frame}. Line series go to dataset 0
     * (line renderer); the realtime trace goes to dataset 1 (area renderer).
     */
    public void updateSeries(SpectrumFrame frame) {
        DatasetSpectrumPeak ds = frame.dataset;

        chart.setNotify(false);
        try {
            lineDataset.removeAllSeries();
            areaDataset.removeAllSeries();
            int lineIndex = 0;
            // JFreeChart 1.5.5 crashes on empty series (findLiveItemsLowerBound
            // indexes element 0 unconditionally), so we add only visible
            // series and re-apply per-index paint each frame.
            if (frame.showPeaks) {
                lineDataset.addSeries(ds.createPeaksDataset("peaks"));
                lineRenderer.setSeriesPaint(lineIndex++, Palette.PEAKS);
            }
            if (frame.showAverage) {
                lineDataset.addSeries(ds.createAverageDataset("average"));
                lineRenderer.setSeriesPaint(lineIndex++, Palette.AVERAGE);
            }
            if (frame.showMaxHold) {
                lineDataset.addSeries(ds.createMaxHoldDataset("maxhold"));
                lineRenderer.setSeriesPaint(lineIndex++, Palette.MAX_HOLD);
            }
            if (frame.showRealtime) {
                areaDataset.addSeries(ds.createSpectrumDataset("spectrum"));
                // Fill colour (translucent realtime hue) and on-top outline
                // (full-opacity realtime hue) keep the live trace readable
                // while still giving the modern "spectrogram halo" effect.
                areaRenderer.setSeriesPaint(0, REALTIME_FILL);
                areaRenderer.setSeriesOutlinePaint(0, Palette.REALTIME);
            }
        } finally {
            chart.setNotify(true);
        }
    }

    public void setSpectrumLineThickness(float thickness) {
        lineRenderer.setDefaultStroke(new BasicStroke(thickness));
        areaRenderer.setDefaultStroke(new BasicStroke(Math.max(0.6f, thickness * 0.7f)));
    }

    public void setTitleFont(Font f) {
        if (chart.getTitle() != null) chart.getTitle().setFont(f);
    }
}
