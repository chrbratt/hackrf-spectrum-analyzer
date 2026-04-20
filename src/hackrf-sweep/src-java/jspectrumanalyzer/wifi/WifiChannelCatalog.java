package jspectrumanalyzer.wifi;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Static catalogue of well-known Wi-Fi channels covered by the analyzer.
 * Matches the {@code freq/Wi-Fi channel grid.csv} dataset that drives the
 * channel-grid overlay so the per-channel occupancy bars in the Wi-Fi
 * window line up visually with the chart's coloured stripes.
 *
 * <p>Channels are 20 MHz primaries only - the same simplification we use
 * elsewhere in Phase 1. Wider 40/80/160 MHz channels are derived from
 * primaries and a future revision (after IE parsing in
 * {@link WindowsWlanScanner}) can drop full HT/VHT/HE entries here.
 *
 * <p>This class is intentionally pure data: no Android logging, no FX
 * imports, just records and a static list. The {@code ChannelOccupancyService}
 * iterates over it once per sweep so allocations and lookups must stay cheap.
 */
public final class WifiChannelCatalog {

    /** A single 20 MHz Wi-Fi channel as drawn on the spectrum overlay. */
    public record Channel(int number, int centerMhz, int lowMhz, int highMhz,
                          WifiAccessPoint.Band band) {

        public int widthMhz() {
            return highMhz - lowMhz;
        }

        /** Short label for UI rows, e.g. "ch 6 (2.4 GHz)" or "ch 36 (5 GHz)". */
        public String label() {
            return switch (band) {
                case GHZ_24 -> "ch " + number + " (2.4 GHz)";
                case GHZ_5 -> "ch " + number + " (5 GHz)";
                case GHZ_6 -> "6E ch " + number;
            };
        }
    }

    /**
     * Immutable, ordered list of every channel the analyzer currently knows
     * about. Order matches the CSV: 2.4 GHz primaries first, then 5 GHz
     * UNII-1/2A/2C/3, then 6 GHz PSCs.
     */
    public static final List<Channel> ALL;

    static {
        List<Channel> list = new ArrayList<>(48);
        // 2.4 GHz: only the non-overlapping primaries to match the overlay.
        list.add(new Channel(1,  2412, 2401, 2423, WifiAccessPoint.Band.GHZ_24));
        list.add(new Channel(6,  2437, 2426, 2448, WifiAccessPoint.Band.GHZ_24));
        list.add(new Channel(11, 2462, 2451, 2473, WifiAccessPoint.Band.GHZ_24));

        // 5 GHz UNII-1
        addFiveGhz(list, new int[]{36, 40, 44, 48});
        // 5 GHz UNII-2A (DFS)
        addFiveGhz(list, new int[]{52, 56, 60, 64});
        // 5 GHz UNII-2C (DFS)
        addFiveGhz(list, new int[]{100, 104, 108, 112, 116, 120, 124, 128, 132, 136, 140, 144});
        // 5 GHz UNII-3
        addFiveGhz(list, new int[]{149, 153, 157, 161, 165});

        // 6 GHz PSCs (every 16th channel = preferred scanning channels).
        addSixGhz(list, new int[]{
                5, 21, 37, 53, 69, 85, 101, 117, 133, 149, 165, 181, 197, 213, 229
        });

        ALL = Collections.unmodifiableList(list);
    }

    private static void addFiveGhz(List<Channel> list, int[] channels) {
        // 5 GHz: ch N centre = 5000 + N*5 MHz; 20 MHz wide centred on the
        // primary, matching the CSV ranges and what real radios broadcast.
        for (int n : channels) {
            int center = 5000 + n * 5;
            list.add(new Channel(n, center, center - 10, center + 10,
                    WifiAccessPoint.Band.GHZ_5));
        }
    }

    private static void addSixGhz(List<Channel> list, int[] channels) {
        // 6 GHz: ch N centre = 5950 + N*5 MHz (Wi-Fi 6E numbering).
        for (int n : channels) {
            int center = 5950 + n * 5;
            list.add(new Channel(n, center, center - 10, center + 10,
                    WifiAccessPoint.Band.GHZ_6));
        }
    }

    private WifiChannelCatalog() {}
}
