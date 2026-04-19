package jspectrumanalyzer.wifi;

import java.util.Objects;

/**
 * Immutable description of a single Wi-Fi access point as observed by the
 * platform's network adapter (Windows Native Wi-Fi API in production, no-op
 * stub everywhere else).
 *
 * <p>Field semantics:
 * <ul>
 *   <li>{@code bssid} - colon-separated lowercase MAC, e.g. {@code aa:bb:cc:dd:ee:ff}.
 *       Used as the identity of an AP; two BSSIDs from the same physical box
 *       are still treated as separate APs because each beacons independently.</li>
 *   <li>{@code ssid} - decoded UTF-8 network name; empty for hidden networks.</li>
 *   <li>{@code rssiDbm} - signal strength in dBm (negative number; -50 strong,
 *       -90 near noise floor).</li>
 *   <li>{@code centerFrequencyMhz} - reported by the OS in kHz, converted to MHz
 *       in {@link #fromKhz}. Used both for {@link #channel} derivation and to
 *       align AP markers with the spectrum chart.</li>
 *   <li>{@code phyType} - friendly name of the radio (e.g. "802.11ac") for the
 *       UI; {@code null} when unknown.</li>
 * </ul>
 *
 * <p>The record is a value type - {@code equals}/{@code hashCode} are derived
 * so two scans returning the same AP twice are correctly de-duplicated by any
 * downstream consumer that uses a Set.
 */
public record WifiAccessPoint(
        String bssid,
        String ssid,
        int rssiDbm,
        int centerFrequencyMhz,
        String phyType) {

    /** Lower edge of the 2.4 GHz band in MHz. */
    public static final int BAND_24_LOW_MHZ = 2400;
    public static final int BAND_24_HIGH_MHZ = 2500;
    /** Lower edge of the 5 GHz band in MHz. */
    public static final int BAND_5_LOW_MHZ = 5150;
    public static final int BAND_5_HIGH_MHZ = 5895;
    /** Lower edge of the 6 GHz band in MHz (Wi-Fi 6E PSC range). */
    public static final int BAND_6_LOW_MHZ = 5895;
    public static final int BAND_6_HIGH_MHZ = 7125;

    public WifiAccessPoint {
        Objects.requireNonNull(bssid, "bssid");
        Objects.requireNonNull(ssid, "ssid");
        // phyType may be null (the Windows stack reports unknown PHYs as 0).
    }

    /**
     * Convenience constructor from a frequency reported in kHz (the Windows
     * BSS list unit) to keep the conversion in exactly one place.
     */
    public static WifiAccessPoint fromKhz(String bssid, String ssid, int rssiDbm,
                                          long centerFrequencyKhz, String phyType) {
        // Round to nearest MHz: kHz / 1000 with rounding handles e.g.
        // 2412345 kHz (2412.345 MHz) -> 2412 MHz, the canonical ch 1 centre.
        int mhz = (int) Math.round(centerFrequencyKhz / 1000.0);
        return new WifiAccessPoint(bssid, ssid, rssiDbm, mhz, phyType);
    }

    /** Three-way band classification. {@code null} for out-of-band reports. */
    public Band band() {
        if (centerFrequencyMhz >= BAND_24_LOW_MHZ && centerFrequencyMhz < BAND_24_HIGH_MHZ) return Band.GHZ_24;
        if (centerFrequencyMhz >= BAND_5_LOW_MHZ && centerFrequencyMhz < BAND_5_HIGH_MHZ) return Band.GHZ_5;
        if (centerFrequencyMhz >= BAND_6_LOW_MHZ && centerFrequencyMhz <= BAND_6_HIGH_MHZ) return Band.GHZ_6;
        return null;
    }

    /**
     * Derived channel number using the standard 802.11 channel-to-frequency
     * mapping per band. Returns {@code -1} if the frequency does not match
     * any allocated channel (e.g. propagation noise, off-band reports).
     */
    public int channel() {
        Band b = band();
        if (b == null) return -1;
        return switch (b) {
            // 2.4 GHz: ch N centre = 2407 + N*5 MHz, with ch 14 at 2484.
            case GHZ_24 -> centerFrequencyMhz == 2484 ? 14 : (centerFrequencyMhz - 2407) / 5;
            // 5 GHz: ch N centre = 5000 + N*5 MHz.
            case GHZ_5 -> (centerFrequencyMhz - 5000) / 5;
            // 6 GHz: ch N centre = 5950 + N*5 MHz (Wi-Fi 6E numbering).
            case GHZ_6 -> (centerFrequencyMhz - 5950) / 5;
        };
    }

    /** Stable display label for the band (e.g. "2.4 GHz"). */
    public String bandLabel() {
        Band b = band();
        return b == null ? "?" : b.label;
    }

    public enum Band {
        GHZ_24("2.4 GHz"),
        GHZ_5("5 GHz"),
        GHZ_6("6 GHz");

        public final String label;
        Band(String label) { this.label = label; }
    }
}
