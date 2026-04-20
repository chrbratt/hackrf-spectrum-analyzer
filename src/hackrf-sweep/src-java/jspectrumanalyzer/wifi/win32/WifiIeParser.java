package jspectrumanalyzer.wifi.win32;

/**
 * Tiny parser for the 802.11 Information Element (IE) blob attached to
 * each {@code WLAN_BSS_ENTRY} on Windows. We only extract the bits needed
 * to display channel widths in the AP marker overlay; everything else
 * (security, rates, capabilities) is already available from the
 * structured BSS fields the existing scanner reads.
 *
 * <h3>Why hand-rolled instead of pulling a library?</h3>
 * The IE format is trivial (1-byte ID, 1-byte length, length bytes payload)
 * and Windows already gives us the byte buffer. An external library would
 * mean a transitive dependency for ~50 lines of code that never changes.
 *
 * <h3>What we read</h3>
 * <ul>
 *   <li><b>HT Operation (ID 61, 802.11n)</b> - Bit 2 of byte[1] is the STA
 *       channel width (0 = 20 MHz, 1 = "any" i.e. 40 MHz).</li>
 *   <li><b>VHT Operation (ID 192, 802.11ac)</b> - Byte[0] is the VHT
 *       channel width: 0 = 20/40 (defer to HT), 1 = 80, 2 = 160,
 *       3 = 80+80 (we report 160 because the marker is centred on the
 *       reported primary).</li>
 *   <li><b>HE Operation (ID 255 ext 36, 802.11ax)</b> - When the
 *       "6 GHz Operation Information Present" bit is set, the inner
 *       sub-element carries a {@code Channel Width} field with the same
 *       0/1/2/3 mapping as VHT. Used so 6 GHz Wi-Fi 6E APs report their
 *       true 80 / 160 MHz width even though they have no VHT IE.</li>
 * </ul>
 *
 * <p>EHT (Wi-Fi 7, 320 MHz) is not parsed yet; an EHT-only AP will
 * therefore show as 80 / 160 MHz depending on its companion HE IE.
 * That is wrong but never overstates the width, which is the safer
 * direction for a marker overlay.
 */
public final class WifiIeParser {

    private static final int ID_HT_OPERATION = 61;
    private static final int ID_VHT_OPERATION = 192;
    private static final int ID_EXTENSION = 255;
    private static final int EXT_HE_OPERATION = 36;

    private WifiIeParser() {}

    /**
     * Aggregated view of the operating-channel info we care about for
     * marker placement and adjacent-channel footprint computation.
     *
     * <p>{@code bondedCenterMhz} is the RF centre of the full bonded
     * channel block (the actual midpoint of the spectrum the AP occupies),
     * derived from VHT/HE Operation IE seg0 or HT secondary-channel offset.
     * It is {@code 0} when no wider IE is present - the caller should then
     * fall back to the primary 20 MHz centre. {@code bandwidthMhz} is
     * always >= 20.
     */
    public record OperatingInfo(int bandwidthMhz, int bondedCenterMhz) {}

    /**
     * Walk the IE blob and return the widest advertised operating width.
     * Convenience wrapper around {@link #parse} for callers that do not
     * need the bonded-centre frequency.
     *
     * @param data   raw IE bytes returned by {@code WlanGetNetworkBssList}.
     * @param offset start of the IE region inside {@code data}.
     * @param length number of bytes to scan.
     */
    public static int operatingBandwidthMhz(byte[] data, int offset, int length) {
        return parse(data, offset, length, 0).bandwidthMhz();
    }

