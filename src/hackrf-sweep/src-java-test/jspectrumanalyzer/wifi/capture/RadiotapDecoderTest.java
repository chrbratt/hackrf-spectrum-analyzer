package jspectrumanalyzer.wifi.capture;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import org.junit.jupiter.api.Test;

/**
 * Focused unit tests for {@link RadiotapDecoder}'s presence-walk. The
 * radiotap layout is fiddly enough (variable per-field lengths, optional
 * extension words, alignment requirements) that regression tests are
 * cheaper than another debugging session.
 */
class RadiotapDecoderTest {

    /**
     * Hand-craft a radiotap header advertising RATE + ANT_SIGNAL fields
     * followed by a minimal beacon frame. Returns the assembled byte
     * stream so we can feed {@link RadiotapDecoder#decode} directly.
     */
    private static byte[] buildFrame(int rateRaw, int rssiSigned) {
        // Header layout:
        //   it_version (1) + pad (1) + it_len (2) + present (4)
        //   + RATE (1) + ANT_SIGNAL (1)
        // = 10 bytes header, followed by 24 bytes of MAC.
        // Present mask: bit 2 (RATE) + bit 5 (ANT_SIGNAL) = 0x24.
        int presentMask = (1 << 2) | (1 << 5);
        int rtLen = 10;
        int macLen = 24;
        byte[] out = new byte[rtLen + macLen];
        ByteBuffer bb = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN);
        bb.put((byte) 0);                  // version
        bb.put((byte) 0);                  // pad
        bb.putShort((short) rtLen);
        bb.putInt(presentMask);
        bb.put((byte) rateRaw);            // RATE in 500 kbps units
        bb.put((byte) rssiSigned);         // ANT_SIGNAL signed dBm
        // 802.11 beacon header: FC=0x80 (mgmt/beacon).
        out[rtLen] = (byte) 0x80;
        return out;
    }

    @Test
    void rateExtractedAndRoundedToWholeMbps() {
        // 12 raw -> 12 * 0.5 = 6 Mbps.
        RadiotapDecoder.Decoded d = RadiotapDecoder.decode(buildFrame(12, -50));
        assertEquals(6, d.rateMbps());
        assertEquals(-50, d.rssiDbm());
    }

    @Test
    void rateRoundsHalvesUp() {
        // 11 raw -> 5.5 Mbps -> rounds to 6 Mbps so the legacy 5.5 Mbps
        // OFDM rate surfaces as a friendly integer in the UI.
        RadiotapDecoder.Decoded d = RadiotapDecoder.decode(buildFrame(11, -60));
        assertEquals(6, d.rateMbps());
    }

    @Test
    void channelMhzExtractedFromChannelField() {
        // Header layout: version+pad+len+present, then CHANNEL (4 bytes:
        // u16 freq + u16 flags), then ANT_SIGNAL (1 byte). CHANNEL needs
        // 2-byte alignment - present mask 0x28 = bit 3 (CHANNEL) + bit 5
        // (ANT_SIGNAL) so we exercise the channel-skip path too.
        int presentMask = (1 << 3) | (1 << 5);
        int rtLen = 13; // 8 header + 4 channel + 1 ant_signal
        byte[] out = new byte[rtLen + 24];
        ByteBuffer bb = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN);
        bb.put((byte) 0);
        bb.put((byte) 0);
        bb.putShort((short) rtLen);
        bb.putInt(presentMask);
        bb.putShort((short) 5590);          // CHANNEL freq MHz (UNII-2C ch 118)
        bb.putShort((short) 0x0140);        // CHANNEL flags (5 GHz + OFDM)
        bb.put((byte) -55);                 // ANT_SIGNAL
        out[rtLen] = (byte) 0x80;           // beacon FC
        RadiotapDecoder.Decoded d = RadiotapDecoder.decode(out);
        assertEquals(5590, d.channelMhz());
        assertEquals(-55, d.rssiDbm());
    }

    @Test
    void channelMhzZeroWhenChannelBitOff() {
        // Reuse the rate-only fixture, which omits the CHANNEL bit.
        RadiotapDecoder.Decoded d = RadiotapDecoder.decode(buildFrame(12, -50));
        assertEquals(0, d.channelMhz());
    }

    @Test
    void rateZeroWhenPresentBitOff() {
        // Build a header with ONLY ANT_SIGNAL, no RATE bit set, so the
        // walker must not advance into the rate slot and must report 0.
        int presentMask = 1 << 5; // ANT_SIGNAL only
        int rtLen = 9;
        byte[] out = new byte[rtLen + 24];
        ByteBuffer bb = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN);
        bb.put((byte) 0);
        bb.put((byte) 0);
        bb.putShort((short) rtLen);
        bb.putInt(presentMask);
        bb.put((byte) -45);                 // ANT_SIGNAL
        out[rtLen] = (byte) 0x80;           // beacon FC
        RadiotapDecoder.Decoded d = RadiotapDecoder.decode(out);
        assertEquals(0, d.rateMbps());
        assertEquals(-45, d.rssiDbm());     // RSSI must still parse correctly
    }
}
