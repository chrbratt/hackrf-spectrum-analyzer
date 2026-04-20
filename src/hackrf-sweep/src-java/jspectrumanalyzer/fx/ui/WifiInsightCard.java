package jspectrumanalyzer.fx.ui;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javafx.geometry.Insets;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import jspectrumanalyzer.core.FrequencyPlan;
import jspectrumanalyzer.core.FrequencyRange;
import jspectrumanalyzer.fx.model.SettingsStore;
import jspectrumanalyzer.wifi.ChannelOccupancyService;
import jspectrumanalyzer.wifi.WifiAccessPoint;
import jspectrumanalyzer.wifi.WifiChannelCatalog;
import jspectrumanalyzer.wifi.capture.BeaconStore;

/**
 * Top-of-Wi-Fi-window "insight card": a plain-English live summary of
 * what the user is currently looking at. Designed so a first-time user
 * can answer the three questions any spectrum survey is really about,
 * without having to interpret a chart:
 *
 * <ol>
 *   <li><b>What am I scanning right now?</b> - active sweep range</li>
 *   <li><b>What is the air like?</b> - AP counts per band, most
 *       crowded channel, worst duty cycle</li>
 *   <li><b>Where is it quiet?</b> - cleanest 20 MHz channel inside
 *       the active sweep range, useful as an "AP placement" hint</li>
 * </ol>
 *
 * <p>Implementation: pure read-only computation in
 * {@link #recompute(List, ChannelOccupancyService.Snapshot, FrequencyPlan)}.
 * The card holds no state - callers re-invoke whenever any of the
 * three input streams ticks (scan snapshot, occupancy snapshot, plan
 * change). Hidden-SSID resolver counts come straight from the shared
 * {@link BeaconStore} so the card and the AP table can never
 * disagree.</p>
 *
 * <p>Why a dedicated component rather than baking the strings into
 * {@code WifiWindow}? Three reasons: (1) the FX layout in WifiWindow
 * is already large; isolating the insight wording keeps that file from
 * growing further. (2) Insight wording will likely keep evolving as
 * we learn what users actually find useful, so giving it its own home
 * makes those iterations cheap. (3) The class is now testable in
 * isolation when we eventually add a deterministic-string regression
 * test for it.</p>
 */
public final class WifiInsightCard extends VBox {

    private final BeaconStore beaconStore;

    private final Label rangeLabel = new Label();
    private final Label countsLabel = new Label();
    private final Label crowdedLabel = new Label();
    private final Label cleanestLabel = new Label();
    private final Label hiddenLabel = new Label();

    public WifiInsightCard(BeaconStore beaconStore) {
        this.beaconStore = beaconStore;
        getStyleClass().add("wifi-insight-card");
        setPadding(new Insets(10, 14, 10, 14));
        setSpacing(2);

        rangeLabel.getStyleClass().add("wifi-insight-headline");
        rangeLabel.setWrapText(true);

        for (Label l : List.of(countsLabel, crowdedLabel, cleanestLabel, hiddenLabel)) {
            l.getStyleClass().add("wifi-insight-line");
            l.setWrapText(true);
        }

        // Two-column layout for the supporting lines so they line up
        // visually instead of running edge-to-edge as a wall of text.
        // The HBox columns get equal Hgrow priority so a long SSID name
        // in one column does not push the other off-screen.
        VBox left = new VBox(2, countsLabel, crowdedLabel);
        VBox right = new VBox(2, cleanestLabel, hiddenLabel);
        HBox.setHgrow(left, Priority.ALWAYS);
        HBox.setHgrow(right, Priority.ALWAYS);
        left.setMaxWidth(Double.MAX_VALUE);
        right.setMaxWidth(Double.MAX_VALUE);
        HBox cols = new HBox(16, left, right);
        cols.setFillHeight(true);

        getChildren().addAll(rangeLabel, cols);

        // Render an empty-state up front so the card has structure even
        // before the first scan snapshot arrives. Otherwise the card
        // would briefly show as a thin coloured line at startup, which
        // looks like a layout bug.
        renderEmpty();
    }

    /**
     * Refresh the card from the latest inputs. Pass {@code null} for
     * any stream that has not yet produced data; the card degrades to
     * "waiting for ..." messages instead of throwing.
     *
     * @param aps full unfiltered AP scan snapshot from
     *            {@code WifiScanService}; may be empty / null
     * @param occ latest channel occupancy snapshot; may be null
     * @param activePlan currently active scan plan; may be null
     */
    public void recompute(List<WifiAccessPoint> aps,
                          ChannelOccupancyService.Snapshot occ,
                          FrequencyPlan activePlan) {
        rangeLabel.setText(buildRangeText(activePlan));

        if (aps == null || aps.isEmpty()) {
            countsLabel.setText("Waiting for the first Wi-Fi scan...");
            crowdedLabel.setText("");
            cleanestLabel.setText("");
            hiddenLabel.setText(buildHiddenText(0));
            return;
        }

        countsLabel.setText(buildCountsText(aps));
        crowdedLabel.setText(buildCrowdedText(aps, occ, activePlan));
        cleanestLabel.setText(buildCleanestText(aps, occ, activePlan));
        hiddenLabel.setText(buildHiddenText(countHiddenAps(aps)));
    }

