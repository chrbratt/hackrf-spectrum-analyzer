package jspectrumanalyzer.wifi.capture.ieee80211;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Cover the IE-walking and beacon-parsing primitives with synthesised
 * frames so a regression in the parser surfaces here, not silently in
 * production where it would manifest as "hidden SSIDs never get
 * resolved" or "BSS Load bars stay at 0%".
 *
 * <p>The fixtures build the smallest possible byte sequences that the
 * parser will accept - 24-byte MAC header, 12-byte fixed body and
 * just the IEs we want to test, in the order the spec recommends
 * (SSID first, then anything else). Real beacons are larger (rates,
 * RSN, vendor) but the parser is order-independent so the additional
 * bytes would just be skipped.
 */
class BeaconParserTest {

    @Test
    @DisplayName("IeWalker yields one element per TLV and stops at truncation")
    void walkParsesElementsAndStopsCleanly() {
        // Two well-formed elements followed by a truncated third (length
        // says 5 bytes but only 2 remain) - parser must return the
        // first two and bail without throwing.
        byte[] data = {
                0x00, 0x04, 'a', 'b', 'c', 'd',     // SSID "abcd"
                0x0b, 0x05, 0x02, 0x00, 0x40, 0x10, 0x00, // BSS Load (5 bytes)
                0x07, 0x05, 'X', 'Y'                 // truncated Country IE
        };
        List<InformationElement> ies = IeWalker.walk(data, 0, data.length);
        assertEquals(2, ies.size());
        assertEquals(InformationElement.ID_SSID, ies.get(0).id());
        assertArrayEqualsAscii("abcd", ies.get(0).body());
        assertEquals(InformationElement.ID_BSS_LOAD, ies.get(1).id());
    }

    @Test
    @DisplayName("BssLoad.parse converts 0-255 utilization to 0-100 percent with rounding")
    void bssLoadConvertsUtilizationByte() {
        // 64 / 255 ~= 25.1% -> rounded to 25.
        BssLoad load = BssLoad.parse(new byte[] { 0x05, 0x00, 0x40, 0x00, 0x00 });
        assertNotNull(load);
        assertEquals(5, load.stationCount());
        assertEquals(25, load.channelUtilizationPercent());
        assertEquals(0, load.availableAdmissionCapacity());
    }

    @Test
    @DisplayName("BssLoad.parse rejects malformed bodies")
    void bssLoadRejectsShortBody() {
        assertNull(BssLoad.parse(new byte[] { 0x00 }));
        assertNull(BssLoad.parse(new byte[0]));
        assertNull(BssLoad.parse(null));
    }

    @Test
    @DisplayName("BeaconParser extracts SSID and BSSID from a synthesised beacon")
    void beaconExtractsSsidAndBssid() {
        byte[] frame = beacon(
                bssid("11:22:33:44:55:66"),
                ie(InformationElement.ID_SSID, "MyNet".getBytes(StandardCharsets.UTF_8))
        );
        assertTrue(BeaconParser.isBeaconOrProbeResp(frame));
        assertEquals("11:22:33:44:55:66", BeaconParser.bssidOf(frame));
        BeaconBody body = BeaconParser.parse(frame);
        assertTrue(body.ssid().isPresent());
        assertEquals("MyNet", body.ssid().get());
        assertFalse(body.bssLoad().isPresent());
    }

    @Test
    @DisplayName("BeaconParser surfaces BSS Load when the AP advertises one")
    void beaconExtractsBssLoad() {
        byte[] frame = beacon(
                bssid("aa:bb:cc:dd:ee:ff"),
                ie(InformationElement.ID_SSID, "Office".getBytes(StandardCharsets.UTF_8)),
                ie(InformationElement.ID_BSS_LOAD,
                        new byte[] { 0x07, 0x00, (byte) 0x80, 0x00, 0x00 })
        );
        BeaconBody body = BeaconParser.parse(frame);
        assertEquals("Office", body.ssid().orElse(null));
        assertTrue(body.bssLoad().isPresent());
        BssLoad load = body.bssLoad().get();
        assertEquals(7, load.stationCount());
        // 0x80 / 255 = 50.2% -> rounds to 50.
        assertEquals(50, load.channelUtilizationPercent());
    }

    @Test
    @DisplayName("Hidden SSID (length-0 IE) parses as Optional.of(\"\")")
    void hiddenSsidParsesAsEmptyString() {
        byte[] frame = beacon(
                bssid("00:11:22:33:44:55"),
                ie(InformationElement.ID_SSID, new byte[0])
        );
        BeaconBody body = BeaconParser.parse(frame);
        assertTrue(body.ssid().isPresent(), "hidden SSID IE must surface as present-but-empty");
        assertEquals("", body.ssid().get());
    }

