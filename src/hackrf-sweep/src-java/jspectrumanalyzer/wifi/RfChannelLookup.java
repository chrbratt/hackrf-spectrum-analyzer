package jspectrumanalyzer.wifi;

import java.util.Optional;

/**
 * Maps an RF frequency in MHz to a short human-readable channel label
 * (e.g. {@code "Wi-Fi ch 6 (2.4 GHz)"}, {@code "Zigbee ch 11"}).
 *
 * <p>Used by the spectrum chart and waterfall hover read-out so a user
 * can identify which standard they are looking at without having to
 * remember every band plan.
 *
 * <p>Recognised:
 * <ul>
 *   <li>Wi-Fi 2.4 / 5 / 6 GHz primaries via {@link WifiChannelCatalog}.</li>
 *   <li>Zigbee 802.15.4 channels 11..26 in the 2.4 GHz ISM band
 *       (2 MHz wide, centred at {@code 2405 + 5*(N-11)} MHz).</li>
 *   <li>Zigbee 802.15.4 channel 0 in the EU 868 MHz SRD band
 *       (centre 868.3 MHz, 600 kHz wide).</li>
 *   <li>Zigbee 802.15.4 channels 1..10 in the NA 915 MHz ISM band
 *       (centred at {@code 906 + 2*(N-1)} MHz, 600 kHz wide).</li>
 * </ul>
 *
 * <p>Wi-Fi takes priority over Zigbee on a tie because the user is
 * far more likely to be looking at Wi-Fi traffic; Zigbee labels still
 * appear when the cursor sits clearly inside a 2 MHz Zigbee channel
 * (e.g. ch 15 around 2425 MHz lands between Wi-Fi ch 1 and 6).
 *
 * <p>Pure data, no Android / FX imports - kept here next to the
 * Wi-Fi catalogue so the unit tests can run headless.
 */
public final class RfChannelLookup {

    /** Half-width of a Zigbee 2.4 GHz channel in MHz. */
    private static final double ZIGBEE_24_HALF_MHZ = 1.0;
    /** Half-width of a Zigbee sub-GHz channel in MHz (~600 kHz wide). */
    private static final double ZIGBEE_SUBGHZ_HALF_MHZ = 0.3;

    private RfChannelLookup() {}

    /**
     * Returns a short label for the channel that contains {@code rfMhz},
     * or {@link Optional#empty()} if none of the known bands matches.
     */
    public static Optional<String> labelFor(double rfMhz) {
        Optional<String> wifi = wifiLabel(rfMhz);
        if (wifi.isPresent()) return wifi;
        return zigbeeLabel(rfMhz);
    }

    private static Optional<String> wifiLabel(double rfMhz) {
        for (WifiChannelCatalog.Channel ch : WifiChannelCatalog.ALL) {
            if (rfMhz >= ch.lowMhz() && rfMhz <= ch.highMhz()) {
                return Optional.of("Wi-Fi " + ch.label());
            }
        }
        return Optional.empty();
    }

    private static Optional<String> zigbeeLabel(double rfMhz) {
        // 2.4 GHz Zigbee: 16 channels, 11..26.
        if (rfMhz >= 2403.0 && rfMhz <= 2482.0) {
            for (int n = 11; n <= 26; n++) {
                double centre = 2405.0 + 5.0 * (n - 11);
                if (Math.abs(rfMhz - centre) <= ZIGBEE_24_HALF_MHZ) {
                    return Optional.of("Zigbee ch " + n);
                }
            }
            return Optional.empty();
        }
        // EU 868 MHz: single channel 0 at 868.3 MHz.
        if (Math.abs(rfMhz - 868.3) <= ZIGBEE_SUBGHZ_HALF_MHZ) {
            return Optional.of("Zigbee ch 0 (EU)");
        }
        // NA 915 MHz: channels 1..10, centres 906..924 MHz, 2 MHz spacing.
        if (rfMhz >= 905.5 && rfMhz <= 924.5) {
            for (int n = 1; n <= 10; n++) {
                double centre = 906.0 + 2.0 * (n - 1);
                if (Math.abs(rfMhz - centre) <= ZIGBEE_SUBGHZ_HALF_MHZ) {
                    return Optional.of("Zigbee ch " + n + " (NA)");
                }
            }
        }
        return Optional.empty();
    }
}
