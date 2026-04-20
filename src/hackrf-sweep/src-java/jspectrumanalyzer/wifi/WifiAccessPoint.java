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
 *   <li>{@code bandwidthMhz} - operating channel bandwidth in MHz (20, 40,
 *       80, 160 or 80+80 reported as 160). Derived from the parsed HT/VHT/HE
 *       Operation IE on Windows; falls back to 20 MHz when no IE is
 *       available (older PHYs or platform stubs).</li>
 *   <li>{@code bondedCenterMhz} - RF centre of the full bonded-channel
 *       block in MHz. For 20 MHz APs this equals {@code centerFrequencyMhz}.
 *       For HT 40 / VHT 80 / 160 / HE 6 GHz the bonded block is offset
 *       from the primary by the secondary-channel offset (HT) or by the
 *       Channel Center Frequency Segment 0 field (VHT/HE). The compact
 *       constructor falls back to {@code centerFrequencyMhz} whenever a
 *       caller passes 0 / a non-positive value, so old call sites and
 *       platform stubs that do not know the bonded centre still compose
 *       a valid record.</li>
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
        String phyType,
        int bandwidthMhz,
        int bondedCenterMhz) {

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
        if (bandwidthMhz <= 0) bandwidthMhz = 20;
        // bondedCenterMhz <= 0 means "caller does not know"; fall back to
        // the primary so downstream code can use bondedCenterMhz()
        // unconditionally without null checks.
        if (bondedCenterMhz <= 0) bondedCenterMhz = centerFrequencyMhz;
    }

    /**
     * Convenience constructor from a frequency reported in kHz (the Windows
     * BSS list unit) to keep the conversion in exactly one place. Defaults
     * the operating bandwidth to 20 MHz and the bonded centre to the
     * primary - callers with parsed HT/VHT/HE info should use the wider
     * overload to override both.
     */
    public static WifiAccessPoint fromKhz(String bssid, String ssid, int rssiDbm,
                                          long centerFrequencyKhz, String phyType) {
        return fromKhz(bssid, ssid, rssiDbm, centerFrequencyKhz, phyType, 20, 0);
    }

    /**
     * Convenience constructor from a frequency reported in kHz (the Windows
     * BSS list unit) plus a parsed operating bandwidth in MHz. Defaults
     * the bonded centre to the primary (caller did not supply one).
     */
    public static WifiAccessPoint fromKhz(String bssid, String ssid, int rssiDbm,
                                          long centerFrequencyKhz, String phyType,
                                          int bandwidthMhz) {
        return fromKhz(bssid, ssid, rssiDbm, centerFrequencyKhz, phyType,
                bandwidthMhz, 0);
    }

    /**
     * Convenience constructor that also carries the parsed bonded-channel
     * centre frequency in MHz. Pass 0 to fall back to the primary centre.
     */
    public static WifiAccessPoint fromKhz(String bssid, String ssid, int rssiDbm,
                                          long centerFrequencyKhz, String phyType,
                                          int bandwidthMhz, int bondedCenterMhz) {
        // Round to nearest MHz: kHz / 1000 with rounding handles e.g.
        // 2412345 kHz (2412.345 MHz) -> 2412 MHz, the canonical ch 1 centre.
        int mhz = (int) Math.round(centerFrequencyKhz / 1000.0);
        return new WifiAccessPoint(bssid, ssid, rssiDbm, mhz, phyType,
                bandwidthMhz, bondedCenterMhz);
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
