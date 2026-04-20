package jspectrumanalyzer.fx.chart;

import java.awt.geom.Rectangle2D;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import javafx.application.Platform;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.scene.text.Text;
import jspectrumanalyzer.core.FrequencyPlan;
import jspectrumanalyzer.fx.model.SettingsStore;
import jspectrumanalyzer.wifi.WifiAccessPoint;

/**
 * JavaFX overlay that marks visible Wi-Fi access points on the spectrum
 * chart with one thin vertical stick per AP plus a short horizontal
 * whisker showing the channel width at the AP's RSSI.
 *
 * <p>The previous gradient-box rendering carried two unwanted costs:
 * users found it visually noisy ("just lines and the name is enough"),
 * and on busy office bands it allocated a fresh {@code LinearGradient}
 * + a stroked rect per AP per redraw - measurable contributor to the
 * "Processing queue full" warnings the engine emits when the FX thread
 * cannot keep up with the SDR sweep rate. Lines + cached labels are
 * an order of magnitude cheaper.
 *
 * <h2>Multi-BSSID / VAP coalescing</h2>
 * Modern access points typically beacon 2-6 BSSIDs from a single
 * physical radio (main SSID + guest + IoT + hidden corporate, etc.).
 * Each virtual AP gets a BSSID derived from the radio's hardware MAC
 * by flipping a handful of bits - usually the locally-administered
 * bit of the first byte, or a nibble of the fourth byte. Treating
 * these as independent APs makes a single physical AP appear as
 * "bratt +2" when it really is one device with one known name.
 * Pass 1.5 therefore coalesces markers that share the same
 * {@code bondedCenterMhz} <em>and</em> have a BSSID Hamming distance
 * &lt;= {@link #VAP_BIT_DISTANCE} bits into a single logical marker,
 * picking the strongest non-hidden SSID as the display name (so the
 * label never collapses to "(hidden)" just because the hidden VAP is
 * a fraction of a dB stronger).
 *
 * <h2>Co-channel clustering</h2>
 * Even after VAP coalescing, a busy office band still sees several
 * <em>distinct</em> physical APs sharing the same 80 MHz block
 * (different vendors, different OUIs - cannot be merged by VAP
 * heuristics). Pass 2 groups any markers whose centre is within
 * {@link #CLUSTER_PIXEL_GAP} of each other and prints only the
 * <em>strongest</em> SSID in the cluster, suffixed with "{@code +N}"
 * when more APs share the column. The hover tooltip enumerates every
 * member - including all VAPs of every member - so the full identity
 * is one mouseover away.
 *
 * <h2>Plan-aware</h2>
 * Centre and width are mapped through {@link FrequencyPlan#rfMHzToLogicalMHz}
 * when a multi-segment plan is active so the markers stay aligned with
 * what the chart actually shows; APs whose centre falls in a multi-band
 * gap are skipped.
 */
public final class ApMarkerCanvas extends Canvas {

    /** Fallback width when an AP record reports an invalid bandwidth (<=0). */
    private static final int FALLBACK_CHANNEL_WIDTH_MHZ = 20;

