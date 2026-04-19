package jspectrumanalyzer.fx.chart;

import java.awt.Color;
import java.awt.Paint;

import org.jfree.chart.renderer.xy.XYLineAndShapeRenderer;

import jspectrumanalyzer.fx.chart.GraphTheme.MaxHoldEffect;

/**
 * {@link XYLineAndShapeRenderer} that paints the max-hold trace with a
 * per-bin colour / alpha that follows the bin's age.
 *
 * <p>The renderer is stateless between frames - {@link SpectrumChart} pushes
 * a fresh {@link RenderState} into it each time it rebuilds the dataset, so
 * there's no risk of reading stale {@code ages} arrays after the dataset has
 * been swapped.
 *
 * <p>For {@link MaxHoldEffect#NONE} the renderer behaves like a stock
 * {@link XYLineAndShapeRenderer} - we still install it on the chart so the
 * theme switch doesn't have to swap renderer instances on the fly (which
 * would force a full chart relayout). Effects that do animate read the
 * per-bin {@code ageMillis} array and normalise against {@code falloutMillis}
 * to produce a [0, 1] age ratio.
 */
public final class FadeMaxHoldRenderer extends XYLineAndShapeRenderer {

    private static final long serialVersionUID = 1L;

    /**
     * Snapshot of everything the per-item paint hook needs. Treated as
     * effectively immutable: {@link SpectrumChart#updateSeries} swaps the
     * whole record between frames rather than mutating individual fields,
     * so a single render pass always sees a consistent set of values.
     */
    public record RenderState(
            MaxHoldEffect effect,
            Color baseColor,
            long[] ageMillis,
            long falloutMillis) {
        /** "No-effect" sentinel used before the first frame arrives. */
        public static RenderState idle(Color baseColor) {
            return new RenderState(MaxHoldEffect.NONE, baseColor, null, 0);
        }
    }

    /**
     * Volatile so the FX render thread always sees the latest state pushed
     * by {@link SpectrumChart#updateSeries}, which runs on the FX thread too
     * but separated by the JFreeChart change-event boundary.
     */
    private volatile RenderState state;

    public FadeMaxHoldRenderer(Color initialBase) {
        super();
        this.state = RenderState.idle(initialBase);
        // Single max-hold series in this dataset (added by SpectrumChart).
        // Pin its base paint to the theme colour so JFreeChart's own legend
        // / tooltip code (if ever re-enabled) reads sensible values.
        setSeriesPaint(0, initialBase);
        setDefaultShapesVisible(false);
        setAutoPopulateSeriesPaint(false);
        setAutoPopulateSeriesStroke(false);
        // The chart has 15 000+ bins/series at 30 fps; per-bin entities turn
        // into a GC firehose. Disable like the other renderers.
        setDefaultCreateEntities(false);
    }

    /** Push a new render state. Cheap; safe to call once per data frame. */
    public void setRenderState(RenderState newState) {
        if (newState == null) return;
        this.state = newState;
        // Pin per-series paint too so any code path that doesn't go through
        // getItemPaint (e.g. legend swatches) still reads the right colour.
        setSeriesPaint(0, newState.baseColor());
    }

    @Override
    public Paint getItemPaint(int series, int item) {
        RenderState s = state;
        if (s.effect() == MaxHoldEffect.NONE
                || s.ageMillis() == null
                || s.falloutMillis() <= 0
                || item < 0 || item >= s.ageMillis().length) {
            return s.baseColor();
        }
        // Normalise the bin's age into [0, 1] - 0 means "just hit", 1 means
        // "right at lifetime end".
        double k = (double) s.ageMillis()[item] / (double) s.falloutMillis();
        if (k < 0) k = 0;
        if (k > 1) k = 1;

        return switch (s.effect()) {
            case VALUE_FADE  -> warmFade(s.baseColor(), k);
            case ALPHA_PULSE -> alphaPulse(s.baseColor(), k);
            case NONE        -> s.baseColor();
        };
    }

    /**
     * "Cooling ember" colour ramp for the Heatmap theme. Bin starts at the
     * theme's base red, drifts toward an amber/yellow midway, and ends as a
     * very translucent yellow at end-of-life. Combined with the value-fade
     * data path (the line is also visibly settling toward the live trace),
     * the eye reads aged peaks as orange/yellow ghosts on top of the live
     * spectrum - much clearer than today's binary disappearance.
     */
    private static Color warmFade(Color base, double k) {
        // Yellow target. Hand-picked rather than derived from `base` so the
        // ramp looks the same regardless of which red shade the theme picks.
        final int tr = 255, tg = 220, tb = 80;
        int r = lerp(base.getRed(),   tr, k);
        int g = lerp(base.getGreen(), tg, k);
        int b = lerp(base.getBlue(),  tb, k);
        // Alpha: full-on for the first half of life, gentle drop to ~40%
        // by lifetime end so the trace doesn't strobe in/out.
        int a = (int) Math.round(255 - 150 * k);
        return new Color(clamp(r), clamp(g), clamp(b), clamp(a));
    }

    /**
     * "Cool pulse" effect for the Cool Pulse theme: hue stays put, alpha
     * fades from full to zero. The trace gently dissolves rather than
     * shifting colour.
     */
    private static Color alphaPulse(Color base, double k) {
        int a = (int) Math.round(255 * (1.0 - k));
        return new Color(base.getRed(), base.getGreen(), base.getBlue(), clamp(a));
    }

    private static int lerp(int from, int to, double k) {
        return (int) Math.round(from + (to - from) * k);
    }

    private static int clamp(int v) {
        return v < 0 ? 0 : (v > 255 ? 255 : v);
    }
}
