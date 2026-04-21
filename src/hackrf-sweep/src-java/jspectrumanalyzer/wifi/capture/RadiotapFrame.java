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
 *
 * <p>{@code channelMhz} is the centre frequency the user / scheduler
 * <em>requested</em> for this capture session and is stamped onto every
 * frame so downstream consumers know the intent. {@code observedChannelMhz}
 * is what the radio actually reported for this specific frame via the
 * radiotap CHANNEL field; it can differ from the request when the OS
 * holds the adapter on a different channel (the bug the WlanHelper
 * tune fixes). 0 means the radiotap header omitted the field.
 */
public record RadiotapFrame(long timestampNs, int channelMhz,
                            int observedChannelMhz,
                            int rssiDbm, int rateMbps, byte[] frame) {
    public RadiotapFrame {
        Objects.requireNonNull(frame, "frame");
    }

    /** Backwards-compatible constructor for tests that pre-date the observed-channel field. */
    public RadiotapFrame(long timestampNs, int channelMhz, int rssiDbm, int rateMbps, byte[] frame) {
        this(timestampNs, channelMhz, 0, rssiDbm, rateMbps, frame);
    }
}
