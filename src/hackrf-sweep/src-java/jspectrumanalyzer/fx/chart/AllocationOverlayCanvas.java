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

        // Pre-compute integer-aligned pixel rects so adjacent bands don't
        // visually merge or leave 1px gaps from sub-pixel rounding (the old
        // double-precision rects looked like overlapping smears in dense
        // areas of the spectrum).
        List<DrawnBand> drawn = new ArrayList<>(bands.size());
        for (FrequencyBand band : bands) {
            double xStart = rfMHzToPixelX(band.getMHzStartIncl(), plan);
            double xEnd = rfMHzToPixelX(band.getMHzEndExcl(), plan);
            if (Double.isNaN(xStart) && Double.isNaN(xEnd)) continue;
            double clampedStart = clampToArea(xStart, true);
            double clampedEnd = clampToArea(xEnd, false);
            int sx = (int) Math.round(clampedStart);
            int ex = (int) Math.round(clampedEnd);
            if (ex - sx < 1) continue;
            drawn.add(new DrawnBand(band, sx, ex));
        }
        if (drawn.isEmpty()) return;

        Font font = Font.font("Dialog", FontWeight.NORMAL, FONT_SIZE);
        g.setFont(font);

        double bandY = dataArea.getY();
        double bandH = Math.min(BAND_HEIGHT, dataArea.getHeight() * 0.5d);

        // First pass: paint all band fills + borders. We do this before any
        // labels so labels never get covered by a later band's fill.
        for (DrawnBand d : drawn) {
            Color fill = ensureVisibleOnBlack(
                    parseColorOrDefault(d.band.getColor(), Color.GRAY));
            g.setFill(fill.deriveColor(0, 1, 1, FILL_OPACITY));
            g.fillRect(d.start, bandY, d.end - d.start, bandH);
            g.setStroke(Color.color(1, 1, 1, 0.55));
            g.setLineWidth(1);
            g.strokeRect(d.start, bandY, d.end - d.start, bandH);
        }

        // Second pass: labels. Coalesce runs of adjacent bands that share a
        // name (very common in EFIS data: e.g. Sweden has 12 consecutive
        // "Sjofartsradio" bands) so the label is drawn once across the
        // combined span instead of repeated 12 times in unreadable slivers.
        int i = 0;
        while (i < drawn.size()) {
            DrawnBand head = drawn.get(i);
            int j = i + 1;
            while (j < drawn.size()
                    && drawn.get(j).band.getName().equals(head.band.getName())
                    && drawn.get(j).start <= drawn.get(j - 1).end + 1) {
                j++;
            }
            DrawnBand tail = drawn.get(j - 1);
            DrawnBand merged = (j - i == 1)
                    ? head
                    : new DrawnBand(head.band, head.start, tail.end);
            drawBandLabel(g, font, merged, bandY, bandH);
            i = j;
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

    /**
     * Render the best-fitting label for a band. Strategy, in order:
     *   1. Word-wrap the band name into as many lines as the band height
     *      allows (also splits on '/' so multi-service names break naturally).
     *   2. If even one wrapped line of the name doesn't fit, fall back to a
     *      compact frequency tag (e.g. "868 MHz", "2.4 GHz") so the user
     *      always knows what the colored stripe represents.
     *   3. If neither name nor frequency tag fits, draw nothing.
     */
    private void drawBandLabel(GraphicsContext g, Font font, DrawnBand d,
                                double bandY, double bandH) {
        double w = d.end - d.start;
        if (w < 8) return;

        Color textColor = pickReadableTextColor(parseColorOrDefault(d.band.getColor(), Color.GRAY));
        g.setFill(textColor);

        double availW = w - TEXT_PADDING * 2;
        if (availW <= 0) return;
        double lineHeight = FONT_SIZE + 1;
        int maxLines = Math.max(1, (int) Math.floor((bandH - TEXT_PADDING) / lineHeight));

        List<String> lines = wrapWords(d.band.getName(), font, availW, maxLines);
        if (lines.isEmpty()) {
            String fallback = shortFreqLabel(d.band);
            if (textFits(fallback, font, availW)) {
                lines = java.util.Collections.singletonList(fallback);
            } else {
                return;
            }
        }

        double textX = d.start + TEXT_PADDING;
        for (int i = 0; i < lines.size(); i++) {
            g.fillText(lines.get(i), textX,
                    bandY + TEXT_PADDING + (i + 1) * lineHeight - 2);
        }
    }

    /**
     * Greedy word-wrap: pack as many whitespace-or-slash-separated tokens as
     * fit on each line, up to {@code maxLines}. Returns an empty list if not
     * even the first token (after ellipsis-truncation) fits.
     */
    private static List<String> wrapWords(String text, Font font,
                                          double maxWidth, int maxLines) {
        List<String> result = new ArrayList<>();
        if (text == null || text.isEmpty() || maxLines <= 0) return result;

        String[] words = text.split("\\s+|/");
        Text measurer = new Text();
        measurer.setFont(font);

        StringBuilder line = new StringBuilder();
        for (String word : words) {
            if (word.isEmpty()) continue;
            String tentative = line.length() == 0 ? word : line + " " + word;
            measurer.setText(tentative);
            if (measurer.getLayoutBounds().getWidth() <= maxWidth) {
                line.setLength(0);
                line.append(tentative);
                continue;
            }
            // Doesn't fit on the current line.
            if (line.length() > 0) {
                result.add(line.toString());
                line.setLength(0);
                if (result.size() >= maxLines) return result;
            }
            // Try the word on a fresh line.
            measurer.setText(word);
            if (measurer.getLayoutBounds().getWidth() <= maxWidth) {
                line.append(word);
            } else {
                String truncated = ellipsize(word, font, maxWidth);
                if (truncated == null) {
                    // Single word doesn't fit even after truncation. Return
                    // whatever we have so far - caller falls back to the
                    // frequency tag when the result is empty.
                    return result;
                }
                result.add(truncated);
                if (result.size() >= maxLines) return result;
            }
        }
        if (line.length() > 0 && result.size() < maxLines) {
            result.add(line.toString());
        }
        return result;
    }

    /**
     * Compact frequency tag for a band: shown when even one wrapped line of
     * the name doesn't fit. Picks kHz / MHz / GHz so the number stays short
     * (max 4-5 visible characters), which is what makes the difference
     * between "useless overlay sliver" and "I can read it".
     */
    private static String shortFreqLabel(FrequencyBand b) {
        double centerMHz = (b.getMHzStartIncl() + b.getMHzEndExcl()) / 2d;
        if (centerMHz < 1.0) {
            return String.format(java.util.Locale.ROOT, "%.0f kHz", centerMHz * 1000d);
        }
        if (centerMHz < 1000.0) {
            return String.format(java.util.Locale.ROOT, "%.0f MHz", centerMHz);
        }
        return String.format(java.util.Locale.ROOT, "%.2f GHz", centerMHz / 1000d);
    }

    private static boolean textFits(String s, Font font, double maxWidth) {
        Text m = new Text(s);
        m.setFont(font);
        return m.getLayoutBounds().getWidth() <= maxWidth;
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