    private static final double FONT_SIZE = 10d;
    private static final double LABEL_LINE_HEIGHT = FONT_SIZE + 2d;
    /** Horizontal gap to enforce between two labels on the same Y row. */
    private static final double LABEL_X_GAP = 4d;
    /** Y gap between the topmost label and the chart's top edge. */
    private static final double LABEL_TOP_MARGIN = 2d;
    /**
     * Stroke width for the vertical stick. 1.5 px reads as a clear
     * "this is a marker, not the trace" without bloating the chart.
     */
    private static final double STICK_WIDTH = 1.5d;
    /**
     * Minimum opacity floor for the stick + label so the user-controlled
     * {@code apMarkerOpacity} setting cannot fade markers to invisibility -
     * 0.35 leaves them readable on any chart background while still
     * letting the slider tune visual weight.
     */
    private static final double MIN_DRAW_ALPHA = 0.35d;
    /**
     * Two markers are folded into the same cluster when their centre
     * pixels are within this many pixels. 6 px is generous enough to
     * catch APs whose centres differ by &lt;1 MHz on a typical 1000 px
     * chart of the full 5 GHz band, but tight enough that a 20 MHz
     * channel away from its neighbour still reads as two columns.
     */
    private static final double CLUSTER_PIXEL_GAP = 6.0;
    /**
     * Maximum Hamming distance (in bits, over the full 48-bit BSSID)
     * for two beacons to count as VAPs of the same physical radio.
     * Empirically observed VAP patterns from consumer / enterprise gear:
     * <ul>
     *   <li>Locally-administered-bit flip in byte 0 (e.g. {@code 80:..}
     *       vs {@code 86:..}) -&gt; 1-3 bit deltas.</li>
     *   <li>Lower-nibble increments in byte 3 (e.g. {@code ..:9c:..}
     *       vs {@code ..:ac:..} vs {@code ..:bc:..}) -&gt; 1-3 bit
     *       deltas.</li>
     * </ul>
     * 4 bits comfortably covers all observed patterns. Two genuinely
     * different APs on the same channel will always differ in either
     * the OUI (24 bits, almost always) or in the lower three bytes
     * (typically 10+ bits), well above the threshold, so the heuristic
     * has no false-positive risk in practice.
     */
    private static final int VAP_BIT_DISTANCE = 4;
    /** Cap on how many SSIDs the hover tooltip enumerates. Anything past
     *  this gets a "+N more" footer to keep the tooltip box reasonable. */
    private static final int TOOLTIP_MAX_LINES = 8;

    private final SettingsStore settings;
    private Rectangle2D dataArea = new Rectangle2D.Double(0, 0, 0, 0);

    /** Latest AP snapshot pushed by the scan service (FX-thread only). */
    private List<WifiAccessPoint> aps = Collections.emptyList();

    /**
     * Hit-test boxes built during {@link #redraw()} so the tooltip layer
     * can map a mouse position back to the cluster of APs sharing that
     * column without recomputing pixel mappings. One entry per drawn
     * cluster (i.e. per visible chart column with at least one AP).
     */
    private final List<DrawnCluster> drawnAps = new ArrayList<>();

    /**
     * Last known mouse position in canvas coordinates, or {@code null} when
     * the cursor is not over the chart layer. Updates trigger a redraw so the
     * tooltip rectangle follows the cursor in real time.
     */
    private Double hoverX, hoverY;

