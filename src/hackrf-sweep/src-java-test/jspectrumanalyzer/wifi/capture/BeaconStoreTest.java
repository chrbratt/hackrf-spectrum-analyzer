package jspectrumanalyzer.wifi.capture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import jspectrumanalyzer.wifi.capture.ieee80211.InformationElement;

/**
 * The {@link BeaconStore} sits between the capture polling thread and
 * the FX UI, so the contracts that matter are: a probe response with
 * a real SSID resolves a hidden BSSID, the lookup is case-insensitive
 * for the input BSSID (UI may pass either case), and reset clears
 * everything.
 */
class BeaconStoreTest {

    @Test
    @DisplayName("Probe response with real SSID resolves a hidden BSSID")
    void probeRespResolvesHiddenSsid() {
        BeaconStore store = new BeaconStore();
        store.accept(frame(probeResp(
                bssid("11:22:33:aa:bb:cc"),
                ie(InformationElement.ID_SSID, "ResolvedNet".getBytes(StandardCharsets.UTF_8))
        )));
        assertEquals("ResolvedNet",
                store.discoveredSsid("11:22:33:AA:BB:CC").orElse(null));
        assertEquals(1, store.discoveredCount());
    }

    @Test
    @DisplayName("Hidden-only beacons leave the store empty (no placeholder pollution)")
    void hiddenBeaconDoesNotPollute() {
        BeaconStore store = new BeaconStore();
        store.accept(frame(beacon(
                bssid("11:22:33:aa:bb:cc"),
                ie(InformationElement.ID_SSID, new byte[0])
        )));
        assertEquals(0, store.discoveredCount());
        assertFalse(store.discoveredSsid("11:22:33:aa:bb:cc").isPresent());
    }

    @Test
    @DisplayName("Reset clears all observations")
    void resetClearsState() {
        BeaconStore store = new BeaconStore();
        store.accept(frame(beacon(
                bssid("aa:bb:cc:dd:ee:ff"),
                ie(InformationElement.ID_SSID, "Foo".getBytes(StandardCharsets.UTF_8)),
                ie(InformationElement.ID_BSS_LOAD, new byte[] { 5, 0, 0x40, 0, 0 })
        )));
        assertTrue(store.discoveredSsid("aa:bb:cc:dd:ee:ff").isPresent());
        assertTrue(store.bssLoad("aa:bb:cc:dd:ee:ff").isPresent());

        store.reset();
        assertEquals(0, store.discoveredCount());
        assertFalse(store.discoveredSsid("aa:bb:cc:dd:ee:ff").isPresent());
        assertFalse(store.bssLoad("aa:bb:cc:dd:ee:ff").isPresent());
    }

    @Test
    @DisplayName("Non-mgmt frames are ignored")
    void ignoresDataFrames() {
        BeaconStore store = new BeaconStore();
        // A data frame: type=2 (data), subtype=0 -> fc0 = 0x08.
        byte[] dataFrame = new byte[64];
        dataFrame[0] = 0x08;
        store.accept(frame(dataFrame));
        assertEquals(0, store.discoveredCount());
    }

    // --- fixtures (mirror BeaconParserTest) ---

    private static RadiotapFrame frame(byte[] mac) {
        return new RadiotapFrame(0L, 2412, -50, 0, mac);
    }

    private static byte[] beacon(byte[] bssid, byte[]... ies) {
        return mgmtFrame(0x80, bssid, ies);
    }

    private static byte[] probeResp(byte[] bssid, byte[]... ies) {
        // subtype 5 (probe response) -> fc0 = 0x50.
        return mgmtFrame(0x50, bssid, ies);
    }

    private static byte[] mgmtFrame(int fc0, byte[] bssid, byte[]... ies) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(fc0);
        out.write(0x00);
        out.write(new byte[] { 0, 0 }, 0, 2);
        out.write(new byte[] {
                (byte) 0xff, (byte) 0xff, (byte) 0xff,
                (byte) 0xff, (byte) 0xff, (byte) 0xff }, 0, 6);
        out.write(bssid, 0, 6);
        out.write(bssid, 0, 6);
        out.write(new byte[] { 0, 0 }, 0, 2);
        out.write(new byte[12], 0, 12);
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
}
