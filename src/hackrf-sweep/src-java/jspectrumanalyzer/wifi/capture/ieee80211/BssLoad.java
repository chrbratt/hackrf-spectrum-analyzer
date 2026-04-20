package jspectrumanalyzer.wifi.capture.ieee80211;

/**
 * Parsed BSS Load Information Element (IEEE 802.11, IE id 11).
 *
 * <p>Beacon-advertised view of how busy the AP itself thinks its
 * channel is - much more reliable than spectrum-side estimates because
 * it comes from the AP's own MAC counters. Fields:
 * <ul>
 *   <li>{@link #stationCount()} - number of associated STAs.</li>
 *   <li>{@link #channelUtilizationPercent()} - 0-100 derived from the
 *       raw 0-255 utilization byte (the IE field's natural unit). 100
 *       means the AP saw the channel as busy 100% of the measurement
 *       window.</li>
 *   <li>{@link #availableAdmissionCapacity()} - WMM admission control
 *       headroom in 32 microseconds-per-second units. We carry it as
 *       an opaque u16 because almost no consumer cares about it.</li>
 * </ul>
 *
 * <p>Body layout, 5 bytes total:
 * <pre>
 *   +-------+-------+-------+-------+-------+
 *   | Stations LE   | Util  | AvailCap LE   |
 *   |    u16        |  u8   |     u16       |
 *   +-------+-------+-------+-------+-------+
 * </pre>
 */
public record BssLoad(int stationCount,
                      int channelUtilizationPercent,
                      int availableAdmissionCapacity) {

    /**
     * Decode a raw 5-byte BSS Load IE body. Returns {@code null} when
     * the body is not exactly 5 bytes long - the caller should treat
     * that the same as "AP did not advertise BSS Load this beacon".
     */
    public static BssLoad parse(byte[] body) {
        if (body == null || body.length != 5) return null;
        int stations = (body[0] & 0xff) | ((body[1] & 0xff) << 8);
        int rawUtil  = body[2] & 0xff;
        // The IEEE 802.11 spec reports utilization as a 0-255 value
        // ("integer percentage of time, normalised to 255, that the AP
        // sensed the medium busy"). Convert to a friendlier 0-100 with
        // rounding so the UI never shows 99.6%.
        int utilPct  = (int) Math.round(rawUtil * 100.0 / 255.0);
        int availCap = (body[3] & 0xff) | ((body[4] & 0xff) << 8);
        return new BssLoad(stations, utilPct, availCap);
    }
}
