package jspectrumanalyzer.fx.chart;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.geom.Rectangle2D;
import java.awt.geom.Line2D;
import java.text.DecimalFormat;
import java.text.FieldPosition;
import java.text.NumberFormat;
import java.text.ParsePosition;

import org.jfree.chart.axis.AxisState;
import org.jfree.chart.axis.NumberAxis;
import org.jfree.chart.plot.PlotRenderingInfo;
import org.jfree.chart.ui.RectangleEdge;

import jspectrumanalyzer.core.FrequencyPlan;

/**
 * NumberAxis subclass for the stitched multi-band domain. Data x-values are
 * <em>logical</em> MHz (0..plan.totalLogicalSpan, all segments concatenated),
 * which lets JFreeChart's default value->pixel math draw the chart with no
 * gap regions. This axis only needs to:
 * <ol>
 *   <li>translate tick labels back from logical MHz to actual RF MHz so
 *       users still see real radio frequencies on the x-axis;</li>
 *   <li>paint a thin vertical separator line at every segment boundary so
 *       it's visually obvious where one band ends and the next begins.</li>
 * </ol>
 *
 * <p>For a single-segment plan this is equivalent to a stock NumberAxis -
 * the chart code therefore only installs StitchedNumberAxis when
 * {@link FrequencyPlan#isMultiSegment()} is true.
 */
public final class StitchedNumberAxis extends NumberAxis {

    private static final long serialVersionUID = 1L;

    private static final Color SEPARATOR_COLOR = new Color(0x808080);
    private static final float SEPARATOR_DASH_LENGTH = 4f;

    private final FrequencyPlan plan;
    /** Constant offset added to every logical MHz before display (matches DatasetSpectrum.freqShift). */
    private final int freqShift;

    public StitchedNumberAxis(String label, FrequencyPlan plan, int freqShift) {
        super(label);
        this.plan = plan;
        this.freqShift = freqShift;
        setNumberFormatOverride(new LogicalToRfFormat(plan, freqShift));
        setAutoRange(false);
        setRange(freqShift, plan.totalLogicalSpanMHz() + freqShift);
    }

    public FrequencyPlan getPlan() {
        return plan;
    }

    /**
     * Paint segment separator lines on top of the parent axis output. The
     * lines extend across the plot area, so we draw them in
     * {@link #draw(Graphics2D, double, Rectangle2D, Rectangle2D, RectangleEdge, PlotRenderingInfo)}
     * after delegating to {@code super}.
     */
    @Override
    public AxisState draw(Graphics2D g2, double cursor, Rectangle2D plotArea,
                          Rectangle2D dataArea, RectangleEdge edge,
                          PlotRenderingInfo plotState) {
        AxisState state = super.draw(g2, cursor, plotArea, dataArea, edge, plotState);
        if (!plan.isMultiSegment()) return state;

        // Boundaries live at the cumulative logical MHz position of each
        // segment after the first one. We draw a dashed grey vertical line
        // across the data area to make the discontinuity unmissable but
        // unobtrusive.
        Color oldPaint = (Color) g2.getColor();
        java.awt.Stroke oldStroke = g2.getStroke();
        try {
            g2.setColor(SEPARATOR_COLOR);
            g2.setStroke(new java.awt.BasicStroke(
                    1f,
                    java.awt.BasicStroke.CAP_BUTT,
                    java.awt.BasicStroke.JOIN_MITER,
                    1f,
                    new float[]{SEPARATOR_DASH_LENGTH, SEPARATOR_DASH_LENGTH},
                    0f));
            for (int i = 0; i < plan.segmentCount() - 1; i++) {
                double logicalBoundary = plan.logicalStartMHz(i + 1) + freqShift;
                double xPixel = valueToJava2D(logicalBoundary, dataArea, edge);
                g2.draw(new Line2D.Double(xPixel, dataArea.getMinY(),
                                          xPixel, dataArea.getMaxY()));
            }
        } finally {
            g2.setStroke(oldStroke);
            g2.setColor(oldPaint);
        }
        return state;
    }

    /**
     * NumberFormat that takes a logical MHz value (the tick position
     * JFreeChart hands us) and renders the corresponding RF MHz label. The
     * format is shared by every tick, so it has to be cheap - we avoid any
     * allocation in the hot path.
     */
    private static final class LogicalToRfFormat extends NumberFormat {
        private static final long serialVersionUID = 1L;
        private final FrequencyPlan plan;
        private final int freqShift;
        private final DecimalFormat inner = new DecimalFormat(" #.### ");

        LogicalToRfFormat(FrequencyPlan plan, int freqShift) {
            this.plan = plan;
            this.freqShift = freqShift;
        }

        @Override
        public StringBuffer format(double logicalMHzValue, StringBuffer toAppendTo,
                                   FieldPosition pos) {
            // Strip the constant frequency-shift offset so the plan can do its
            // logical->RF lookup against the shape it knows about.
            double logical = logicalMHzValue - freqShift;
            double rf = plan.logicalMHzToRfMHz(logical) + freqShift;
            return inner.format(rf, toAppendTo, pos);
        }

        @Override
        public StringBuffer format(long logicalMHzValue, StringBuffer toAppendTo,
                                   FieldPosition pos) {
            return format((double) logicalMHzValue, toAppendTo, pos);
        }

        @Override
        public Number parse(String source, ParsePosition parsePosition) {
            // The chart never round-trips text input back into a value, so a
            // best-effort identity parse is enough and keeps the API contract.
            return inner.parse(source, parsePosition);
        }
    }
}
