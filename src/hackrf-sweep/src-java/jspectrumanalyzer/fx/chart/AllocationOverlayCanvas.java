package jspectrumanalyzer.fx.chart;

import java.awt.geom.Rectangle2D;
import java.util.ArrayList;
import java.util.List;

import javafx.application.Platform;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.scene.text.Text;
import jspectrumanalyzer.core.FrequencyAllocationTable;
import jspectrumanalyzer.core.FrequencyBand;
import jspectrumanalyzer.core.FrequencyPlan;
import jspectrumanalyzer.fx.model.SettingsStore;

/**
 * Translucent JavaFX overlay rendered on top of the spectrum plot. Paints the
 * coloured frequency-allocation bands (e.g. "GSM900 Telekom downlink",
 * "Wi-Fi 2.4") above the FFT trace using the table the user picked in the
 * Display tab.
 *
 * <p>Plan-aware: maps each band's RF Hz endpoints through
 * {@link FrequencyPlan#rfMHzToLogicalMHz(double)} when a multi-segment plan
 * is active, so bands inside a stitched window land in the right place and
 * bands that fall in a gap are clipped out cleanly.
 *
 * <p>Lives in a {@code StackPane} on top of the {@code ChartViewer}; sized
 * to the chart's full pixel area but only paints inside the data area
 * reported by {@link SpectrumChart#setDataAreaListener}. Mouse events pass
 * straight through to the chart so zoom / context menu still work.
 */
public final class AllocationOverlayCanvas extends Canvas {

    private static final double FONT_SIZE = 11d;
    private static final double BAND_HEIGHT = 36d;
    private static final double FILL_OPACITY = 0.55d;
    private static final double TEXT_PADDING = 4d;

    private final SettingsStore settings;

    private Rectangle2D dataArea = new Rectangle2D.Double(0, 0, 0, 0);

    public AllocationOverlayCanvas(SettingsStore settings) {
        this.settings = settings;
        // Pass-through: the user still wants to drag-zoom on the chart even
        // when the overlay is on. Without this, every click on a band would
        // be swallowed by the overlay.
        setMouseTransparent(true);

        Runnable repaint = () -> Platform.runLater(this::redraw);
        settings.isFrequencyAllocationVisible().addListener(repaint);
        settings.getFrequencyAllocationTable().addListener(repaint);
        settings.getFrequency().addListener(repaint);
        settings.getFrequencyPlan().addListener(repaint);
        settings.getFreqShift().addListener(repaint);

        widthProperty().addListener((obs, o, n) -> redraw());
        heightProperty().addListener((obs, o, n) -> redraw());
    }

    /** Called from {@link SpectrumChart#setDataAreaListener} on the FX thread. */
    public void onDataAreaChanged(Rectangle2D area) {
        if (area == null) return;
        this.dataArea = area;
        redraw();
    }

    private void redraw() {
        GraphicsContext g = getGraphicsContext2D();
        g.clearRect(0, 0, getWidth(), getHeight());

        if (!settings.isFrequencyAllocationVisible().getValue()) return;
        FrequencyAllocationTable table = settings.getFrequencyAllocationTable().getValue();
        if (table == null) return;
        if (dataArea.getWidth() < 4 || dataArea.getHeight() < 4) return;

        FrequencyPlan plan = settings.getEffectivePlan();
        long startHz = plan.firstStartMHz() * 1_000_000L;
        long endHz = plan.lastEndMHz() * 1_000_000L;
        List<FrequencyBand> bands = table.getFrequencyBands(startHz, endHz);
        if (bands.isEmpty()) return;

        // Pre-compute the chart-relative pixel rects so we can decide which
        // bands have enough horizontal room to fit a label without overdraw.
        List<DrawnBand> drawn = new ArrayList<>(bands.size());
        for (FrequencyBand band : bands) {
            double xStart = rfMHzToPixelX(band.getMHzStartIncl(), plan);
            double xEnd = rfMHzToPixelX(band.getMHzEndExcl(), plan);
            if (Double.isNaN(xStart) && Double.isNaN(xEnd)) continue;
            // Clamp to plot area; bands that overflow on either side just get cut.
            double clampedStart = clampToArea(xStart, true);
            double clampedEnd = clampToArea(xEnd, false);
            if (clampedEnd - clampedStart < 1) continue;
            drawn.add(new DrawnBand(band, clampedStart, clampedEnd));
        }
        if (drawn.isEmpty()) return;

        Font font = Font.font("Dialog", FontWeight.NORMAL, FONT_SIZE);
        g.setFont(font);

        double bandY = dataArea.getY();
        double bandH = Math.min(BAND_HEIGHT, dataArea.getHeight() * 0.5d);

        for (DrawnBand d : drawn) {
            Color fill = ensureVisibleOnBlack(
                    parseColorOrDefault(d.band.getColor(), Color.GRAY));
            g.setFill(fill.deriveColor(0, 1, 1, FILL_OPACITY));
            g.fillRect(d.start, bandY, d.end - d.start, bandH);
            // Border drawn in white at low alpha so band edges are visible
            // even on a black chart background, regardless of fill colour
            // (the previous black-on-black border was effectively invisible
            // and tables like "Europe" - all bands #444444 - looked empty).
            g.setStroke(Color.color(1, 1, 1, 0.55));
            g.setLineWidth(1);
            g.strokeRect(d.start, bandY, d.end - d.start, bandH);

            drawBandLabel(g, font, d, bandY, bandH);
        }
    }

