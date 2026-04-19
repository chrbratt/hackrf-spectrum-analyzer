package jspectrumanalyzer.fx.chart;

import java.awt.Color;

/**
 * Catalogue of visual themes for the spectrum chart.
 *
 * <p>Each theme bundles every colour the renderer needs (trace paints,
 * background gradient stops, grid + crosshair, axis chrome) plus a
 * {@link MaxHoldEffect} that controls how the max-hold trace ages out. Keeping
 * everything inside a single immutable {@link Spec} record means
 * {@code SpectrumChart#applyTheme} can re-skin the chart by reading exactly one
 * field per knob - no scattered "if theme == ..." branches inside the renderer.
 *
 * <p>FX-side mirrors of the trace colours (used by {@code LegendOverlay}) are
 * derived lazily by {@link Spec#peaksFx()} etc. so callers in the FX layer
 * don't need to import {@code java.awt}.
 */
public enum GraphTheme {

    /**
     * The look this app shipped with: bright primary trace colours on a near-
     * black gradient, max-hold drops back to the live sample the moment its
     * lifetime expires (no decay animation). No strength-mapped gradient -
     * the original users wanted a flat, predictable trace colour.
     */
    CLASSIC("Classic", new Spec(
            new Color(0x5BE572),
            new Color(0xF4C45A),
            new Color(0xFF6B6B),
            new Color(0x7BB6FF),
            new Color(0x14, 0x14, 0x1C),
            new Color(0x0A, 0x0A, 0x10),
            new Color(255, 255, 255, 28),
            new Color(255, 255, 255, 80),
            new Color(255, 255, 255, 60),
            new Color(0xC8, 0xC8, 0xD0),
            new Color(0xE6, 0xE6, 0xEC),
            70,
            MaxHoldEffect.NONE,
            null, null)),

    /**
     * Warm "thermal camera" palette - deep red max-hold visibly cools down to
     * orange/yellow as it ages out. Strength gradient: orange at the noise
     * floor ramping to white-hot at strong-signal levels, mirroring the way
     * a real thermal sensor presents temperature.
     */
    HEATMAP("Heatmap", new Spec(
            new Color(0x8AE07A),
            new Color(0xF0A040),
            new Color(0xFF3030),
            new Color(0xFFB070),
            new Color(0x18, 0x10, 0x0E),
            new Color(0x0A, 0x06, 0x06),
            new Color(255, 200, 150, 30),
            new Color(255, 200, 150, 90),
            new Color(255, 200, 150, 70),
            new Color(0xE0, 0xC8, 0xB0),
            new Color(0xFF, 0xE6, 0xD0),
            70,
            MaxHoldEffect.VALUE_FADE,
            new Color(0xC8, 0x50, 0x10),   // strengthLow  - dim orange (noise floor)
            new Color(0xFF, 0xF0, 0xC0))), // strengthHigh - white-hot (strong)

    /**
     * Cool monochromatic theme - everything sits in the cyan/blue/teal range
     * so the eye reads the chart as one calm signal field. Max-hold "pulses"
     * out. Strength gradient: deep ocean blue at noise floor ramping to icy
     * cyan at strong-signal levels, so the eye instinctively reads "louder
     * = brighter" without leaving the cool palette.
     */
    COOL_PULSE("Cool Pulse", new Spec(
            new Color(0x70E0E0),
            new Color(0xA0D8FF),
            new Color(0x80B0FF),
            new Color(0x60C0E0),
            new Color(0x10, 0x14, 0x1C),
            new Color(0x06, 0x08, 0x10),
            new Color(180, 220, 255, 28),
            new Color(180, 220, 255, 80),
            new Color(180, 220, 255, 60),
            new Color(0xC0, 0xD8, 0xE8),
            new Color(0xE0, 0xF0, 0xFF),
            55,
            MaxHoldEffect.ALPHA_PULSE,
            new Color(0x20, 0x40, 0x80),   // strengthLow  - deep blue
            new Color(0xC0, 0xF0, 0xFF))), // strengthHigh - icy cyan-white

