package jspectrumanalyzer.ui;

import java.awt.Color;
import java.util.function.Supplier;

/**
 * Catalogue of palettes available for the waterfall display.
 *
 * <p>Each enum constant carries:
 * <ul>
 *   <li>a human-readable {@code displayName} that appears in the Display tab
 *       combo box (and round-trips through {@link #toString()} so the FX
 *       {@code ComboBox} renders it without a custom cell factory);</li>
 *   <li>a {@link Supplier}{@code <ColorPalette>} that lazily instantiates the
 *       backing palette - small palettes (Grayscale) are cheap to recreate
 *       but the legacy {@link HotIronBluePalette} parses a 200+ row string
 *       at construction, so we avoid building palettes the user never picks.</li>
 * </ul>
 *
 * <p>Keep order stable: it determines the order in the UI combo. New palettes
 * should be appended.
 */
public enum WaterfallPalette {

    /** Original palette (deep blue → white → yellow → red). Default. */
    HOT_IRON_BLUE("Hot Iron Blue", HotIronBluePalette::new),

    /** Matplotlib "inferno" - black → purple → red → orange → yellow.
     *  Strong perceptual contrast on bright signals against a dark background. */
    INFERNO("Inferno", () -> new GradientPalette(
            new GradientPalette.Stop(0.00, new Color(0, 0, 4)),
            new GradientPalette.Stop(0.25, new Color(87, 16, 110)),
            new GradientPalette.Stop(0.50, new Color(188, 55, 84)),
            new GradientPalette.Stop(0.75, new Color(249, 142, 9)),
            new GradientPalette.Stop(1.00, new Color(252, 255, 164)))),

    /** Matplotlib "viridis" - dark purple → blue → teal → green → yellow.
     *  Perceptually uniform; equal value steps look like equal colour steps,
     *  which is the right default for "I want to read a number off a colour". */
    VIRIDIS("Viridis", () -> new GradientPalette(
            new GradientPalette.Stop(0.00, new Color(68, 1, 84)),
            new GradientPalette.Stop(0.25, new Color(59, 82, 139)),
            new GradientPalette.Stop(0.50, new Color(33, 144, 141)),
            new GradientPalette.Stop(0.75, new Color(94, 201, 98)),
            new GradientPalette.Stop(1.00, new Color(253, 231, 37)))),

    /** Black → white. Cleanest for screenshots, prints well in monochrome. */
    GRAYSCALE("Grayscale", () -> new GradientPalette(
            new GradientPalette.Stop(0.00, Color.BLACK),
            new GradientPalette.Stop(1.00, Color.WHITE)));

    private final String displayName;
    private final Supplier<ColorPalette> factory;

    WaterfallPalette(String displayName, Supplier<ColorPalette> factory) {
        this.displayName = displayName;
        this.factory = factory;
    }

    public ColorPalette create() {
        return factory.get();
    }

    @Override
    public String toString() {
        return displayName;
    }
}
