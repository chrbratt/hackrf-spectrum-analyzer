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
     * lifetime expires (no decay animation). This is the default.
     */
    CLASSIC("Classic", new Spec(
            new Color(0x5BE572),  // peaks   - green
            new Color(0xF4C45A),  // average - amber
            new Color(0xFF6B6B),  // max-hold- coral red
            new Color(0x7BB6FF),  // realtime- cool blue
            new Color(0x14, 0x14, 0x1C),  // background top
            new Color(0x0A, 0x0A, 0x10),  // background bottom
            new Color(255, 255, 255, 28), // grid (~11% white)
            new Color(255, 255, 255, 80), // crosshair (~31% white)
            new Color(255, 255, 255, 60), // axis line
            new Color(0xC8, 0xC8, 0xD0),  // axis labels
            new Color(0xE6, 0xE6, 0xEC),  // chart title
            70,                            // realtime fill alpha (0-255)
            MaxHoldEffect.NONE)),

    /**
     * Warm "thermal camera" palette - deep red max-hold visibly cools down to
     * orange/yellow as it ages out, so old peaks read as "stale" before they
     * disappear entirely. Realtime gets a slightly warmer fill for cohesion.
     */
    HEATMAP("Heatmap", new Spec(
            new Color(0x8AE07A),  // peaks   - warm green
            new Color(0xF0A040),  // average - tangerine
            new Color(0xFF3030),  // max-hold- saturated red (start of cool-down ramp)
            new Color(0xFFB070),  // realtime- amber
            new Color(0x18, 0x10, 0x0E),
            new Color(0x0A, 0x06, 0x06),
            new Color(255, 200, 150, 30),
            new Color(255, 200, 150, 90),
            new Color(255, 200, 150, 70),
            new Color(0xE0, 0xC8, 0xB0),
            new Color(0xFF, 0xE6, 0xD0),
            70,
            MaxHoldEffect.VALUE_FADE)),

    /**
     * Cool monochromatic theme - everything sits in the cyan/blue/teal range
     * so the eye reads the chart as one calm signal field. Max-hold "pulses"
     * out: value stays put, the line just fades + shifts hue toward the
     * background, then disappears at lifetime end.
     */
    COOL_PULSE("Cool Pulse", new Spec(
            new Color(0x70E0E0),  // peaks   - cyan
            new Color(0xA0D8FF),  // average - icy blue
            new Color(0x80B0FF),  // max-hold- soft sky blue
            new Color(0x60C0E0),  // realtime- aqua
            new Color(0x10, 0x14, 0x1C),
            new Color(0x06, 0x08, 0x10),
            new Color(180, 220, 255, 28),
            new Color(180, 220, 255, 80),
            new Color(180, 220, 255, 60),
            new Color(0xC0, 0xD8, 0xE8),
            new Color(0xE0, 0xF0, 0xFF),
            55,
            MaxHoldEffect.ALPHA_PULSE)),

    /**
     * No-frills theme for screenshots / printouts: pure black background,
     * fully opaque primary trace colours, no fade animation, no realtime fill
     * gradient. What you see is exactly what gets archived.
     */
    HIGH_CONTRAST("High Contrast", new Spec(
            new Color(0x00FF60),  // peaks   - vivid green
            new Color(0xFFE000),  // average - vivid yellow
            new Color(0xFF2040),  // max-hold- vivid red
            new Color(0x40C0FF),  // realtime- vivid blue
            Color.BLACK,
            Color.BLACK,
            new Color(255, 255, 255, 50),
            new Color(255, 255, 255, 120),
            new Color(255, 255, 255, 100),
            new Color(0xFF, 0xFF, 0xFF),
            new Color(0xFF, 0xFF, 0xFF),
            0,                              // no realtime fill - just the line
            MaxHoldEffect.NONE));

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
            MaxHoldEffect maxHoldEffect) {

        public javafx.scene.paint.Color peaksFx()    { return toFx(peaks); }
        public javafx.scene.paint.Color averageFx()  { return toFx(average); }
        public javafx.scene.paint.Color maxHoldFx()  { return toFx(maxHold); }
        public javafx.scene.paint.Color realtimeFx() { return toFx(realtime); }

        private static javafx.scene.paint.Color toFx(Color c) {
            return javafx.scene.paint.Color.rgb(c.getRed(), c.getGreen(), c.getBlue());
        }
    }
}
