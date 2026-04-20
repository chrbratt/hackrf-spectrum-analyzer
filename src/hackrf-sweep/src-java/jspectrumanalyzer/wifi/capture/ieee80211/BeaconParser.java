package jspectrumanalyzer.wifi.capture.ieee80211;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

/**
 * Decodes the body of an IEEE 802.11 Beacon or Probe Response
 * management frame into the small set of fields the Phase-2 UI cares
 * about today (SSID, BSS Load).
 *
 * <h2>Frame layout</h2>
 * <p>The capture pipeline strips the radiotap header before the frame
 * reaches us, so byte 0 is the 802.11 MAC header. The MAC header is
 * 24 bytes (FrameControl + Duration + Address1 + Address2 + Address3
 * + SequenceControl). The management body that follows starts with
 * 12 fixed bytes (Timestamp + BeaconInterval + Capability) and then a
 * stream of TLV-formatted Information Elements.
 *
 * <pre>
 *   byte 0           : Frame Control (2 bytes)
 *   byte 2           : Duration / ID (2 bytes)
 *   byte 4           : Address 1     (6 bytes, broadcast for beacons)
 *   byte 10          : Address 2     (6 bytes, source AP)
 *   byte 16          : Address 3     (6 bytes, BSSID)
 *   byte 22          : Sequence Ctrl (2 bytes)
 *   byte 24          : Timestamp     (8 bytes) - fixed body start
 *   byte 32          : BeaconInterval (2 bytes)
 *   byte 34          : Capability    (2 bytes)
 *   byte 36 onwards  : Information Elements (TLV stream)
 * </pre>
 *
 * <p>Values borrowed from {@link jspectrumanalyzer.wifi.capture.RadiotapDecoder}
 * for symmetry with the existing capture-stats code path:
 * <ul>
 *   <li>{@link RadiotapDecoder#TYPE_MGMT} for the frame type.</li>
 *   <li>{@link RadiotapDecoder#MGMT_BEACON} / {@code MGMT_PROBE_RESP}
 *       for the subtypes that carry a usable body.</li>
 * </ul>
 */
public final class BeaconParser {

    /** Offset of the BSSID inside a management frame (Address 3). */
    public static final int BSSID_OFFSET = 16;
    /** Bytes of MAC header + fixed body fields before the IE stream. */
    public static final int IE_OFFSET = 36;

    private BeaconParser() {}

    /**
     * True if the supplied raw 802.11 frame is a Beacon or Probe
     * Response - the only two subtypes whose body layout this parser
     * understands. Caller is expected to have already stripped the
     * radiotap header (mirroring the
     * {@link jspectrumanalyzer.wifi.capture.Pcap4jMonitorCapture}
     * contract).
     */
    public static boolean isBeaconOrProbeResp(byte[] mac) {
        if (mac == null || mac.length < 1) return false;
        int fc0 = mac[0] & 0xff;
        int type = (fc0 >> 2) & 0x03;
        int subtype = (fc0 >> 4) & 0x0f;
        if (type != 0 /* TYPE_MGMT */) return false;
        return subtype == 8 /* MGMT_BEACON */
            || subtype == 5 /* MGMT_PROBE_RESP */;
    }

    /**
     * Extract the BSSID (Address 3) from a management frame as the
     * familiar lowercase colon-separated MAC. Returns {@code null}
     * when the frame is too short to contain Address 3.
     */
    public static String bssidOf(byte[] mac) {
        if (mac == null || mac.length < BSSID_OFFSET + 6) return null;
        return formatMac(mac, BSSID_OFFSET);
    }

    /**
     * Parse the IE stream of a Beacon or Probe Response and return
     * the subset we care about. Always returns a non-null
     * {@link BeaconBody} - missing IEs surface as
     * {@link Optional#empty()} rather than nulls so the call site can
     * use {@code .ifPresent(...)} without intermediate guards.
     *
     * <p>The frame is treated as opaque past the IE stream: we walk
     * until {@link IeWalker} runs out of bytes, then synthesise the
     * record. SSID IE id 0 is the only IE that can <em>legitimately</em>
     * have length 0 (hidden networks); we represent that as
     * {@code Optional.of("")} to distinguish "AP advertised a hidden
     * SSID" from "no SSID IE at all".
     */
    public static BeaconBody parse(byte[] mac) {
        if (mac == null || mac.length <= IE_OFFSET) return BeaconBody.empty();
        List<InformationElement> ies =
                IeWalker.walk(mac, IE_OFFSET, mac.length - IE_OFFSET);
        Optional<String> ssid = Optional.empty();
        Optional<BssLoad> bssLoad = Optional.empty();
        for (InformationElement ie : ies) {
            switch (ie.id()) {
                case InformationElement.ID_SSID -> ssid = Optional.of(decodeSsid(ie.body()));
                case InformationElement.ID_BSS_LOAD -> {
                    BssLoad parsed = BssLoad.parse(ie.body());
                    if (parsed != null) bssLoad = Optional.of(parsed);
                }
                default -> {
                    // Other IEs (rates, HT/VHT/HE caps, RSN, vendor) are
                    // intentionally ignored at this layer. Future phases
                    // can extend the switch without changing the parser
                    // contract.
                }
            }
        }
        return new BeaconBody(ssid, bssLoad);
    }

    /**
     * Decode an SSID IE body. The spec says the body is "0..32 octets
     * of UTF-8" (technically not always UTF-8 in the wild, but every
     * sane consumer treats it as such). An all-zero body of any length
     * is the alternate hidden-network encoding some vendors use; we
     * fold that into the empty string so the resolver can treat all
     * "hidden" advertisements identically.
     */
    private static String decodeSsid(byte[] body) {
        if (body == null || body.length == 0) return "";
        boolean allZero = true;
        for (byte b : body) {
            if (b != 0) { allZero = false; break; }
        }
        if (allZero) return "";
        return new String(body, StandardCharsets.UTF_8);
    }

    private static String formatMac(byte[] data, int offset) {
        StringBuilder sb = new StringBuilder(17);
        for (int i = 0; i < 6; i++) {
            if (i > 0) sb.append(':');
            int b = data[offset + i] & 0xff;
            if (b < 0x10) sb.append('0');
            sb.append(Integer.toHexString(b));
        }
        return sb.toString();
    }
}
