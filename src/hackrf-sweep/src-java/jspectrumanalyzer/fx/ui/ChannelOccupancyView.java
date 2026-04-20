package jspectrumanalyzer.fx.ui;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javafx.application.Platform;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import jspectrumanalyzer.core.FrequencyPlan;
import jspectrumanalyzer.core.FrequencyRange;
import jspectrumanalyzer.fx.model.SettingsStore;
import jspectrumanalyzer.wifi.ChannelInterferenceService;
import jspectrumanalyzer.wifi.ChannelOccupancyService;
import jspectrumanalyzer.wifi.WifiAccessPoint;
import jspectrumanalyzer.wifi.WifiChannelCatalog;

/**
 * Horizontal bar chart that visualises per-Wi-Fi-channel duty cycle (%).
 * One row per channel, filtered to the channels that the SDR is currently
 * sweeping (so the user only ever sees what they actually have data for).
 *
 * <p>Each row shows: channel label on the left, a coloured bar whose width
 * encodes the rolling occupancy percentage, the percent value at the right
 * edge of the bar, and the latest channel-power dBm reading at the very
 * right. Channels with no readings yet render an empty rail with a "no
 * data" hint so the user knows the row is intentional rather than missing.
 *
 * <p>Sized dynamically: the canvas height grows with the visible row count
 * and the parent layout's preferred height tracks it so the
 * {@code WifiWindow} scroller never traps the bars in a tiny inset.
 */
public final class ChannelOccupancyView extends Canvas {

    private static final double ROW_HEIGHT = 18d;
    private static final double ROW_PADDING = 4d;
    private static final double LABEL_WIDTH = 110d;
    /** Right-edge slot for occupancy% / dBm / co/adj counts. */
    private static final double VALUE_WIDTH = 170d;
    private static final double SIDE_PADDING = 8d;

    private static final Color BG = Color.web("#16161a");
    private static final Color GRID = Color.web("#2a2a30");
    private static final Color LABEL = Color.web("#e0e0e0");
    private static final Color VALUE = Color.web("#cccccc");
    private static final Color SECONDARY = Color.web("#888");
    private static final Color CO_CHANNEL = Color.web("#ffb84d");
    private static final Color ADJ_CHANNEL = Color.web("#9b8cff");

    private final SettingsStore settings;

    private List<ChannelOccupancyService.ChannelStat> stats = List.of();
    /**
     * Latest interference counts indexed by channel number. Updated
     * independently from the occupancy snapshot so a stale Wi-Fi snapshot
     * does not block the duty-cycle bars from refreshing.
     */
    private Map<Integer, ChannelInterferenceService.ChannelInterference> interference =
            new HashMap<>();

    public ChannelOccupancyView(SettingsStore settings) {
        this.settings = settings;
        widthProperty().addListener((o, a, b) -> redraw());
        heightProperty().addListener((o, a, b) -> redraw());
        // Re-filter when the active scan plan changes so rows appear /
        // disappear in lock-step with the spectrum view.
        settings.getFrequencyPlan().addListener(
                () -> Platform.runLater(this::redraw));
        settings.getFrequency().addListener(
                () -> Platform.runLater(this::redraw));
    }

    /**
     * Push a fresh snapshot from the {@link ChannelOccupancyService} and
     * redraw. Caller must marshal to the FX thread.
     */
    public void setSnapshot(ChannelOccupancyService.Snapshot snap) {
        if (snap == null) {
            this.stats = List.of();
        } else {
            this.stats = snap.stats();
        }
        redraw();
    }

    /**
     * Push a fresh per-channel interference tally (Co / Adj counts).
     * Caller must marshal to the FX thread. Pass {@code null} to clear
     * the counts (renders as "0/0").
     */
    public void setInterference(ChannelInterferenceService.Snapshot snap) {
        Map<Integer, ChannelInterferenceService.ChannelInterference> map = new HashMap<>();
        if (snap != null) {
            for (ChannelInterferenceService.ChannelInterference e : snap.entries()) {
                // Channel number alone is not unique across bands (ch 1 in
                // 6 GHz exists in some plans), so combine band + number to
                // avoid 5 GHz / 6 GHz collisions when we look up later.
                map.put(channelKey(e.channel()), e);
            }
        }
        this.interference = map;
        redraw();
    }

    private static int channelKey(WifiChannelCatalog.Channel ch) {
        // Pack band ordinal (0..2) into the high byte so 2.4 GHz ch 1 and
        // 6 GHz ch 1 hash to different slots.
        return (ch.band().ordinal() << 16) | ch.number();
    }