    /**
     * No-frills theme for screenshots / printouts: pure black background,
     * fully opaque primary trace colours, no fade animation, no strength
     * gradient (a screenshot needs to read the same regardless of where the
     * peaks happen to land vertically).
     */
    HIGH_CONTRAST("High Contrast", new Spec(
            new Color(0x00FF60),
            new Color(0xFFE000),
            new Color(0xFF2040),
            new Color(0x40C0FF),
            Color.BLACK,
            Color.BLACK,
            new Color(255, 255, 255, 50),
            new Color(255, 255, 255, 120),
            new Color(255, 255, 255, 100),
            new Color(0xFF, 0xFF, 0xFF),
            new Color(0xFF, 0xFF, 0xFF),
            0,
            MaxHoldEffect.NONE,
            null, null));

    private final String displayName;
    private final Spec spec;

    GraphTheme(String displayName, Spec spec) {
        this.displayName = displayName;
        this.spec = spec;
    }

    public Spec spec() {
        return spec;
    }

    @Override
    public String toString() {
        return displayName;
    }

    /**
     * Behaviour applied to the max-hold trace when its per-bin lifetime
     * elapses. Independent of how the chart paints other traces - some themes
     * deliberately turn this off (CLASSIC, HIGH_CONTRAST) so the chart stays
     * static and easy to read.
     */
    public enum MaxHoldEffect {
        /** Bin drops directly to the live sample when its lifetime expires. */
        NONE,
        /** Held value linearly drifts toward the live sample over its
         *  lifetime; renderer also shifts the per-bin colour toward the
         *  background as age increases. */
        VALUE_FADE,
        /** Held value stays put; renderer fades the per-bin alpha toward 0
         *  and slightly shifts hue as age increases. */
        ALPHA_PULSE
    }

    /**
     * Immutable bundle of every colour + behaviour knob the chart needs.
     * Defining it as a {@code record} means a theme change is a simple field
     * read and never accidentally aliases mutable state across themes.
     *
     * <p>{@code strengthLow} / {@code strengthHigh} are optional. When both
     * are set the chart paints peaks + realtime with a vertical gradient
     * spanning {@link #STRENGTH_NOISE_DBM} (noise floor) at {@code low} to
     * {@link #STRENGTH_STRONG_DBM} (loud signal) at {@code high}. When either
     * is null the renderer falls back to the flat {@code peaks} / {@code
     * realtime} colours.
     */
    public record Spec(
            Color peaks,
            Color average,
            Color maxHold,
            Color realtime,
            Color bgTop,
            Color bgBottom,
            Color grid,
            Color crosshair,
            Color axisLine,
            Color label,
            Color title,
            int realtimeFillAlpha,
            MaxHoldEffect maxHoldEffect,
            Color strengthLow,
            Color strengthHigh) {

        public boolean hasStrengthGradient() {
            return strengthLow != null && strengthHigh != null;
        }

        public javafx.scene.paint.Color peaksFx()    { return toFx(peaks); }
        public javafx.scene.paint.Color averageFx()  { return toFx(average); }
        public javafx.scene.paint.Color maxHoldFx()  { return toFx(maxHold); }
        public javafx.scene.paint.Color realtimeFx() { return toFx(realtime); }

        private static javafx.scene.paint.Color toFx(Color c) {
            return javafx.scene.paint.Color.rgb(c.getRed(), c.getGreen(), c.getBlue());
        }
    }

    /** Bottom of the strength gradient ("noise floor"). Anything weaker
     *  clamps to {@code strengthLow}. Picked to match a typical HackRF
     *  noise floor with the internal LNA on. */
    public static final float STRENGTH_NOISE_DBM = -90f;

    /** Top of the strength gradient ("loud signal"). Anything stronger
     *  clamps to {@code strengthHigh}. Picked so a Wi-Fi / FM beacon a few
     *  metres from the antenna lands solidly in the bright zone. */
    public static final float STRENGTH_STRONG_DBM = -50f;
}