    /**
     * Parse the IE blob and return both the operating bandwidth and the
     * bonded-channel-block centre frequency.
     *
     * <p>The {@code primaryMhz} argument is the primary 20 MHz channel
     * centre (the value Windows reports in {@code ulChCenterFrequency}).
     * It is needed in two places: to compute the bonded centre for HT
     * 40 MHz APs (primary +/- 10 MHz depending on the secondary-channel
     * offset bits) and to pick the right band when converting VHT/HE
     * channel-number fields into RF MHz. Pass 0 if unknown - the
     * bonded-centre field will then be 0 and the caller can fall back
     * to the primary.
     *
     * <p>Never throws on malformed input; a truncated TLV simply ends the
     * walk and any unparsable sub-element is dropped silently. The widest
     * consistent advertisement wins (HE > VHT > HT) - the same rule the
     * old {@code operatingBandwidthMhz} method used.
     */
    public static OperatingInfo parse(byte[] data, int offset, int length, int primaryMhz) {
        if (data == null || length <= 0) return new OperatingInfo(20, 0);
        int htWidth = 20;
        int htBondedCenter = 0;
        int vhtWidth = 0;
        int vhtBondedCenter = 0;
        int heWidth = 0;
        int heBondedCenter = 0;
        int end = Math.min(data.length, offset + length);

        int i = offset;
        while (i + 2 <= end) {
            int id = data[i] & 0xff;
            int len = data[i + 1] & 0xff;
            int payload = i + 2;
            if (payload + len > end) break;

            switch (id) {
                case ID_HT_OPERATION -> {
                    if (len >= 2) {
                        // HT Operation IE byte 0 = primary channel number.
                        // byte 1 = HT info subset 1: bits 0-1 = secondary
                        // channel offset, bit 2 = STA channel width.
                        int subset1 = data[payload + 1] & 0xff;
                        boolean fortyMhz = ((subset1 >> 2) & 0x01) == 1;
                        htWidth = fortyMhz ? 40 : 20;
                        if (fortyMhz && primaryMhz > 0) {
                            int offsetCode = subset1 & 0x03;
                            // 1 = secondary above primary => bonded centre
                            // is primary + 10 MHz. 3 = secondary below =>
                            // primary - 10 MHz. 0 / 2 = no bonded info,
                            // leave centre unset.
                            if (offsetCode == 1) htBondedCenter = primaryMhz + 10;
                            else if (offsetCode == 3) htBondedCenter = primaryMhz - 10;
                        }
                    }
                }
                case ID_VHT_OPERATION -> {
                    if (len >= 3) {
                        // VHT Operation Information layout:
                        //   byte 0: Channel Width (0/1/2/3)
                        //   byte 1: Channel Center Frequency Segment 0
                        //   byte 2: Channel Center Frequency Segment 1
                        int width = data[payload] & 0xff;
                        vhtWidth = vhtWidthCodeToMhz(width);
                        int seg0 = data[payload + 1] & 0xff;
                        if (vhtWidth > 0 && seg0 > 0) {
                            // VHT only operates in 5 GHz; convert seg0
                            // channel# to MHz with the standard mapping.
                            vhtBondedCenter = 5000 + seg0 * 5;
                        }
                    }
                }
                case ID_EXTENSION -> {
                    if (len >= 1) {
                        int extId = data[payload] & 0xff;
                        if (extId == EXT_HE_OPERATION) {
                            int[] he = parseHeOperation(data, payload, len, primaryMhz);
                            heWidth = he[0];
                            heBondedCenter = he[1];
                        }
                    }
                }
                default -> { /* ignore */ }
            }
            i = payload + len;
        }

        // Pick the widest advertisement. The bonded centre is taken from
        // whichever IE supplied that width so the centre always matches
        // the bandwidth value we are returning. Falls back to 0 (caller
        // uses primary) when the widest IE did not provide a centre.
        int bestWidth = 20;
        int bestCenter = 0;
        if (htWidth > bestWidth) { bestWidth = htWidth; bestCenter = htBondedCenter; }
        if (vhtWidth > bestWidth) { bestWidth = vhtWidth; bestCenter = vhtBondedCenter; }
        if (heWidth > bestWidth) { bestWidth = heWidth; bestCenter = heBondedCenter; }
        return new OperatingInfo(bestWidth, bestCenter);
    }

