package jspectrumanalyzer.wifi.capture;

import java.util.HashMap;
import java.util.Map;

/**
 * Per-capture-session running tallies: total frames, frames-per-type,
 * and a few well-known management subtypes the UI shows individually.
 *
 * <p>Single-threaded by contract - the {@link Pcap4jMonitorCapture}
 * polling thread is the only writer. The UI reads
 * {@link #snapshot()} on the JavaFX thread; that method takes the
 * monitor so the snapshot is consistent with no torn updates. We
 * accept a cheap synchronisation cost rather than the complexity of
 * a per-counter {@code AtomicLong} grid because the polling thread
 * already does heavy work per frame and the contention is one short
 * critical section per UI tick (4 Hz).
 */
public final class CaptureStats {

    /**
     * Immutable snapshot returned by {@link #snapshot()}. UI can read
     * fields without further locking.
     */
    public record Snapshot(long total, long mgmt, long ctrl, long data, long ext,
                           long beacons, long probeReq, long probeResp,
                           long deauth, Map<String, Long> rssiByBssid) {}

    private long total;
    private long mgmt;
    private long ctrl;
    private long data;
    private long ext;
    private long beacons;
    private long probeReq;
    private long probeResp;
    private long deauth;

    /**
     * Last-seen RSSI per BSSID (managment frames only). Bounded to a
     * sane size so a busy office on probe-flood night does not OOM the
     * UI - the oldest entries get dropped on insert.
     */
    private static final int MAX_BSSIDS = 64;
    private final Map<String, Long> lastRssi = new HashMap<>();

    /** Reset all counters to 0. Called on Start. */
    public synchronized void reset() {
        total = mgmt = ctrl = data = ext = 0;
        beacons = probeReq = probeResp = deauth = 0;
        lastRssi.clear();
    }

    /**
     * Update tallies for one captured frame. Reads the 802.11 frame
     * control byte directly from the supplied (radiotap-stripped) MAC
     * payload to keep the per-frame work to a single byte read +
     * branch.
     */
    public synchronized void accept(RadiotapFrame f) {
        total++;
        byte[] mac = f.frame();
        if (mac.length < 1) return;
        int fc0 = mac[0] & 0xff;
        int type = (fc0 >> 2) & 0x03;
        int subtype = (fc0 >> 4) & 0x0f;
        switch (type) {
            case RadiotapDecoder.TYPE_MGMT -> {
                mgmt++;
                switch (subtype) {
                    case RadiotapDecoder.MGMT_BEACON      -> beacons++;
                    case RadiotapDecoder.MGMT_PROBE_REQ   -> probeReq++;
                    case RadiotapDecoder.MGMT_PROBE_RESP  -> probeResp++;
                    case RadiotapDecoder.MGMT_DEAUTH      -> deauth++;
                    default -> { /* other mgmt - lumped into mgmt total */ }
                }
                // BSSID for mgmt frames is at MAC offset 16 (Address 3).
                if (mac.length >= 22 && f.rssiDbm() != 0) {
                    String bssid = formatMac(mac, 16);
                    if (lastRssi.size() < MAX_BSSIDS || lastRssi.containsKey(bssid)) {
                        lastRssi.put(bssid, (long) f.rssiDbm());
                    }
                }
            }
            case RadiotapDecoder.TYPE_CTRL -> ctrl++;
            case RadiotapDecoder.TYPE_DATA -> data++;
            case RadiotapDecoder.TYPE_EXT  -> ext++;
            default -> { /* malformed, ignore */ }
        }
    }

    public synchronized Snapshot snapshot() {
        return new Snapshot(total, mgmt, ctrl, data, ext,
                beacons, probeReq, probeResp, deauth,
                Map.copyOf(lastRssi));
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