    private void renderEmpty() {
        rangeLabel.setText("Pick a band below to start scanning Wi-Fi.");
        countsLabel.setText("Waiting for the first Wi-Fi scan...");
        crowdedLabel.setText("");
        cleanestLabel.setText("");
        hiddenLabel.setText("Run Monitor capture to resolve hidden SSID names.");
    }

    // ---------------------------------------------------------------- text builders

    private static String buildRangeText(FrequencyPlan plan) {
        if (plan == null || plan.segmentCount() == 0) {
            return "No active scan range.";
        }
        if (plan.segmentCount() == 1) {
            FrequencyRange r = plan.segments().get(0);
            return String.format("Now scanning: %d-%d MHz (%d MHz wide).",
                    r.getStartMHz(), r.getEndMHz(),
                    r.getEndMHz() - r.getStartMHz());
        }
        int total = 0;
        for (FrequencyRange r : plan.segments()) {
            total += r.getEndMHz() - r.getStartMHz();
        }
        return String.format("Now scanning: %d band segments, %d MHz total.",
                plan.segmentCount(), total);
    }

    private static String buildCountsText(List<WifiAccessPoint> aps) {
        EnumMap<WifiAccessPoint.Band, Integer> counts =
                new EnumMap<>(WifiAccessPoint.Band.class);
        for (WifiAccessPoint.Band b : WifiAccessPoint.Band.values()) counts.put(b, 0);
        Set<String> bssids = new HashSet<>();
        for (WifiAccessPoint ap : aps) {
            WifiAccessPoint.Band b = ap.band();
            if (b != null) counts.merge(b, 1, Integer::sum);
            if (ap.bssid() != null) bssids.add(ap.bssid());
        }
        return String.format("APs heard: %d  (2.4 GHz: %d  /  5 GHz: %d  /  6 GHz: %d)",
                bssids.size(),
                counts.get(WifiAccessPoint.Band.GHZ_24),
                counts.get(WifiAccessPoint.Band.GHZ_5),
                counts.get(WifiAccessPoint.Band.GHZ_6));
    }

    /**
     * Find the most crowded channel inside the active scan plan. Two
     * scoring axes are combined: AP count (primary - the user's first
     * instinct) and occupancy percent (secondary - "crowded" should
     * mean "busy in the air" not just "many BSSIDs registered").
     */
    private String buildCrowdedText(List<WifiAccessPoint> aps,
                                    ChannelOccupancyService.Snapshot occ,
                                    FrequencyPlan plan) {
        Map<Integer, List<WifiAccessPoint>> byCh = groupByChannel(aps, plan);
        if (byCh.isEmpty()) return "No APs inside the active scan range.";

        // Primary sort by AP count descending; tie-break on max RSSI so
        // a crowded channel with weak APs loses to one with strong APs
        // sitting on it (those degrade Wi-Fi performance more).
        WifiChannelCatalog.Channel worst = null;
        int worstCount = 0;
        int worstMaxRssi = Integer.MIN_VALUE;
        for (Map.Entry<Integer, List<WifiAccessPoint>> e : byCh.entrySet()) {
            int count = e.getValue().size();
            int maxRssi = Integer.MIN_VALUE;
            for (WifiAccessPoint ap : e.getValue()) {
                if (ap.rssiDbm() > maxRssi) maxRssi = ap.rssiDbm();
            }
            if (count > worstCount
                    || (count == worstCount && maxRssi > worstMaxRssi)) {
                worstCount = count;
                worstMaxRssi = maxRssi;
                worst = lookupChannel(e.getKey(), e.getValue().get(0));
            }
        }
        if (worst == null) return "";

        String dutyText = formatDuty(occ, worst);
        return String.format("Most crowded: ch %d  (%d AP%s%s)",
                worst.number(), worstCount, worstCount == 1 ? "" : "s", dutyText);
    }

    /**
     * Find the cleanest 20 MHz primary channel inside the active scan
     * plan. "Cleanest" = fewest APs, tie-break on lowest occupancy
     * percent. Channels not visited by any AP score best (count 0) and
     * are still useful suggestions even when occupancy data is missing.
     */
    private String buildCleanestText(List<WifiAccessPoint> aps,
                                     ChannelOccupancyService.Snapshot occ,
                                     FrequencyPlan plan) {
        Map<Integer, List<WifiAccessPoint>> byCh = groupByChannel(aps, plan);
        List<WifiChannelCatalog.Channel> candidates = channelsInPlan(plan);
        if (candidates.isEmpty()) return "";

        WifiChannelCatalog.Channel best = null;
        int bestCount = Integer.MAX_VALUE;
        double bestDuty = Double.POSITIVE_INFINITY;
        for (WifiChannelCatalog.Channel ch : candidates) {
            List<WifiAccessPoint> bucket = byCh.getOrDefault(ch.number(), List.of());
            int count = bucket.size();
            double duty = lookupDuty(occ, ch);
            if (count < bestCount
                    || (count == bestCount && duty < bestDuty)) {
                best = ch;
                bestCount = count;
                bestDuty = duty;
            }
        }
        if (best == null) return "";

        String dutyText = formatDuty(occ, best);
        if (bestCount == 0) {
            return String.format("Cleanest spot: ch %d  (no APs%s)",
                    best.number(), dutyText);
        }
        return String.format("Cleanest spot: ch %d  (%d AP%s%s)",
                best.number(), bestCount, bestCount == 1 ? "" : "s", dutyText);
    }