    @Test
    @DisplayName("All-zero-bytes SSID body is folded into the empty string (alternate hidden encoding)")
    void allZeroSsidIsHidden() {
        byte[] frame = beacon(
                bssid("00:11:22:33:44:55"),
                ie(InformationElement.ID_SSID, new byte[] { 0, 0, 0, 0 })
        );
        assertEquals("", BeaconParser.parse(frame).ssid().orElse(null));
    }

    @Test
    @DisplayName("SSID with invalid UTF-8 bytes falls back to Latin-1 (no U+FFFD wall)")
    void invalidUtf8SsidFallsBackToLatin1() {
        // Bytes 0xC3 0x28 are an illegal UTF-8 sequence (0xC3 starts a
        // 2-byte sequence but 0x28 is not a valid continuation). A naive
        // new String(body, UTF_8) replaces both with U+FFFD and the user
        // sees "??" / "��" in the table; the Latin-1 fallback gives us
        // "A(" instead, which preserves both the byte count and lets the
        // user distinguish two different broken SSIDs from each other.
        byte[] frame = beacon(
                bssid("11:22:33:44:55:66"),
                ie(InformationElement.ID_SSID, new byte[] {
                        (byte) 0xC3, 0x28, (byte) 0xA1, (byte) 0xC0
                })
        );
        BeaconBody body = BeaconParser.parse(frame);
        assertTrue(body.ssid().isPresent());
        String ssid = body.ssid().get();
        assertEquals(4, ssid.length(), "Latin-1 fallback must preserve byte count");
        // U+FFFD never appears: the whole point of the fallback.
        for (int i = 0; i < ssid.length(); i++) {
            assertFalse(ssid.charAt(i) == '\uFFFD',
                    "Latin-1 fallback must not introduce U+FFFD; got: " + ssid);
        }
    }

    @Test
    @DisplayName("SSID with valid UTF-8 multibyte chars decodes normally")
    void validUtf8SsidDecodesAsUtf8() {
        // "Café" in UTF-8: C-a-f-0xC3 0xA9. Strict decoder must succeed.
        byte[] frame = beacon(
                bssid("11:22:33:44:55:66"),
                ie(InformationElement.ID_SSID, new byte[] {
                        'C', 'a', 'f', (byte) 0xC3, (byte) 0xA9
                })
        );
        BeaconBody body = BeaconParser.parse(frame);
        assertEquals("Caf\u00e9", body.ssid().orElse(null));
    }

    @Test
    @DisplayName("Frame too short to be a management frame is rejected")
    void rejectsShortFrame() {
        assertFalse(BeaconParser.isBeaconOrProbeResp(new byte[] { 0x00 }));
        assertFalse(BeaconParser.isBeaconOrProbeResp(null));
        assertEquals(BeaconBody.empty(), BeaconParser.parse(null));
    }

    // --- fixture builders -------------------------------------------------

    /**
     * Build a minimum-viable Beacon frame: 24-byte MAC header (with the
     * given BSSID at Address 3), 12 fixed body bytes (zeroed - we don't
     * care about timestamp / interval / capability for these tests),
     * then the supplied IEs concatenated.
     */
    private static byte[] beacon(byte[] bssid, byte[]... ies) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        // Frame Control: type=mgmt(0), subtype=beacon(8) -> fc0 = 0x80.
        out.write(0x80);
        out.write(0x00); // FC second byte
        out.write(new byte[] { 0, 0 }, 0, 2); // Duration
        out.write(new byte[] {                 // Address 1 (broadcast)
                (byte) 0xff, (byte) 0xff, (byte) 0xff,
                (byte) 0xff, (byte) 0xff, (byte) 0xff }, 0, 6);
        out.write(bssid, 0, 6);                // Address 2 (source AP)
        out.write(bssid, 0, 6);                // Address 3 (BSSID)
        out.write(new byte[] { 0, 0 }, 0, 2); // Sequence Control
        out.write(new byte[12], 0, 12);       // Fixed body fields
        for (byte[] ie : ies) out.write(ie, 0, ie.length);
        return out.toByteArray();
    }

    private static byte[] ie(int id, byte[] body) {
        byte[] out = new byte[2 + body.length];
        out[0] = (byte) id;
        out[1] = (byte) body.length;
        System.arraycopy(body, 0, out, 2, body.length);
        return out;
    }

    private static byte[] bssid(String mac) {
        String[] parts = mac.split(":");
        byte[] out = new byte[6];
        for (int i = 0; i < 6; i++) {
            out[i] = (byte) Integer.parseInt(parts[i], 16);
        }
        return out;
    }

    private static void assertArrayEqualsAscii(String expected, byte[] actual) {
        assertEquals(expected, new String(actual, StandardCharsets.US_ASCII));
    }
}
