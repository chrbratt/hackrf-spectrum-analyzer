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

    /** Result of decoding one captured frame. Fields are zero / null when unknown. */
    public record Decoded(int radiotapLen, int rssiDbm, int frameType,
                          int frameSubtype, String bssid) {}

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
            return new Decoded(0, 0, -1, -1, null);
        }
        int version = data[0] & 0xff;
        if (version != 0) {
            // Future revision; fall back to skipping the length field only.
            int len = readU16Le(data, 2);
            return new Decoded(len, 0, -1, -1, null);
        }
        int rtLen = readU16Le(data, 2);
        if (rtLen < 8 || rtLen > data.length) {
            return new Decoded(0, 0, -1, -1, null);
        }
        int presentFlags = readU32Le(data, 4);
        int rssi = extractRssi(data, presentFlags, rtLen);

        int frameStart = rtLen;
        if (frameStart + 2 > data.length) {
            return new Decoded(rtLen, rssi, -1, -1, null);
        }
        int fc0 = data[frameStart] & 0xff;
        int type = (fc0 >> 2) & 0x03;
        int subtype = (fc0 >> 4) & 0x0f;

        String bssid = null;
        // Address 1 sits at frame_start + 4 (after FC + Duration).
        if (frameStart + 10 <= data.length) {
            bssid = formatMac(data, frameStart + 4);
        }
        return new Decoded(rtLen, rssi, type, subtype, bssid);
    }

    /**
     * Walk the radiotap field layout up to the antenna-signal byte. We
     * only support the v0 "extended presence" with no extension words,
     * which covers every Npcap / libpcap capture today. Returns 0 when
     * the antenna-signal flag is not set.
     */
    private static int extractRssi(byte[] data, int presentFlags, int rtLen) {
        if ((presentFlags & RT_FLAG_ANT_SIGNAL) == 0) return 0;
        // First field starts at byte 8 (after version+pad+len+presentFlags).
        // Extension flags double the present field; skip if bit 31 is set.
        int cursor = 8;
        int flags = presentFlags;
        while ((flags & (1 << 31)) != 0) {
            if (cursor + 4 > rtLen) return 0;
            flags = readU32Le(data, cursor);
            cursor += 4;
        }
        if ((presentFlags & RT_FLAG_TSFT) != 0)    cursor = align(cursor, 8) + 8;
        if ((presentFlags & RT_FLAG_FLAGS) != 0)   cursor += 1;
        if ((presentFlags & RT_FLAG_RATE) != 0)    cursor += 1;
        if ((presentFlags & RT_FLAG_CHANNEL) != 0) cursor = align(cursor, 2) + 4;
        if ((presentFlags & RT_FLAG_FHSS) != 0)    cursor += 2;
        // Antenna signal is a signed 8-bit dBm; align is 1 (no padding).
        if (cursor >= rtLen) return 0;
        return data[cursor]; // already signed - radiotap dBm is int8
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
