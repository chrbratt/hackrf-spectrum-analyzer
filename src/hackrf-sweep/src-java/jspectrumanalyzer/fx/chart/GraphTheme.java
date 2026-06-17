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
            null, -90f, -50f)),

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
            new Color[] {
                    new Color(0xC8, 0x50, 0x10),   // dim orange (noise floor)
                    new Color(0xFF, 0xF0, 0xC0)    // white-hot (strong)
            },
            -90f, -50f)),

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
            new Color[] {
                    new Color(0x20, 0x40, 0x80),   // deep blue (noise floor)
                    new Color(0xC0, 0xF0, 0xFF)    // icy cyan-white (strong)
            },
            -90f, -50f)),

    /**
     * High-saturation spectrum-analyzer look tuned for the real dynamic range
     * of terrestrial signals (roughly 20-30 dB above the noise floor). The
     * strength gradient runs through a full blue -> cyan -> green -> yellow ->
     * orange -> red ramp over a deliberately tight -88 to -62 dBm window, so a
     * signal only ~26 dB above the floor already reaches the hot red end
     * instead of stalling in the mid tones. Max-hold cools down like the
     * heatmap.
     */
    VIVID("Vivid Spectrum", new Spec(
            new Color(0x6BFF6B),
            new Color(0xFFD24A),
            new Color(0xFF3B3B),
            new Color(0x40D0FF),
            new Color(0x0C, 0x0E, 0x18),
            new Color(0x05, 0x06, 0x0C),
            new Color(255, 255, 255, 26),
            new Color(255, 255, 255, 90),
            new Color(255, 255, 255, 60),
            new Color(0xC8, 0xD0, 0xE0),
            new Color(0xE6, 0xEC, 0xF6),
            80,
            MaxHoldEffect.VALUE_FADE,
            new Color[] {
                    new Color(0x20, 0x40, 0xE0),   // blue        (noise floor)
                    new Color(0x20, 0xC8, 0xE0),   // cyan
                    new Color(0x30, 0xE0, 0x50),   // green
                    new Color(0xF0, 0xE0, 0x20),   // yellow
                    new Color(0xFF, 0x80, 0x10),   // orange
                    new Color(0xFF, 0x20, 0x20)    // red         (strong)
            },
            -88f, -62f)),

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
            null, -90f, -50f));

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
     * <p>{@code strengthColors} is optional (low -> high order). When it holds
     * at least two colours the chart paints peaks + realtime with a vertical
     * gradient spanning {@code strengthNoiseDbm} (noise floor) at the first
     * colour to {@code strengthStrongDbm} (loud signal) at the last colour;
     * intermediate colours are spread evenly between. When it is null/short the
     * renderer falls back to the flat {@code peaks} / {@code realtime} colours.
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
            Color[] strengthColors,
            float strengthNoiseDbm,
            float strengthStrongDbm) {

        public boolean hasStrengthGradient() {
            return strengthColors != null && strengthColors.length >= 2;
        }

        public javafx.scene.paint.Color peaksFx()    { return toFx(peaks); }
        public javafx.scene.paint.Color averageFx()  { return toFx(average); }
        public javafx.scene.paint.Color maxHoldFx()  { return toFx(maxHold); }
        public javafx.scene.paint.Color realtimeFx() { return toFx(realtime); }

        private static javafx.scene.paint.Color toFx(Color c) {
            return javafx.scene.paint.Color.rgb(c.getRed(), c.getGreen(), c.getBlue());
        }
    }
}
