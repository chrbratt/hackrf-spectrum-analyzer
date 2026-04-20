package jspectrumanalyzer.wifi.capture.ieee80211;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Iterates an IEEE 802.11 Information Element (IE) blob and emits one
 * {@link InformationElement} per tag. Used by {@link BeaconParser} to
 * walk the variable section of beacons and probe responses, but kept
 * separate so future callers (association frames, action frames,
 * Wi-Fi Direct probes) can reuse the exact same parser without
 * dragging beacon-specific assumptions along.
 *
 * <h2>Robustness</h2>
 * The parser is deliberately lenient: a truncated trailing IE simply
 * ends the iteration, malformed length bytes that would overflow the
 * buffer skip the offending element rather than throwing. Capture
 * data comes from the radio - in practice we will see truncated
 * frames (driver buffer wrap-arounds, bad CRC tolerated by the
 * monitor mode) and we want the rest of the beacon to still parse.
 *
 * <h2>Allocation</h2>
 * Every {@code InformationElement} carries a freshly allocated body
 * array (a copy of the slice). That extra copy is cheap relative to
 * the per-frame parsing work and keeps the contract simple: the IE is
 * safe to retain past the current capture buffer. This matters for
 * downstream consumers like {@link jspectrumanalyzer.wifi.capture.HiddenSsidResolver}
 * that store discovered SSIDs across frames.
 */
public final class IeWalker {

    private IeWalker() {}

    /**
     * Parse the IE list starting at {@code offset} into {@code data}
     * and continuing for at most {@code maxLen} bytes. Stops at the
     * first truncated element. Returns an unmodifiable list so
     * callers cannot accidentally mutate the parser output.
     */
    public static List<InformationElement> walk(byte[] data, int offset, int maxLen) {
        if (data == null || offset < 0 || maxLen <= 0) return Collections.emptyList();
        int end = Math.min(data.length, offset + maxLen);
        if (end - offset < 2) return Collections.emptyList();
        List<InformationElement> out = new ArrayList<>();
        int cursor = offset;
        while (cursor + 2 <= end) {
            int id = data[cursor] & 0xff;
            int len = data[cursor + 1] & 0xff;
            int bodyStart = cursor + 2;
            int bodyEnd = bodyStart + len;
            if (bodyEnd > end) {
                // Truncated trailing element - everything we have parsed
                // so far is still valid, just stop walking here.
                break;
            }
            byte[] body = new byte[len];
            System.arraycopy(data, bodyStart, body, 0, len);
            out.add(new InformationElement(id, body));
            cursor = bodyEnd;
        }
        return Collections.unmodifiableList(out);
    }
}
