package jspectrumanalyzer.wifi.capture;

import java.util.Objects;

/**
 * One captured 802.11 frame plus the radiotap-supplied PHY metadata.
 *
 * <p>Phase 2 starts with a small extracted header (channel, RSSI, rate)
 * because that is all the per-BSSID-airtime, hidden-SSID and roaming
 * features need. The full radiotap blob is kept in {@code frame} so
 * later passes (e.g. EHT-specific fields, A-MPDU stats) can re-parse
 * it without forcing a wire-format change here.
 *
 * <p>{@code timestampNs} is monotonic wall-clock ns from the host's
 * monotonic clock - NOT the radiotap timestamp, which can wrap and
 * which we cross-correlate with the SDR timeline upstream. {@code frame}
 * starts at the 802.11 MAC header (radiotap header already stripped).
 */
public record RadiotapFrame(long timestampNs, int channelMhz,
                            int rssiDbm, int rateMbps, byte[] frame) {
    public RadiotapFrame {
        Objects.requireNonNull(frame, "frame");
    }
}