    public ApMarkerCanvas(SettingsStore settings) {
        this.settings = settings;
        // Pass-through so the chart's drag-zoom and context-menu handlers keep
        // receiving every mouse event - markers are decorative.
        setMouseTransparent(true);

        Runnable repaint = () -> Platform.runLater(this::redraw);
        settings.isApMarkersVisible().addListener(repaint);
        settings.getApMarkerOpacity().addListener(repaint);
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

    /**
     * Push a fresh AP snapshot. Must be called on the FX thread (the
     * {@link jspectrumanalyzer.wifi.WifiScanService} listener already
     * marshals there in {@link jspectrumanalyzer.fx.MainWindow}).
     */
    public void setAccessPoints(List<WifiAccessPoint> snapshot) {
        this.aps = (snapshot == null) ? Collections.emptyList() : snapshot;
        redraw();
    }

    /**
     * Update the hover position used for the tooltip overlay. Pass
     * {@code null} for both coordinates when the cursor leaves the chart so
     * the tooltip vanishes. Coordinates are in this canvas's local space -
     * {@link jspectrumanalyzer.fx.MainWindow} translates from the StackPane
     * before calling.
     */
    public void setHoveredPoint(Double xLocal, Double yLocal) {
        // Skip noisy redraws when the cursor sits inside the same pixel.
        if (sameDouble(this.hoverX, xLocal) && sameDouble(this.hoverY, yLocal)) return;
        this.hoverX = xLocal;
        this.hoverY = yLocal;
        redraw();
    }

    private static boolean sameDouble(Double a, Double b) {
        if (a == null && b == null) return true;
        if (a == null || b == null) return false;
        return Math.abs(a - b) < 0.5;
    }

    private void redraw() {
        GraphicsContext g = getGraphicsContext2D();
        g.clearRect(0, 0, getWidth(), getHeight());
        drawnAps.clear();

        if (!settings.isApMarkersVisible().getValue()) return;
        if (aps.isEmpty()) return;
        if (dataArea.getWidth() < 4 || dataArea.getHeight() < 4) return;

        FrequencyPlan plan = settings.getEffectivePlan();
        if (plan == null) return;

        Font font = Font.font("Dialog", FontWeight.NORMAL, FONT_SIZE);
        g.setFont(font);

        final double yMinDbm = SpectrumChart.Y_MIN_DBM;
        final double yMaxDbm = SpectrumChart.Y_MAX_DBM;
        final double dbmSpan = yMaxDbm - yMinDbm;
        final double areaTop = dataArea.getY();
        final double areaBottom = dataArea.getY() + dataArea.getHeight();

        // Pass 1: project every AP onto the chart and collect the bits we
        // need for both the line draw and the label-collision pass. Doing
        // this in a single sweep keeps the per-AP work sequential (good
        // for branch prediction) and gives the collision code a stable
        // list to sort.
        List<Marker> markers = new ArrayList<>();
        for (WifiAccessPoint ap : aps) {
            WifiAccessPoint.Band band = ap.band();
            if (band == null) continue;

            int widthMhz = ap.bandwidthMhz() > 0
                    ? ap.bandwidthMhz()
                    : FALLBACK_CHANNEL_WIDTH_MHZ;
            // Bonded centre (parsed from VHT/HE IE) gives the actual RF
            // midpoint of the channel block. Falling back to the primary
            // would shift 80 / 160 MHz markers off the spectrum trace by
            // up to 30 / 70 MHz; the record already defaults bondedCenter
            // to primary when the IE was absent, so this is always safe.
            int centerMhz = ap.bondedCenterMhz();
            double startMhz = centerMhz - widthMhz / 2.0;
            double endMhz = centerMhz + widthMhz / 2.0;

            double xCentre = rfMHzToPixelX(centerMhz, plan);
            if (Double.isNaN(xCentre)) continue;
            if (xCentre < dataArea.getX() || xCentre > dataArea.getX() + dataArea.getWidth()) {
                continue;
            }

            double xStart = rfMHzToPixelX(startMhz, plan);
            double xEnd = rfMHzToPixelX(endMhz, plan);
            if (Double.isNaN(xStart)) xStart = xCentre;
            if (Double.isNaN(xEnd)) xEnd = xCentre;
            double clampedStart = clampToArea(xStart);
            double clampedEnd = clampToArea(xEnd);
            if (clampedEnd < clampedStart) {
                double tmp = clampedStart; clampedStart = clampedEnd; clampedEnd = tmp;
            }

            double rssi = clamp(ap.rssiDbm(), yMinDbm, yMaxDbm);
            double yTop = areaTop + (yMaxDbm - rssi) / dbmSpan * dataArea.getHeight();
            if (yTop > areaBottom) yTop = areaBottom;

            String ssid = ap.ssid().isEmpty() ? "(hidden)" : ap.ssid();

            markers.add(new Marker(ap, List.of(ap), band,
                    xCentre, clampedStart, clampedEnd, yTop, ssid));
        }

        // Pass 1.5: collapse Multi-BSSID virtual APs that come from the
        // same physical radio. A single AP advertising 4 SSIDs would
        // otherwise paint as 4 stacked labels ("bratt +3") even though
        // the user only cares about the one device. See class javadoc.
        markers = coalesceVaps(markers);

        // Pass 2: cluster markers that share (approximately) the same
        // X column. Without this, an 80 MHz channel hosting 10 BSSIDs
        // produces 10 stacked labels that just pile vertically.
        markers.sort(Comparator.comparingDouble(m -> m.xCentre));
        List<Cluster> clusters = clusterByPixelColumn(markers);

        // Pass 3: lay out labels with vertical anti-collision so two
        // adjacent clusters (e.g. ch 36 + ch 40 at narrow chart widths)
        // do not stamp on top of each other. Walk left-to-right so the
        // greedy "place against already-placed neighbours" search only
        // has to look backwards a few entries.
        double peakAlpha = clamp(settings.getApMarkerOpacity().getValue() / 100d,
                MIN_DRAW_ALPHA, 1.0);
        List<PlacedLabel> placed = new ArrayList<>();
        for (Cluster c : clusters) {
            Marker primary = c.primary();
            int extras = c.members().size() - 1;
            String labelText = extras > 0
                    ? primary.ssid + "  +" + extras
                    : primary.ssid;

            double textW = textWidth(labelText, font);
            double labelLeft  = primary.xCentre - textW / 2.0;
            double labelRight = primary.xCentre + textW / 2.0;
            // Initial Y baseline: just above the stick top so the label
            // floats over the RSSI mark, with a small gap so it doesn't
            // touch the whisker.
            double yBaseline = primary.yTop - 3;
            yBaseline = bumpToFreeRow(yBaseline, labelLeft, labelRight, placed);
            double labelTop = yBaseline - FONT_SIZE;
            boolean canDrawLabel = labelTop >= areaTop + LABEL_TOP_MARGIN;

            // Stick + whisker draw regardless of label availability so a
            // dropped label never makes the cluster itself invisible.
            drawStick(g, primary, peakAlpha);
            if (canDrawLabel) {
                drawClusterLabel(g, primary, labelText, extras, labelLeft, yBaseline, peakAlpha);
                placed.add(new PlacedLabel(labelLeft, labelRight, yBaseline));
            }
            // Hit-test box covers the cluster's stick area; tooltip
            // enumerates all members.
            drawnAps.add(new DrawnCluster(c.members(),
                    primary.clampedStart, primary.yTop,
                    Math.max(primary.clampedEnd - primary.clampedStart, STICK_WIDTH),
                    areaBottom - primary.yTop));
        }

        // Tooltip overlay last so it always sits on top of every marker.
        drawHoverTooltip(g, font);
    }

    /**
     * Group markers that look like virtual BSSIDs of one physical radio.
     * Two markers merge when they sit on the exact same bonded channel
     * <em>and</em> their BSSIDs are at most {@link #VAP_BIT_DISTANCE}
     * bits apart. The merged marker keeps the strongest member's
     * geometry (so the stick lands at the strongest signal), but its
     * display SSID prefers the strongest <em>non-hidden</em> name -
     * that way "bratt" wins over "(hidden)" even when the hidden VAP
     * happens to be a couple of dB stronger this scan.
     *
     * <p>Order of input is irrelevant; output is unsorted (the next
     * pass re-sorts by X). Members of every group, including the
     * primary, end up in {@link Marker#vaps} so the hover tooltip can
     * still reveal every BSSID the radio is beaconing.
     */
    private static List<Marker> coalesceVaps(List<Marker> markers) {
        List<Marker> out = new ArrayList<>(markers.size());
        boolean[] consumed = new boolean[markers.size()];
        for (int i = 0; i < markers.size(); i++) {
            if (consumed[i]) continue;
            Marker seed = markers.get(i);
            List<Marker> group = new ArrayList<>(2);
            group.add(seed);
            consumed[i] = true;
            long seedBssid = parseBssid(seed.ap.bssid());
            int seedCentre = seed.ap.bondedCenterMhz();
            for (int j = i + 1; j < markers.size(); j++) {
                if (consumed[j]) continue;
                Marker other = markers.get(j);
                if (other.ap.bondedCenterMhz() != seedCentre) continue;
                long otherBssid = parseBssid(other.ap.bssid());
                if (seedBssid < 0 || otherBssid < 0) continue;
                if (Long.bitCount(seedBssid ^ otherBssid) <= VAP_BIT_DISTANCE) {
                    group.add(other);
                    consumed[j] = true;
                }
            }
            out.add(mergeVapGroup(group));
        }
        return out;
    }

    /**
     * Build the single representative marker for a VAP group:
     * <ul>
     *   <li>Geometry (x, whisker, y) comes from the strongest RSSI
     *       member - ensures the stick height tracks the actual peak
     *       the trace is showing.</li>
     *   <li>Display SSID prefers the strongest non-hidden name; falls
     *       back to "(hidden)" only when every VAP is hidden.</li>
     *   <li>{@link Marker#vaps} keeps every original AP (sorted by
     *       RSSI desc) so the tooltip can list them all.</li>
     * </ul>
     */
    private static Marker mergeVapGroup(List<Marker> group) {
        if (group.size() == 1) return group.get(0);
        group.sort((a, b) -> Integer.compare(b.ap.rssiDbm(), a.ap.rssiDbm()));
        Marker primary = group.get(0);
        String displaySsid = primary.ssid;
        if ("(hidden)".equals(displaySsid)) {
            for (Marker m : group) {
                if (!"(hidden)".equals(m.ssid)) { displaySsid = m.ssid; break; }
            }
        }
        List<WifiAccessPoint> vaps = new ArrayList<>(group.size());
        for (Marker m : group) vaps.add(m.ap);
        return new Marker(primary.ap, vaps, primary.band,
                primary.xCentre, primary.clampedStart, primary.clampedEnd,
                primary.yTop, displaySsid);
    }

    /**
     * Parse a colon-separated MAC into a 48-bit unsigned long. Returns
     * {@code -1} on any malformed input - callers must treat that as
     * "do not group". We are intentionally lenient (any non-hex byte
     * skips the AP) rather than throwing because a single odd record
     * from the platform stack should not break the whole overlay.
     */
    private static long parseBssid(String bssid) {
        if (bssid == null) return -1L;
        String[] parts = bssid.split(":");
        if (parts.length != 6) return -1L;
        long v = 0L;
        for (String p : parts) {
            if (p.length() != 2) return -1L;
            try {
                v = (v << 8) | (Integer.parseInt(p, 16) & 0xFF);
            } catch (NumberFormatException ex) {
                return -1L;
            }
        }
        return v;
    }

    /**
     * Greedy left-to-right cluster builder. A marker joins the current
     * cluster when its centre pixel is within {@link #CLUSTER_PIXEL_GAP}
     * of the cluster's primary; otherwise a new cluster opens. After
     * grouping each cluster's members are sorted by RSSI descending so
     * {@code primary()} returns the strongest AP - that one's SSID is
     * what the label shows, and the weaker ones turn into the "+N" tail.
     */
    private static List<Cluster> clusterByPixelColumn(List<Marker> sortedByX) {
        List<Cluster> out = new ArrayList<>();
        for (Marker m : sortedByX) {
            Cluster last = out.isEmpty() ? null : out.get(out.size() - 1);
            if (last != null
                    && Math.abs(m.xCentre - last.members().get(0).xCentre) <= CLUSTER_PIXEL_GAP) {
                last.members().add(m);
            } else {
                List<Marker> mem = new ArrayList<>();
                mem.add(m);
                out.add(new Cluster(mem));
            }
        }
        for (Cluster c : out) {
            c.members().sort((a, b) -> Integer.compare(b.ap.rssiDbm(), a.ap.rssiDbm()));
        }
        return out;
    }

    /**
     * Find the lowest Y baseline (largest numerical value, since the FX
     * Y axis grows downward) at or above {@code seedY} where the supplied
     * label horizontal extent does not overlap any previously placed
     * label. Walks upward by {@link #LABEL_LINE_HEIGHT} per attempt.
     */
    private static double bumpToFreeRow(double seedY, double labelLeft, double labelRight,
                                        List<PlacedLabel> placed) {
        double y = seedY;
        boolean moved;
        do {
            moved = false;
            for (PlacedLabel p : placed) {
                // Same "row" if the baselines are within one line height -
                // anything further apart cannot collide vertically since
                // labels are exactly FONT_SIZE tall.
                if (Math.abs(p.yBaseline - y) >= LABEL_LINE_HEIGHT) continue;
                boolean horizontalOverlap =
                        labelRight > p.left - LABEL_X_GAP
                                && labelLeft < p.right + LABEL_X_GAP;
                if (horizontalOverlap) {
                    y = p.yBaseline - LABEL_LINE_HEIGHT;
                    moved = true;
                    // Restart the scan: bumping up could now collide with
                    // a different placed label one row higher.
                    break;
                }
            }
        } while (moved);
        return y;
    }

    private void drawStick(GraphicsContext g, Marker m, double peakAlpha) {
        Color base = colorForBand(m.band);
        Color stickColor = base.deriveColor(0, 1, 1, peakAlpha);
        g.setStroke(stickColor);
        g.setLineWidth(STICK_WIDTH);
        // Vertical stick from the RSSI line down to the chart baseline.
        double areaBottom = dataArea.getY() + dataArea.getHeight();
        g.strokeLine(m.xCentre, m.yTop, m.xCentre, areaBottom);
        // Horizontal whisker at the RSSI line spanning the channel
        // width. Skipped when the channel width clamps to a single
        // pixel (e.g. an out-of-range edge); the stick alone is enough.
        if (m.clampedEnd - m.clampedStart >= 2) {
            g.setLineWidth(1);
            g.strokeLine(m.clampedStart, m.yTop, m.clampedEnd, m.yTop);
        }
    }

    private void drawClusterLabel(GraphicsContext g, Marker primary, String labelText,
                                  int extras, double labelLeft, double yBaseline,
                                  double peakAlpha) {
        // Tiny shadow so a white label stays readable against the noise
        // floor band of the spectrum (which is also near-white). Two
        // fillText calls is cheaper than a JFX effect node and still
        // looks fine on the dark theme.
        g.setFill(Color.color(0, 0, 0, Math.min(0.85, peakAlpha + 0.3)));
        g.fillText(labelText, labelLeft + 1, yBaseline + 1);
        // Two-tone draw when the cluster has overflow APs: the primary
        // SSID stays white (high contrast) and the "+N" tail picks up
        // the band tint so the eye reads it as "this is meta info, not
        // part of the SSID name".
        if (extras > 0) {
            String ssid = primary.ssid;
            String tail = labelText.substring(ssid.length()); // "  +N"
            g.setFill(Color.color(1, 1, 1, peakAlpha));
            g.fillText(ssid, labelLeft, yBaseline);
            double ssidW = textWidth(ssid, g.getFont());
            g.setFill(colorForBand(primary.band).deriveColor(0, 1, 1.2, peakAlpha));
            g.fillText(tail, labelLeft + ssidW, yBaseline);
        } else {
            g.setFill(Color.color(1, 1, 1, peakAlpha));
            g.fillText(labelText, labelLeft, yBaseline);
        }
    }

    /**
     * Render a small tooltip box near the cursor when it is hovering over a
     * cluster. The first line repeats the cluster's primary AP (the one
     * whose SSID is on the chart); subsequent lines enumerate the other
     * cluster members so the user sees the full identity of every BSSID
     * sharing this column without leaving the chart.
     */
    private void drawHoverTooltip(GraphicsContext g, Font font) {
        if (hoverX == null || hoverY == null) return;
        DrawnCluster hit = findClusterAt(hoverX, hoverY);
        if (hit == null) return;

        Font tooltipFont = Font.font(font.getFamily(), FontWeight.NORMAL, 11);
        g.setFont(tooltipFont);

        // Build the line list: header + one row per AP, capped so a
        // cluster of 30 BSSIDs does not overflow the chart.
        List<TooltipLine> lines = new ArrayList<>();
        Marker primary = hit.members.get(0);
        String channelLabel = String.format("ch %d  %s  %d MHz",
                primary.ap.channel(), primary.ap.bandLabel(), primary.ap.bandwidthMhz());
        Color bandTint = colorForBand(primary.ap.band()).deriveColor(0, 1, 1.2, 1);
        lines.add(new TooltipLine(channelLabel, bandTint, true));

        // Flatten cluster members -> one tooltip row per actual BSSID.
        // After VAP coalescing, members.size() can be smaller than the
        // total beacon count, but the user still wants to see every
        // BSSID the radio(s) are advertising. Iterate cluster members
        // in their existing RSSI order, then each member's VAPs in
        // their internal RSSI order.
        int totalRows = 0;
        for (Marker m : hit.members) totalRows += m.vaps.size();
        int shown = 0;
        outer:
        for (Marker m : hit.members) {
            for (WifiAccessPoint vap : m.vaps) {
                if (shown >= TOOLTIP_MAX_LINES) break outer;
                String displaySsid = vap.ssid().isEmpty() ? "(hidden)" : vap.ssid();
                String row = String.format("%-3d dBm  %s  %s",
                        vap.rssiDbm(),
                        padOrTruncate(displaySsid, 22),
                        vap.bssid());
                lines.add(new TooltipLine(row, Color.color(0.95, 0.95, 0.95), false));
                shown++;
            }
        }
        int hiddenCount = totalRows - shown;
        if (hiddenCount > 0) {
            lines.add(new TooltipLine("+" + hiddenCount + " more APs", Color.color(0.65, 0.65, 0.68), false));
        }

        double maxLineW = 0;
        for (TooltipLine ln : lines) {
            maxLineW = Math.max(maxLineW, textWidth(ln.text, tooltipFont));
        }

        double padding = 6;
        double lineH = 14;
        double boxW = maxLineW + padding * 2;
        double boxH = lineH * lines.size() + padding * 2;

        // Anchor the tooltip top-left at +12/+12 from the cursor unless that
        // would push it off the canvas - then mirror to the other side.
        double bx = hoverX + 12;
        double by = hoverY + 12;
        if (bx + boxW > getWidth()) bx = hoverX - boxW - 12;
        if (by + boxH > getHeight()) by = hoverY - boxH - 12;
        if (bx < 0) bx = 0;
        if (by < 0) by = 0;

        g.setFill(Color.color(0.07, 0.07, 0.10, 0.92));
        g.fillRoundRect(bx, by, boxW, boxH, 6, 6);
        g.setStroke(Color.color(1, 1, 1, 0.18));
        g.setLineWidth(1);
        g.strokeRoundRect(bx, by, boxW, boxH, 6, 6);

        double tx = bx + padding;
        double ty = by + padding + lineH - 3;
        for (TooltipLine ln : lines) {
            g.setFill(ln.color);
            g.fillText(ln.text, tx, ty);
            ty += lineH;
        }
    }

    /** Right-pad with spaces or truncate with an ellipsis so the tooltip
     *  rows line up vertically without per-row width measurements. */
    private static String padOrTruncate(String s, int width) {
        if (s.length() > width) return s.substring(0, width - 1) + "\u2026";
        if (s.length() == width) return s;
        StringBuilder sb = new StringBuilder(width);
        sb.append(s);
        while (sb.length() < width) sb.append(' ');
        return sb.toString();
    }

    /**
     * Hit-test the cursor against the cluster boxes recorded in the most
     * recent {@link #redraw}. Returns the topmost matching cluster or
     * {@code null} when the cursor is over empty chart area.
     *
     * <p>Iterates in reverse draw order so when cluster boxes overlap
     * (rare on the 20 MHz grid but possible at narrow chart widths) the
     * most recently drawn one wins, matching the "topmost = on top"
     * expectation.
     */
    private DrawnCluster findClusterAt(double x, double y) {
        for (int i = drawnAps.size() - 1; i >= 0; i--) {
            DrawnCluster d = drawnAps.get(i);
            if (x >= d.x && x <= d.x + d.w && y >= d.y && y <= d.y + d.h) {
                return d;
            }
        }
        return null;
    }

    /** Pick the per-band tint for AP markers. Mirrors the channel-grid CSV
     *  colours so a marker visually echoes its channel-grid stripe. */
    private static Color colorForBand(WifiAccessPoint.Band band) {
        return switch (band) {
            case GHZ_24 -> Color.web("#ff9f40"); // orange
            case GHZ_5  -> Color.web("#5fa8ff"); // blue
            case GHZ_6  -> Color.web("#7fdc7f"); // green
        };
    }

    private double rfMHzToPixelX(double rfMHz, FrequencyPlan plan) {
        int shift = settings.getFreqShift().getValue();
        if (plan.isMultiSegment()) {
            double logical = plan.rfMHzToLogicalMHz(rfMHz);
            if (Double.isNaN(logical)) return Double.NaN;
            double frac = logical / (double) plan.totalLogicalSpanMHz();
            return dataArea.getX() + frac * dataArea.getWidth();
        }
        double start = plan.firstStartMHz() + shift;
        double end = plan.lastEndMHz() + shift;
        if (end <= start) return Double.NaN;
        double frac = (rfMHz + shift - start) / (end - start);
        return dataArea.getX() + frac * dataArea.getWidth();
    }

    private double clampToArea(double x) {
        double lo = dataArea.getX();
        double hi = dataArea.getX() + dataArea.getWidth();
        if (Double.isNaN(x)) return lo;
        if (x < lo) return lo;
        if (x > hi) return hi;
        return x;
    }

    private static double clamp(double v, double lo, double hi) {
        if (v < lo) return lo;
        if (v > hi) return hi;
        return v;
    }

    private static double textWidth(String s, Font font) {
        Text t = new Text(s);
        t.setFont(font);
        return t.getLayoutBounds().getWidth();
    }

    /**
     * Per-AP geometry computed in pass 1, consumed by passes 2/3.
     * After {@link #coalesceVaps} runs, {@code ap} is the strongest
     * member (used for stick / whisker placement and as the tooltip
     * "primary"), {@code ssid} is the chosen display name (strongest
     * non-hidden where possible), and {@code vaps} carries every
     * BSSID the radio is beaconing on this channel - including the
     * primary - so the tooltip can list them all.
     */
    private record Marker(WifiAccessPoint ap, List<WifiAccessPoint> vaps,
                          WifiAccessPoint.Band band,
                          double xCentre, double clampedStart, double clampedEnd,
                          double yTop, String ssid) {}

    /**
     * One cluster = one group of co-channel APs that the redraw collapses
     * to a single label. Mutable list so {@link #clusterByPixelColumn}
     * can append members while walking left-to-right.
     */
    private record Cluster(List<Marker> members) {
        Marker primary() { return members.get(0); }
    }

    /** Already-placed label box used by the anti-collision walk. */
    private record PlacedLabel(double left, double right, double yBaseline) {}

    /** Per-cluster hit-test record built during {@link #redraw}. The
     *  member list is shared with the cluster so the tooltip can list
     *  every AP in the column without re-grouping. */
    private record DrawnCluster(List<Marker> members,
                                double x, double y, double w, double h) {}

    /** One row in the hover tooltip. Header rows render in band tint;
     *  data rows in white; "+N more" footer in dim grey. */
    private record TooltipLine(String text, Color color, boolean isHeader) {}
}
