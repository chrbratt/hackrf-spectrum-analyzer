package jspectrumanalyzer.wifi.capture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class WlanHelperTest {

    @Test
    @DisplayName("extractGuid pulls braced GUID from a pcap4j adapter id")
    void extractsBracedGuid() {
        assertEquals("{2debc390-c8bb-40b7-a714-349d32f4b2e6}",
                WlanHelper.extractGuid(
                        "\\Device\\NPF_{2debc390-c8bb-40b7-a714-349d32f4b2e6}"));
    }

    @Test
    @DisplayName("extractGuid passes plain connection names through unchanged")
    void passesConnectionNamesThrough() {
        // WlanHelper accepts both GUIDs and connection names; we should
        // not strip a name that happens to contain no braces.
        assertEquals("Wi-Fi", WlanHelper.extractGuid("Wi-Fi"));
    }

    @Test
    @DisplayName("extractGuid returns null for null input")
    void nullInputReturnsNull() {
        assertNull(WlanHelper.extractGuid(null));
    }
}