    private static int vhtWidthCodeToMhz(int code) {
        return switch (code) {
            case 1 -> 80;
            // Codes 2 and 3 (160 MHz contiguous and 80+80) both occupy the
            // same airtime; treat them identically for the marker overlay.
            case 2, 3 -> 160;
            // Code 0 means "see HT operation"; we keep the HT-derived width
            // by returning 0, which is filtered out via the Math.max above.
            default -> 0;
        };
    }

    /**
     * The HE Operation IE layout is:
     * <pre>
     *   ext id (1)
     *   HE Operation Parameters (3)
     *   BSS Color Information (1)
     *   Basic HE-MCS And NSS Set (2)
     *   [ VHT Operation Information (3) ]   if VHT present
     *   [ Co-Hosted BSSID Indicator (1)  ]   if Co-Hosted BSSID present
     *   [ 6 GHz Operation Information (5) ]  if 6 GHz present
     * </pre>
     * The presence of each optional sub-element is signalled by bits in the
     * HE Operation Parameters field. Returns a 2-element array
     * {@code [widthMhz, bondedCenterMhz]}; either may be 0 when the IE
     * does not carry the corresponding info.
     */
    private static int[] parseHeOperation(byte[] data, int payload, int len, int primaryMhz) {
        // Need at least extId + params(3) + color(1) + mcs(2) = 7 bytes
        if (len < 7) return new int[]{0, 0};
        int paramsStart = payload + 1;
        int p0 = data[paramsStart] & 0xff;
        int p1 = data[paramsStart + 1] & 0xff;
        int p2 = data[paramsStart + 2] & 0xff;
        int params = p0 | (p1 << 8) | (p2 << 16);
        boolean vhtPresent = ((params >> 14) & 0x1) == 1;
        boolean cohostPresent = ((params >> 15) & 0x1) == 1;
        boolean sixGhzPresent = ((params >> 16) & 0x1) == 1;

        int width = 0;
        int center = 0;
        int cursor = payload + 7;
        if (vhtPresent) {
            // VHT Operation Information sub-element is 3 bytes; same code
            // mapping. Carries bandwidth + seg0 channel# (5 GHz numbering).
            if (cursor + 3 > payload + len) return new int[]{0, 0};
            width = vhtWidthCodeToMhz(data[cursor] & 0xff);
            int seg0 = data[cursor + 1] & 0xff;
            if (width > 0 && seg0 > 0) center = 5000 + seg0 * 5;
            cursor += 3;
        }
        if (cohostPresent) {
            cursor += 1;
        }
        if (sixGhzPresent) {
            // 6 GHz Operation Information layout:
            //   PrimaryChannel(1) Control(1)
            //   CenterFreqSeg0(1) CenterFreqSeg1(1) MinRate(1)
            if (cursor + 5 > payload + len) return new int[]{Math.max(0, width), center};
            int control = data[cursor + 1] & 0xff;
            int widthCode = control & 0x03;
            int sixGhzWidth = vhtWidthCodeToMhz(widthCode);
            if (sixGhzWidth == 0 && widthCode == 0) {
                // Width code 0 in the 6 GHz block means 20 MHz outright,
                // unlike VHT where 0 means "defer to HT". When the AP is
                // 20 MHz the bonded centre IS the primary - use that.
                sixGhzWidth = 20;
                if (primaryMhz > 0) return new int[]{20, primaryMhz};
            }
            int seg0 = data[cursor + 2] & 0xff;
            if (sixGhzWidth > 0 && seg0 > 0) {
                // 6 GHz channel-number to MHz: 5950 + N*5.
                return new int[]{Math.max(width, sixGhzWidth), 5950 + seg0 * 5};
            }
            return new int[]{Math.max(width, sixGhzWidth), center};
        }
        return new int[]{width, center};
    }
}
