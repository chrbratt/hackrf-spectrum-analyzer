package jspectrumanalyzer.wifi.capture;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Minimal radiotap + 802.11 frame-control decoder for the live capture
 * preview. We deliberately avoid {@code pcap4j-packetfactory-static}'s
 * full packet hierarchy here so a single object allocation per frame
 * does not bottleneck the polling thread on a busy 80 MHz channel.
 *
 * <p>Reads only the fields the UI needs:
 * <ul>
 *   <li>Radiotap header length (to skip past it).</li>
 *   <li>Antenna signal (RSSI) when the present-flags bit is set.</li>
 *   <li>802.11 frame-control type (mgmt / ctrl / data / ext) and the
 *       management-subtype byte (beacon / probe-req / probe-resp / ...).</li>
 * </ul>
 *
 * <p>Frame-control bytes 4-9 carry the BSSID (Address 1) for management
 * frames, which we expose via {@link #bssidOrNull} so the UI can show
 * "last beacon from XX:XX:XX...". Robust against truncated frames -
 * any out-of-bounds read returns sentinel values instead of throwing.
 */
public final class RadiotapDecoder {

    /**
     * Result of decoding one captured frame. Fields are zero / null when
     * unknown.
     *
     * <p>{@code rateMbps} is the PHY data rate the radio reported for this
     * frame in whole Mbps (radiotap stores 500 kbps units; we round to the
     * nearest Mbps). 0 means the radiotap header omitted the rate field
     * (some adapters do that for HT/VHT/HE frames where MCS lives in a
     * separate optional field). Treat 0 as "unknown", not "0 Mbps".
     *
     * <p>{@code channelMhz} is the centre frequency the radio reported it
     * was tuned to when it captured the frame, taken from the radiotap
     * CHANNEL field. 0 means the field was absent (some drivers omit it
     * for HT/VHT/HE frames in favour of the optional XCHANNEL field that
     * we do not parse). Lets the UI show "Observed channel" so the user
     * can verify the WlanHelper tune actually took effect.
     */
    public record Decoded(int radiotapLen, int rssiDbm, int rateMbps,
                          int frameType, int frameSubtype, int channelMhz,
                          String bssid) {}

    /** Frame Control "Type" values, per IEEE 802.11. */
    public static final int TYPE_MGMT = 0;
    public static final int TYPE_CTRL = 1;
    public static final int TYPE_DATA = 2;
    public static final int TYPE_EXT  = 3;

    /** Management subtypes we display individually; everything else is "other". */
    public static final int MGMT_ASSOC_REQ   = 0;
    public static final int MGMT_ASSOC_RESP  = 1;
    public static final int MGMT_PROBE_REQ   = 4;
    public static final int MGMT_PROBE_RESP  = 5;
    public static final int MGMT_BEACON      = 8;
    public static final int MGMT_DEAUTH      = 12;

    /** Radiotap "Antenna signal" present-flag bit (defined by the spec). */
    private static final int RT_FLAG_ANT_SIGNAL = 1 << 5;
    /** Bits we have to skip in the radiotap header before the antenna-signal byte. */
    private static final int RT_FLAG_TSFT      = 1 << 0; // u64
    private static final int RT_FLAG_FLAGS     = 1 << 1; // u8
    private static final int RT_FLAG_RATE      = 1 << 2; // u8
    private static final int RT_FLAG_CHANNEL   = 1 << 3; // u16+u16, 2-byte aligned
    private static final int RT_FLAG_FHSS      = 1 << 4; // u8+u8

    private RadiotapDecoder() {}

    /**
     * Decode the radiotap header + leading 802.11 fields of a raw capture
     * buffer. Returns a {@link Decoded} with sensible defaults
     * ({@code rssiDbm = 0}, {@code bssid = null}) when a field cannot be
     * read because the buffer is truncated or the radiotap version is
     * not 0 (the only one defined by the spec today).
     */
    public static Decoded decode(byte[] data) {
        if (data == null || data.length < 8) {
            return new Decoded(0, 0, 0, -1, -1, 0, null);
        }
        int version = data[0] & 0xff;
        if (version != 0) {
            // Future revision; fall back to skipping the length field only.
            int len = readU16Le(data, 2);
            return new Decoded(len, 0, 0, -1, -1, 0, null);
        }
        int rtLen = readU16Le(data, 2);
        if (rtLen < 8 || rtLen > data.length) {
            return new Decoded(0, 0, 0, -1, -1, 0, null);
        }
        int presentFlags = readU32Le(data, 4);
        // RSSI, rate and channel all share the same prefix walk; do it once.
        Fields f = extractFields(data, presentFlags, rtLen);

        int frameStart = rtLen;
        if (frameStart + 2 > data.length) {
            return new Decoded(rtLen, f.rssi, f.rateMbps, -1, -1, f.channelMhz, null);
        }
        int fc0 = data[frameStart] & 0xff;
        int type = (fc0 >> 2) & 0x03;
        int subtype = (fc0 >> 4) & 0x0f;

        String bssid = null;
        // Address 1 sits at frame_start + 4 (after FC + Duration).
        if (frameStart + 10 <= data.length) {
            bssid = formatMac(data, frameStart + 4);
        }
        return new Decoded(rtLen, f.rssi, f.rateMbps, type, subtype, f.channelMhz, bssid);
    }

    /** RSSI in dBm, rate in Mbps and channel MHz extracted from one radiotap walk. */
    private record Fields(int rssi, int rateMbps, int channelMhz) {}

    /**
     * Walk the radiotap presence-field layout once and pluck both the
     * antenna-signal (RSSI) and the data rate. Caller-side this matters
     * because the rate field sits before RSSI in the layout, so a
     * separate "extractRate" pass would re-walk the same bytes.
     *
     * <p>The radiotap rate field is a u8 in 500 kbps units; we round it
     * to the nearest whole Mbps. HT/VHT/HE rates do <em>not</em> live in
     * this field (they would be in MCS / VHT / HE_MU optional fields,
     * which we do not currently parse), so for modern PHYs the rate
     * cell will surface as 0 = "unknown". That is honest about the
     * limitation rather than silently making numbers up.
     */
    private static Fields extractFields(byte[] data, int presentFlags, int rtLen) {
        // First field starts at byte 8 (after version+pad+len+presentFlags).
        // Extension flags double the present field; skip if bit 31 is set.
        int cursor = 8;
        int flags = presentFlags;
        while ((flags & (1 << 31)) != 0) {
            if (cursor + 4 > rtLen) return new Fields(0, 0, 0);
            flags = readU32Le(data, cursor);
            cursor += 4;
        }
        if ((presentFlags & RT_FLAG_TSFT) != 0)  cursor = align(cursor, 8) + 8;
        if ((presentFlags & RT_FLAG_FLAGS) != 0) cursor += 1;
        int rateMbps = 0;
        if ((presentFlags & RT_FLAG_RATE) != 0) {
            if (cursor < rtLen) {
                int raw = data[cursor] & 0xff;       // u8, units of 500 kbps
                rateMbps = (raw + 1) / 2;            // round to nearest Mbps
            }
            cursor += 1;
        }
        int channelMhz = 0;
        if ((presentFlags & RT_FLAG_CHANNEL) != 0) {
            // Channel field: u16 frequency MHz + u16 flags, 2-byte aligned.
            // The frequency is the only field the UI uses today; flags
            // (CCK/OFDM/2GHz/5GHz/passive) duplicate info we already have.
            cursor = align(cursor, 2);
            if (cursor + 2 <= rtLen) channelMhz = readU16Le(data, cursor);
            cursor += 4;
        }
        if ((presentFlags & RT_FLAG_FHSS) != 0)    cursor += 2;
        int rssi = 0;
        if ((presentFlags & RT_FLAG_ANT_SIGNAL) != 0) {
            // Antenna signal is a signed 8-bit dBm; align is 1 (no padding).
            if (cursor < rtLen) rssi = data[cursor]; // already signed - radiotap dBm is int8
        }
        return new Fields(rssi, rateMbps, channelMhz);
    }

    private static int align(int offset, int alignment) {
        int rem = offset % alignment;
        return rem == 0 ? offset : offset + (alignment - rem);
    }

    private static int readU16Le(byte[] data, int offset) {
        return ByteBuffer.wrap(data, offset, 2).order(ByteOrder.LITTLE_ENDIAN).getShort() & 0xffff;
    }

    private static int readU32Le(byte[] data, int offset) {
        return ByteBuffer.wrap(data, offset, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
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
