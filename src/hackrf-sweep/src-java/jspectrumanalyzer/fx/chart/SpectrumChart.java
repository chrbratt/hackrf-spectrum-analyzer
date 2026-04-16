package jspectrumanalyzer.fx.chart;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.geom.Rectangle2D;
import java.text.DecimalFormat;
import java.util.function.Consumer;

import org.jfree.chart.ChartFactory;
import org.jfree.chart.JFreeChart;
import org.jfree.chart.axis.NumberAxis;
import org.jfree.chart.axis.NumberTickUnit;
import org.jfree.chart.event.ChartChangeEvent;
import org.jfree.chart.event.ChartChangeListener;
import org.jfree.chart.fx.ChartViewer;
import org.jfree.chart.plot.PlotOrientation;
import org.jfree.chart.plot.XYPlot;
import org.jfree.chart.renderer.xy.XYLineAndShapeRenderer;
import org.jfree.data.xy.XYSeries;
import org.jfree.data.xy.XYSeriesCollection;

import jspectrumanalyzer.core.DatasetSpectrumPeak;
import jspectrumanalyzer.core.FrequencyRange;
import jspectrumanalyzer.fx.engine.SpectrumFrame;
import jspectrumanalyzer.fx.model.SettingsStore;

/**
 * Hosts the existing {@link JFreeChart} inside a JavaFX {@link ChartViewer}.
 * <p>
 * Preserves the existing chart configuration (amplitude axis range, tick format, series
 * colors) from the legacy setup, and exposes a single {@link #updateSeries(SpectrumFrame)}
 * method to be called on the FX thread once per sweep frame.
 */
public final class SpectrumChart {

    private static final Color COLOR_PEAKS = new Color(0x11FF11);
    private static final Color COLOR_AVERAGE = new Color(0xFCFC00);
    private static final Color COLOR_MAX_HOLD = new Color(0xFF0000);
    private static final Color COLOR_REALTIME = new Color(0xAAAAAA);

    private final JFreeChart chart;
    private final ChartViewer chartViewer;
    private final XYSeriesCollection dataset;
    private final XYLineAndShapeRenderer renderer;

    private Rectangle2D lastDataArea = new Rectangle2D.Double(0, 0, 1, 1);

    public SpectrumChart(SettingsStore settings) {
        dataset = new XYSeriesCollection();
        chart = ChartFactory.createXYLineChart("", "Frequency (MHz)", "Amplitude (dBm)",
                dataset, PlotOrientation.VERTICAL, false, false, false);
        XYPlot plot = chart.getXYPlot();
        plot.setBackgroundPaint(Color.BLACK);
        plot.setDomainGridlinePaint(new Color(0x303030));
        plot.setRangeGridlinePaint(new Color(0x303030));
        plot.setOutlinePaint(Color.DARK_GRAY);
        plot.setDomainCrosshairPaint(Color.WHITE);
        plot.setRangeCrosshairPaint(Color.WHITE);

        NumberAxis rangeAxis = (NumberAxis) plot.getRangeAxis();
        rangeAxis.setAutoRange(false);
        rangeAxis.setRange(-100, -10);
        rangeAxis.setTickUnit(new NumberTickUnit(10, new DecimalFormat("###")));
        rangeAxis.setLabelPaint(Color.WHITE);
        rangeAxis.setTickLabelPaint(Color.LIGHT_GRAY);
        rangeAxis.setAxisLinePaint(Color.GRAY);

        NumberAxis domainAxis = (NumberAxis) plot.getDomainAxis();
        domainAxis.setNumberFormatOverride(new DecimalFormat(" #.### "));
        domainAxis.setLabelPaint(Color.WHITE);
        domainAxis.setTickLabelPaint(Color.LIGHT_GRAY);
        domainAxis.setAxisLinePaint(Color.GRAY);
        FrequencyRange freq = settings.getFrequency().getValue();
        domainAxis.setRange(freq.getStartMHz(), freq.getEndMHz());

        renderer = new XYLineAndShapeRenderer();
        renderer.setDefaultShapesVisible(false);
        renderer.setDefaultStroke(new BasicStroke(
                settings.getSpectrumLineThickness().getValue().floatValue()));
        renderer.setAutoPopulateSeriesStroke(false);
        renderer.setAutoPopulateSeriesPaint(false);
        // Stop the renderer from creating one ChartEntity per data point. With
        // 15 000+ bins per series at 30 fps that would produce ~1.8 M throwaway
        // entities every second, which dominated GC time after a minute or two.
        renderer.setDefaultCreateEntities(false);
        plot.setRenderer(renderer);

        if (chart.getTitle() != null) {
            chart.getTitle().setVisible(false);
        }
        chart.setBackgroundPaint(Color.BLACK);

        chartViewer = new ChartViewer(chart);
        chartViewer.getStyleClass().add("spectrum-chart");

        settings.getFrequency().addListener(() -> {
            FrequencyRange r = settings.getFrequency().getValue();
            javafx.application.Platform.runLater(() -> {
                chart.getXYPlot().getDomainAxis().setRange(
                        r.getStartMHz() + settings.getFreqShift().getValue(),
                        r.getEndMHz() + settings.getFreqShift().getValue());
            });
        });
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
        chart.addChangeListener(new ChartChangeListener() {
            @Override
            public void chartChanged(ChartChangeEvent event) {
                Rectangle2D area = getDataArea();
                if (area != null && (area.getWidth() != lastDataArea.getWidth()
                        || area.getHeight() != lastDataArea.getHeight()
                        || area.getX() != lastDataArea.getX()
                        || area.getY() != lastDataArea.getY())) {
                    lastDataArea = area;
                    javafx.application.Platform.runLater(() -> listener.accept(area));
                }
            }
        });
    }

    /**
     * Must be called on the FX thread. Replaces the series in the chart dataset with the
     * ones selected by the given frame. Annotations (peak/max-hold markers) are handled
     * separately by {@code ChartMarkers}.
     */
    public void updateSeries(SpectrumFrame frame) {
        DatasetSpectrumPeak ds = frame.dataset;

        chart.setNotify(false);
        try {
            dataset.removeAllSeries();
            int index = 0;
            // Only visible series are added. JFreeChart 1.5.5 crashes on empty series
            // (findLiveItemsLowerBound indexes element 0 unconditionally), so we cannot
            // pad with empty placeholders. The paint is therefore re-applied per-index.
            if (frame.showPeaks) {
                dataset.addSeries(ds.createPeaksDataset("peaks"));
                renderer.setSeriesPaint(index++, COLOR_PEAKS);
            }
            if (frame.showAverage) {
                dataset.addSeries(ds.createAverageDataset("average"));
                renderer.setSeriesPaint(index++, COLOR_AVERAGE);
            }
            if (frame.showMaxHold) {
                dataset.addSeries(ds.createMaxHoldDataset("maxhold"));
                renderer.setSeriesPaint(index++, COLOR_MAX_HOLD);
            }
            if (frame.showRealtime) {
                dataset.addSeries(ds.createSpectrumDataset("spectrum"));
                renderer.setSeriesPaint(index++, COLOR_REALTIME);
            }
        } finally {
            chart.setNotify(true);
        }
    }

    public void setSpectrumLineThickness(float thickness) {
        renderer.setDefaultStroke(new BasicStroke(thickness));
    }

    public void setTitleFont(Font f) {
        if (chart.getTitle() != null) chart.getTitle().setFont(f);
    }
}
