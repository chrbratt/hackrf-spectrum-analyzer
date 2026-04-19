package jspectrumanalyzer.ui;

import java.awt.Color;
import java.util.Arrays;

/**
 * Generic {@link ColorPalette} backed by a small number of colour stops that
 * get linearly interpolated and baked into a fixed-size lookup table at
 * construction time.
 *
 * <p>The lookup table makes the per-pixel inner loop in
 * {@code WaterfallCanvas#paint()} a single array indexing instead of a binary
 * search across stops, while the colour-stop API keeps each palette
 * declaration to ~10 lines instead of the 256 hand-typed RGB triplets
 * {@code HotIronBluePalette} ships today.
 *
 * <p>Stops must be ordered by ascending {@link Stop#position} in [0, 1]; the
 * first stop's position should be 0 and the last's 1 to fully cover the
 * normalised range. Outside [0, 1] the table clamps to the endpoints.
 */
public final class GradientPalette implements ColorPalette {

    /**
     * One control point of the palette. {@code position} is normalised to
     * [0, 1] and indicates where on the value range this colour is at full
     * weight; values between two stops are a linear RGB interpolation.
     */
    public record Stop(double position, Color color) {}

    private static final int LUT_SIZE = 256;

    private final Color[] lut;

    public GradientPalette(Stop... stops) {
        if (stops == null || stops.length < 2) {
            throw new IllegalArgumentException("GradientPalette needs at least 2 stops");
        }
        // Defensive copy + sort so callers don't have to worry about ordering.
        Stop[] sorted = stops.clone();
        Arrays.sort(sorted, (a, b) -> Double.compare(a.position, b.position));
        this.lut = bake(sorted, LUT_SIZE);
    }

    private static Color[] bake(Stop[] stops, int size) {
        Color[] out = new Color[size];
        for (int i = 0; i < size; i++) {
            double t = (double) i / (size - 1);
            out[i] = sampleStops(stops, t);
        }
        return out;
    }

    private static Color sampleStops(Stop[] stops, double t) {
        if (t <= stops[0].position) return stops[0].color;
        if (t >= stops[stops.length - 1].position) return stops[stops.length - 1].color;
        // Linear scan is fine - every palette has < 10 stops.
        for (int i = 0; i < stops.length - 1; i++) {
            Stop a = stops[i];
            Stop b = stops[i + 1];
            if (t >= a.position && t <= b.position) {
                double span = b.position - a.position;
                double k = span <= 0 ? 0 : (t - a.position) / span;
                return lerp(a.color, b.color, k);
            }
        }
        return stops[stops.length - 1].color;
    }

    private static Color lerp(Color a, Color b, double k) {
        int r = (int) Math.round(a.getRed()   + (b.getRed()   - a.getRed())   * k);
        int g = (int) Math.round(a.getGreen() + (b.getGreen() - a.getGreen()) * k);
        int bl = (int) Math.round(a.getBlue() + (b.getBlue()  - a.getBlue())  * k);
        return new Color(clamp(r), clamp(g), clamp(bl));
    }

    private static int clamp(int v) {
        return v < 0 ? 0 : (v > 255 ? 255 : v);
    }

    @Override
    public Color getColor(int i) {
        if (i < 0) i = 0;
        if (i >= lut.length) i = lut.length - 1;
        return lut[i];
    }

    @Override
    public Color getColorNormalized(double value) {
        int index = (int) (lut.length * value);
        if (index < 0) index = 0;
        if (index >= lut.length) index = lut.length - 1;
        return lut[index];
    }

    @Override
    public int size() {
        return lut.length;
    }
}