    private String buildHiddenText(int hiddenAps) {
        int discovered = beaconStore.discoveredCount();
        if (hiddenAps == 0 && discovered == 0) {
            return "No hidden SSIDs in this scan.";
        }
        if (hiddenAps == 0) {
            return String.format("Hidden SSIDs resolved: %d (none currently visible).",
                    discovered);
        }
        // discovered may exceed hiddenAps when capture has resolved
        // names that the OS scan no longer hears - still a useful
        // count, present it as "of N currently hidden".
        return String.format("Hidden SSIDs: %d currently  /  %d resolved by capture.",
                hiddenAps, discovered);
    }

    // ---------------------------------------------------------------- helpers

    /**
     * Group APs by primary channel, keeping only those whose centre
     * frequency falls inside the active scan plan. We bucket on the
     * primary (not the bonded centre) so 80 MHz APs slot under their
     * primary channel - matches what the AP table and density chart
     * overlay show.
     */
    private static Map<Integer, List<WifiAccessPoint>> groupByChannel(
            List<WifiAccessPoint> aps, FrequencyPlan plan) {
        Map<Integer, List<WifiAccessPoint>> out = new java.util.LinkedHashMap<>();
        List<FrequencyRange> ranges = (plan == null) ? List.of() : plan.segments();
        for (WifiAccessPoint ap : aps) {
            if (!ranges.isEmpty() && !inAnyRange(ap.centerFrequencyMhz(), ranges)) continue;
            int chNum = ap.channel();
            if (chNum <= 0) continue;
            out.computeIfAbsent(chNum, k -> new ArrayList<>()).add(ap);
        }
        return out;
    }

    private static List<WifiChannelCatalog.Channel> channelsInPlan(FrequencyPlan plan) {
        List<FrequencyRange> ranges = (plan == null) ? List.of() : plan.segments();
        if (ranges.isEmpty()) return WifiChannelCatalog.ALL;
        List<WifiChannelCatalog.Channel> out = new ArrayList<>();
        for (WifiChannelCatalog.Channel ch : WifiChannelCatalog.ALL) {
            if (inAnyRange(ch.centerMhz(), ranges)) out.add(ch);
        }
        return out;
    }

    private static boolean inAnyRange(int mhz, List<FrequencyRange> ranges) {
        for (FrequencyRange r : ranges) {
            if (mhz >= r.getStartMHz() && mhz <= r.getEndMHz()) return true;
        }
        return false;
    }

    private static WifiChannelCatalog.Channel lookupChannel(int number, WifiAccessPoint hint) {
        WifiAccessPoint.Band band = hint.band();
        for (WifiChannelCatalog.Channel ch : WifiChannelCatalog.ALL) {
            if (ch.number() == number && ch.band() == band) return ch;
        }
        for (WifiChannelCatalog.Channel ch : WifiChannelCatalog.ALL) {
            if (ch.number() == number) return ch;
        }
        return null;
    }

    private static double lookupDuty(ChannelOccupancyService.Snapshot occ,
                                     WifiChannelCatalog.Channel ch) {
        if (occ == null) return Double.POSITIVE_INFINITY;
        for (ChannelOccupancyService.ChannelStat s : occ.stats()) {
            if (s.channel().number() == ch.number()
                    && s.channel().band() == ch.band()) {
                return s.occupancyPercent();
            }
        }
        return Double.POSITIVE_INFINITY;
    }

    private static String formatDuty(ChannelOccupancyService.Snapshot occ,
                                     WifiChannelCatalog.Channel ch) {
        double d = lookupDuty(occ, ch);
        if (Double.isInfinite(d)) return "";
        return String.format(", %.0f%% duty", d);
    }

    private static int countHiddenAps(List<WifiAccessPoint> aps) {
        int n = 0;
        for (WifiAccessPoint ap : aps) {
            if (ap.ssid() == null || ap.ssid().isEmpty()) n++;
        }
        return n;
    }

    /** Convenience: avoid the SettingsStore import where the caller already has the live plan. */
    public static FrequencyPlan resolvePlan(SettingsStore settings) {
        if (settings == null) return null;
        return settings.getEffectivePlan();
    }
}