    private void redraw() {
        GraphicsContext g = getGraphicsContext2D();
        double w = getWidth();
        double h = getHeight();
        if (w < 4) return;

        g.setFill(BG);
        g.fillRect(0, 0, w, h);

        List<ChannelOccupancyService.ChannelStat> visible = filterToScanRange();
        // Resize ourselves to fit the visible row count so the surrounding
        // ScrollPane stops stealing height. setHeight is FX-thread safe and
        // triggers another redraw via the listener above.
        double targetH = Math.max(ROW_HEIGHT,
                visible.size() * ROW_HEIGHT + ROW_PADDING * 2);
        if (Math.abs(h - targetH) > 0.5) {
            setHeight(targetH);
            return;
        }

        if (visible.isEmpty()) {
            g.setFill(SECONDARY);
            g.setFont(Font.font("Dialog", 11));
            g.fillText("No Wi-Fi channels in current scan range.",
                    SIDE_PADDING, ROW_HEIGHT);
            return;
        }

        double barX = LABEL_WIDTH + SIDE_PADDING;
        double barRight = w - VALUE_WIDTH - SIDE_PADDING;
        double barW = Math.max(20, barRight - barX);
        double y = ROW_PADDING;

        Font labelFont = Font.font("Dialog", FontWeight.NORMAL, 11);
        Font valueFont = Font.font("Dialog", FontWeight.NORMAL, 10);
        g.setFont(labelFont);

        for (ChannelOccupancyService.ChannelStat s : visible) {
            // Row baseline so text vertically aligns with the bar's centre.
            double rowMid = y + ROW_HEIGHT / 2 + 4;

            g.setFill(LABEL);
            g.fillText(s.channel().label(), SIDE_PADDING, rowMid);

            // Empty rail is always drawn so a 0% channel still has an
            // outline and the row never looks like a layout glitch.
            g.setFill(GRID);
            g.fillRect(barX, y + 2, barW, ROW_HEIGHT - 4);

            if (s.sampleCount() > 0) {
                double frac = Math.max(0, Math.min(1, s.occupancyPercent() / 100));
                double fillW = Math.max(1, barW * frac);
                g.setFill(barColor(s.channel().band(), s.occupancyPercent()));
                g.fillRect(barX, y + 2, fillW, ROW_HEIGHT - 4);
            }

            // Right-hand readout: occupancy% + dBm + "C:N A:N".
            // Three values share the slot because they answer three
            // different questions ("busy?", "loud?", "crowded?") and are
            // most useful side-by-side. Occupancy and dBm come from the
            // sweep, C/A from the Wi-Fi adapter snapshot.
            g.setFont(valueFont);
            String pctText;
            String dbmText;
            if (s.sampleCount() == 0) {
                pctText = "--";
                dbmText = "no data";
            } else {
                pctText = String.format("%3.0f%%", s.occupancyPercent());
                dbmText = String.format("%5.1f dBm", s.averageDbm());
            }
            g.setFill(VALUE);
            g.fillText(pctText, barRight + 4, rowMid);
            g.setFill(SECONDARY);
            g.fillText(dbmText, barRight + 40, rowMid);

            ChannelInterferenceService.ChannelInterference info =
                    interference.get(channelKey(s.channel()));
            int co = info == null ? 0 : info.coChannelCount();
            int adj = info == null ? 0 : info.adjacentChannelCount();
            // Tinted only when non-zero so a clean channel reads as quiet
            // grey and a busy one jumps out at a glance.
            g.setFill(co > 0 ? CO_CHANNEL : SECONDARY);
            g.fillText("C:" + co, barRight + 105, rowMid);
            g.setFill(adj > 0 ? ADJ_CHANNEL : SECONDARY);
            g.fillText("A:" + adj, barRight + 138, rowMid);
            g.setFont(labelFont);

            y += ROW_HEIGHT;
        }
    }

    /**
     * Keep only channels that overlap the current spectrum scan plan so the
     * view never claims to know occupancy for a band the SDR is not
     * looking at. Empty result -> placeholder text in {@link #redraw}.
     */
    private List<ChannelOccupancyService.ChannelStat> filterToScanRange() {
        FrequencyPlan plan = settings.getEffectivePlan();
        List<ChannelOccupancyService.ChannelStat> out = new ArrayList<>(stats.size());
        if (plan == null) return out;
        for (ChannelOccupancyService.ChannelStat s : stats) {
            if (planContainsRange(plan, s.channel().lowMhz(), s.channel().highMhz())) {
                out.add(s);
            }
        }
        return out;
    }

    private static boolean planContainsRange(FrequencyPlan plan, int lowMhz, int highMhz) {
        // A channel is "visible" if any segment overlaps it. We don't insist
        // on full containment so channels at the ragged edge of a custom
        // plan still appear (their bar will simply wiggle while only half
        // the bins drive the average).
        for (FrequencyRange seg : plan.segments()) {
            if (seg.getEndMHz() <= lowMhz) continue;
            if (seg.getStartMHz() >= highMhz) continue;
            return true;
        }
        return false;
    }

    /**
     * Fade from a calm tint to a hotter alert tint as occupancy climbs.
     * Per-band base tint matches the marker palette so a bar visually
     * echoes its channel-grid stripe.
     */
    private static Color barColor(WifiAccessPoint.Band band, double pct) {
        Color cool = switch (band) {
            case GHZ_24 -> Color.web("#ff9f40");
            case GHZ_5  -> Color.web("#5fa8ff");
            case GHZ_6  -> Color.web("#7fdc7f");
        };
        // Above ~70 % occupancy push toward a clearer red so the eye spots
        // a saturated channel without having to read the percentage.
        double t = Math.max(0, Math.min(1, (pct - 30) / 60.0));
        return cool.interpolate(Color.web("#ff5555"), t);
    }
}
