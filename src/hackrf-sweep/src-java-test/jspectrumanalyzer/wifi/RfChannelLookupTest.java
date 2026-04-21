package jspectrumanalyzer.wifi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;

import org.junit.jupiter.api.Test;

/**
 * Boundary tests for {@link RfChannelLookup}. We pick centre frequencies
 * that have ground-truth assignments in real radios (Wi-Fi ch 1 = 2412 MHz,
 * Zigbee ch 11 = 2405 MHz, etc.) and a couple of "between" frequencies to
 * confirm we don't false-positive in the gaps.
 */
class RfChannelLookupTest {

    @Test
    void wifi24Centres() {
        assertLabel(2412.0, "Wi-Fi ch 1 (2.4 GHz)");
        assertLabel(2437.0, "Wi-Fi ch 6 (2.4 GHz)");
        assertLabel(2462.0, "Wi-Fi ch 11 (2.4 GHz)");
    }

    @Test
    void wifi5And6E() {
        assertLabel(5180.0, "Wi-Fi ch 36 (5 GHz)");
        assertLabel(5825.0, "Wi-Fi ch 165 (5 GHz)");
        assertLabel(5975.0, "Wi-Fi 6E ch 5");
    }

    @Test
    void zigbee24Centres() {
        // Wi-Fi catalogue covers 2.4 GHz only at 1/6/11 (with 22 MHz width
        // each); Zigbee channels in the gaps between Wi-Fi primaries should
        // resolve as Zigbee.
        assertLabel(2425.0, "Zigbee ch 15");
        assertLabel(2450.0, "Zigbee ch 20");
        assertLabel(2475.0, "Zigbee ch 25");
        assertLabel(2480.0, "Zigbee ch 26");
    }

    @Test
    void wifiTakesPriorityOverZigbeeOnOverlap() {
        // Zigbee ch 12 is centred at 2410 MHz; Wi-Fi ch 1 spans 2401..2423.
        // The user almost always cares about Wi-Fi here, so the catalogue
        // should win.
        Optional<String> hit = RfChannelLookup.labelFor(2410.0);
        assertTrue(hit.isPresent(), "expected a label at 2410 MHz");
        assertTrue(hit.get().startsWith("Wi-Fi"),
                "expected Wi-Fi to win at 2410 MHz, got: " + hit.get());
    }

    @Test
    void zigbeeSubGhz() {
        assertLabel(868.3, "Zigbee ch 0 (EU)");
        assertLabel(906.0, "Zigbee ch 1 (NA)");
        assertLabel(924.0, "Zigbee ch 10 (NA)");
    }

    @Test
    void unknownFrequenciesReturnEmpty() {
        // 1 MHz: well outside any catalogue band.
        assertEquals(Optional.empty(), RfChannelLookup.labelFor(1.0));
        // 3000 MHz: above 2.4 GHz ISM, below Wi-Fi 5.
        assertEquals(Optional.empty(), RfChannelLookup.labelFor(3000.0));
        // 2484 MHz: above Wi-Fi ch 11 high edge (2473) and outside Zigbee 26.
        assertEquals(Optional.empty(), RfChannelLookup.labelFor(2484.0));
    }

    private static void assertLabel(double rfMhz, String expected) {
        Optional<String> got = RfChannelLookup.labelFor(rfMhz);
        assertTrue(got.isPresent(), "no label at " + rfMhz + " MHz");
        assertEquals(expected, got.get(), "wrong label at " + rfMhz + " MHz");
    }
}