    /**
     * Lift very dark band colours toward mid-grey so the fill is visible on
     * the black chart background. Many CSV tables (e.g. Europe ECA) use a
     * single dark hex per band; without this the overlay would look like a
     * uniform black smear and users would think nothing was drawn.
     */
    private static Color ensureVisibleOnBlack(Color c) {
        double luminance = 0.299 * c.getRed() + 0.587 * c.getGreen() + 0.114 * c.getBlue();
        if (luminance >= 0.25) return c;
        // Blend toward white until luminance reaches ~0.4 - keeps the hue
        // recognisable but pushes the colour into a visible range.
        double mix = (0.4 - luminance) / (1.0 - luminance);
        return c.interpolate(Color.WHITE, Math.min(1.0, Math.max(0.0, mix)));
    }

    private void drawBandLabel(GraphicsContext g, Font font, DrawnBand d,
                                double bandY, double bandH) {
        double w = d.end - d.start;
        if (w < 12) return;
        String[] lines = d.band.getName().split("/");
        Color textColor = pickReadableTextColor(parseColorOrDefault(d.band.getColor(), Color.GRAY));
        g.setFill(textColor);

        double textX = d.start + TEXT_PADDING;
        double lineHeight = FONT_SIZE + 1;
        double maxLines = Math.max(1, Math.floor((bandH - TEXT_PADDING) / lineHeight));
        for (int i = 0; i < lines.length && i < maxLines; i++) {
            String trimmed = ellipsize(lines[i], font, w - TEXT_PADDING * 2);
            if (trimmed == null) return;
            g.fillText(trimmed, textX, bandY + TEXT_PADDING + (i + 1) * lineHeight - 2);
        }
    }

    private double rfMHzToPixelX(double rfMHz, FrequencyPlan plan) {
        int shift = settings.getFreqShift().getValue();
        if (plan.isMultiSegment()) {
            double logical = plan.rfMHzToLogicalMHz(rfMHz);
            if (Double.isNaN(logical)) return Double.NaN;
            double frac = logical / (double) plan.totalLogicalSpanMHz();
            return dataArea.getX() + frac * dataArea.getWidth();
        }
        // Single segment: chart axis range is [start+shift, end+shift].
        double start = plan.firstStartMHz() + shift;
        double end = plan.lastEndMHz() + shift;
        double frac = (rfMHz + shift - start) / (end - start);
        return dataArea.getX() + frac * dataArea.getWidth();
    }

    private double clampToArea(double x, boolean leftSide) {
        double lo = dataArea.getX();
        double hi = dataArea.getX() + dataArea.getWidth();
        if (Double.isNaN(x)) return leftSide ? hi : lo; // collapse out-of-range to zero width
        if (x < lo) return lo;
        if (x > hi) return hi;
        return x;
    }

    private static Color parseColorOrDefault(String hex, Color fallback) {
        if (hex == null || hex.isEmpty()) return fallback;
        try {
            return Color.web(hex.trim());
        } catch (Exception e) {
            return fallback;
        }
    }

    /**
     * Pick black or white based on the band's perceived brightness so labels
     * stay readable against any band colour.
     */
    private static Color pickReadableTextColor(Color bg) {
        double luminance = 0.299 * bg.getRed() + 0.587 * bg.getGreen() + 0.114 * bg.getBlue();
        return luminance > 0.55 ? Color.BLACK : Color.WHITE;
    }

    /** Cheap ellipsize using a {@link Text} node for measurement. */
    private static String ellipsize(String text, Font font, double maxWidth) {
        if (maxWidth <= 0) return null;
        Text measurer = new Text(text);
        measurer.setFont(font);
        if (measurer.getLayoutBounds().getWidth() <= maxWidth) return text;
        // Drop last char until it fits or there's no room for "...".
        StringBuilder sb = new StringBuilder(text);
        while (sb.length() > 1) {
            sb.deleteCharAt(sb.length() - 1);
            measurer.setText(sb + "\u2026");
            if (measurer.getLayoutBounds().getWidth() <= maxWidth) {
                return sb + "\u2026";
            }
        }
        return null;
    }

    private static final class DrawnBand {
        final FrequencyBand band;
        final double start;
        final double end;

        DrawnBand(FrequencyBand band, double start, double end) {
            this.band = band;
            this.start = start;
            this.end = end;
        }
    }
}
