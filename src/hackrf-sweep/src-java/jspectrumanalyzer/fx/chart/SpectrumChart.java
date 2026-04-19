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
 * <p>The chart is split across three datasets so we can layer different
 * renderers without losing the shared X-axis:
 * <ul>
 *   <li><b>Dataset 0</b> &mdash; peaks + average lines, drawn with
 *       {@link XYLineAndShapeRenderer}.</li>
 *   <li><b>Dataset 1</b> &mdash; the realtime trace, drawn with
 *       {@link XYAreaRenderer} so we get a translucent fill under the line for
 *       the modern "FFT shadow" look.</li>
 *   <li><b>Dataset 2</b> &mdash; the max-hold trace, drawn with
 *       {@link FadeMaxHoldRenderer} which can colour-shift / alpha-fade
 *       individual bins based on their per-bin age (depending on the active
 *       {@link GraphTheme.MaxHoldEffect}).</li>
 * </ul>
 * Render order is REVERSE so high-index datasets paint first and dataset 0
 * (peaks + average) sits on top, keeping the most-read traces unobstructed.
 *
 * <p>All colours, strokes and the max-hold effect come from a single
 * {@link GraphTheme.Spec} held in {@link #currentSpec} - calling
 * {@link #applyTheme} re-skins the live chart in place. {@link LegendOverlay}
 * reads the same spec via {@link #currentSpec()}.
 */
public final class SpectrumChart {

    /** Y-axis range (dBm). Hard-coded; the persistent-display heatmap reads
     *  these so it can normalise pixels off-FX without touching the chart. */
    public static final float Y_MIN_DBM = -100f;
    public static final float Y_MAX_DBM = -10f;

    // Font shared by axis label + tick labels. Segoe UI is on every Windows
    // install since Vista; on other OSes Java falls back to Dialog which is
    // close enough that nothing looks broken.
    private static final Font AXIS_LABEL_FONT = new Font("Segoe UI", Font.PLAIN, 12);
    private static final Font AXIS_TICK_FONT  = new Font("Segoe UI", Font.PLAIN, 11);

    // Dashed strokes for grid + crosshair. Cheap, anti-aliased, much less
    // visually heavy than the solid 1px lines we used to ship. Stroke shape
    // is theme-independent; only the paint colour changes per theme.
    private static final BasicStroke GRID_STROKE = new BasicStroke(
            0.5f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 1f, new float[]{1f, 4f}, 0f);
    private static final BasicStroke CROSSHAIR_STROKE = new BasicStroke(
            1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 1f, new float[]{2f, 3f}, 0f);

    private final JFreeChart chart;
    private final ChartViewer chartViewer;
    private final XYSeriesCollection lineDataset;
    private final XYSeriesCollection areaDataset;
    private final XYSeriesCollection maxHoldDataset;
    private final XYLineAndShapeRenderer lineRenderer;
    private final XYAreaRenderer areaRenderer;
    private final FadeMaxHoldRenderer maxHoldRenderer;
    private final SettingsStore settings;

    /**
     * Active theme spec. Chart paint hooks ({@code updateSeries},
     * {@code applyDomainAxisForCurrentPlan}) read this every time they touch
     * the plot so a theme switch propagates without us having to re-create
     * the renderers or the axis. Mutated only on the FX thread.
     */
    private GraphTheme.Spec currentSpec = GraphTheme.CLASSIC.spec();

    private Rectangle2D lastDataArea = new Rectangle2D.Double(0, 0, 1, 1);

    public SpectrumChart(SettingsStore settings) {
        this.settings = settings;
        this.currentSpec = settings.getGraphTheme().getValue().spec();

        lineDataset = new XYSeriesCollection();
        areaDataset = new XYSeriesCollection();
        maxHoldDataset = new XYSeriesCollection();
        chart = ChartFactory.createXYLineChart("", "Frequency (MHz)", "Amplitude (dBm)",
                lineDataset, PlotOrientation.VERTICAL, false, false, false);

        XYPlot plot = chart.getXYPlot();
        plot.setOutlineVisible(false);
        plot.setDomainGridlineStroke(GRID_STROKE);
        plot.setRangeGridlineStroke(GRID_STROKE);
        plot.setDomainCrosshairStroke(CROSSHAIR_STROKE);
        plot.setRangeCrosshairStroke(CROSSHAIR_STROKE);

        NumberAxis rangeAxis = (NumberAxis) plot.getRangeAxis();
        rangeAxis.setAutoRange(false);
        rangeAxis.setRange(Y_MIN_DBM, Y_MAX_DBM);
        rangeAxis.setTickUnit(new NumberTickUnit(10, new DecimalFormat("###")));
        rangeAxis.setLabelFont(AXIS_LABEL_FONT);
        rangeAxis.setTickLabelFont(AXIS_TICK_FONT);

        applyDomainAxisForCurrentPlan();

        // Line renderer for peaks + average. Max-hold lives in its own
        // dataset (#2) with the fade-aware renderer below.
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

        // Dedicated dataset + renderer for max-hold so we can apply per-bin
        // fade colours without affecting the peaks / average renderer.
        maxHoldRenderer = new FadeMaxHoldRenderer(currentSpec.maxHold());
        maxHoldRenderer.setDefaultStroke(new BasicStroke(
                settings.getSpectrumLineThickness().getValue().floatValue()));
        plot.setDataset(2, maxHoldDataset);
        plot.setRenderer(2, maxHoldRenderer);

        // REVERSE order: highest-index dataset drawn first, lowest last.
        // -> dataset 2 (max-hold, behind), dataset 1 (realtime fill),
        //    dataset 0 (peaks + average, on top). This keeps peaks/average
        //    legible even when max-hold is bright red.
        plot.setDatasetRenderingOrder(DatasetRenderingOrder.REVERSE);

        if (chart.getTitle() != null) {
            chart.getTitle().setVisible(false);
        }

        chartViewer = new ChartViewer(chart);
        chartViewer.getStyleClass().add("spectrum-chart");

        applyTheme(currentSpec);

        // Plan or single-range change -> rebuild the domain axis. We swap the
        // axis instance entirely (rather than just calling setRange) because
        // moving between single- and multi-segment plans changes the axis
        // type (NumberAxis vs StitchedNumberAxis) and the tick label format.
        Runnable rebuild = () -> javafx.application.Platform.runLater(this::applyDomainAxisForCurrentPlan);
        settings.getFrequency().addListener(rebuild);
        settings.getFrequencyPlan().addListener(rebuild);
        settings.getFreqShift().addListener(rebuild);

        // Theme switch -> re-skin the chart in place (renderers, datasets and
        // the axis are reused; only colours / strokes / max-hold effect
        // change). Listener fires on the model thread; bounce to FX.
        settings.getGraphTheme().addListener(() -> javafx.application.Platform.runLater(
                () -> applyTheme(settings.getGraphTheme().getValue().spec())));
    }

    /**
     * Re-skin the chart with the colours and effect bundled in {@code spec}.
     * Called once at construction and again every time the user picks a new
     * theme from the Display tab. Idempotent.
     *
     * <p>Must be called on the FX thread.
     */
    public void applyTheme(GraphTheme.Spec spec) {
        this.currentSpec = spec;
        XYPlot plot = chart.getXYPlot();

        plot.setBackgroundPaint(new GradientPaint(0f, 0f, spec.bgTop(), 0f, 2000f, spec.bgBottom()));
        plot.setDomainGridlinePaint(spec.grid());
        plot.setRangeGridlinePaint(spec.grid());
        plot.setDomainCrosshairPaint(spec.crosshair());
        plot.setRangeCrosshairPaint(spec.crosshair());

        NumberAxis rangeAxis = (NumberAxis) plot.getRangeAxis();
        rangeAxis.setLabelPaint(spec.label());
        rangeAxis.setTickLabelPaint(spec.label());
        rangeAxis.setAxisLinePaint(spec.axisLine());

        if (plot.getDomainAxis() != null) {
            plot.getDomainAxis().setLabelPaint(spec.label());
            plot.getDomainAxis().setTickLabelPaint(spec.label());
            plot.getDomainAxis().setAxisLinePaint(spec.axisLine());
        }

        if (chart.getTitle() != null) chart.getTitle().setPaint(spec.title());
        // Chart-area background = the gradient's lower stop so the padding
        // around the plot doesn't show a hard colour seam.
        chart.setBackgroundPaint(spec.bgBottom());

        // The series-paint bindings on the line / area renderers are
        // re-applied per frame inside updateSeries(), so updating them here
        // would just be overwritten on the next sweep. Push the new max-hold
        // base colour straight to the fade renderer instead.
        maxHoldRenderer.setRenderState(
                FadeMaxHoldRenderer.RenderState.idle(spec.maxHold()));

        chart.fireChartChanged();
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
        // Read live theme colours so swapping the axis (single -> multi range)
        // never resets the look back to defaults.
        newAxis.setLabelPaint(currentSpec.label());
        newAxis.setTickLabelPaint(currentSpec.label());
        newAxis.setAxisLinePaint(currentSpec.axisLine());
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

        // Push the theme's smooth-fade preference to the dataset so the next
        // refreshMaxHoldSpectrum() pass interpolates (or doesn't) accordingly.
        // Cheap setter: a volatile boolean assignment, no copies.
        ds.setMaxHoldSmoothFade(
                currentSpec.maxHoldEffect() == GraphTheme.MaxHoldEffect.VALUE_FADE);

        chart.setNotify(false);
        try {
            lineDataset.removeAllSeries();
            areaDataset.removeAllSeries();
            maxHoldDataset.removeAllSeries();
            int lineIndex = 0;
            // JFreeChart 1.5.5 crashes on empty series (findLiveItemsLowerBound
            // indexes element 0 unconditionally), so we add only visible
            // series and re-apply per-index paint each frame.
            if (frame.showPeaks) {
                lineDataset.addSeries(ds.createPeaksDataset("peaks"));
                lineRenderer.setSeriesPaint(lineIndex++, currentSpec.peaks());
            }
            if (frame.showAverage) {
                lineDataset.addSeries(ds.createAverageDataset("average"));
                lineRenderer.setSeriesPaint(lineIndex++, currentSpec.average());
            }
            if (frame.showMaxHold) {
                maxHoldDataset.addSeries(ds.createMaxHoldDataset("maxhold"));
                // Hand the renderer a fresh snapshot of per-bin age + the
                // active effect. The renderer holds the array reference; the
                // dataset reuses the same underlying buffer between frames so
                // we don't allocate.
                maxHoldRenderer.setRenderState(new FadeMaxHoldRenderer.RenderState(
                        currentSpec.maxHoldEffect(),
                        currentSpec.maxHold(),
                        ds.getMaxHoldAgeMillisSnapshot(),
                        ds.getMaxHoldFalloutMillis()));
            }
            if (frame.showRealtime) {
                areaDataset.addSeries(ds.createSpectrumDataset("spectrum"));
                // Fill colour (translucent realtime hue) and on-top outline
                // (full-opacity realtime hue) keep the live trace readable
                // while still giving the modern "spectrogram halo" effect.
                Color rt = currentSpec.realtime();
                Color fill = new Color(rt.getRed(), rt.getGreen(), rt.getBlue(),
                        currentSpec.realtimeFillAlpha());
                areaRenderer.setSeriesPaint(0, fill);
                areaRenderer.setSeriesOutlinePaint(0, rt);
            }
        } finally {
            chart.setNotify(true);
        }
    }

    /** Latest applied theme spec - read by overlays (e.g. legend) that need
     *  the same paint values the chart is currently using. */
    public GraphTheme.Spec currentSpec() {
        return currentSpec;
    }

    public void setSpectrumLineThickness(float thickness) {
        lineRenderer.setDefaultStroke(new BasicStroke(thickness));
        areaRenderer.setDefaultStroke(new BasicStroke(Math.max(0.6f, thickness * 0.7f)));
    }

    public void setTitleFont(Font f) {
        if (chart.getTitle() != null) chart.getTitle().setFont(f);
    }
}
