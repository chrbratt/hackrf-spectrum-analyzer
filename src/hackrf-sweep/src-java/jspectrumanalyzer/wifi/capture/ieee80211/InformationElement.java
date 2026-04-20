package jspectrumanalyzer.wifi.capture.ieee80211;

/**
 * One IEEE 802.11 Information Element (IE) - the tag-length-value
 * primitive that beacons, probe responses and association frames use to
 * carry every optional capability the standard defines (SSID, supported
 * rates, HT/VHT/HE operation, BSS Load, Country, RSN, vendor-specific,
 * ...).
 *
 * <p>The IE on-the-wire format is dead simple:
 * <pre>
 *   +-------+--------+----------------+
 *   |  ID   | Length |  Body (Length) |
 *   |  u8   |   u8   |     bytes      |
 *   +-------+--------+----------------+
 * </pre>
 *
 * <p>This record is intentionally lightweight: just the parsed id plus
 * a slice of the original capture buffer. We share the underlying
 * byte array (no copy) because parsing happens on the capture polling
 * thread and per-frame allocation pressure adds up fast on a busy
 * 80 MHz channel - hundreds of beacons + thousands of data frames per
 * second. The {@code body} fields point into the same array slice the
 * polling loop already owns; callers that retain the IE past the
 * current frame must copy {@link #body()} themselves.
 */
public record InformationElement(int id, byte[] body) {

    /** SSID element (IE id 0). Body length 0..32. */
    public static final int ID_SSID = 0;
    /** BSS Load element (IE id 11). Body is exactly 5 bytes. */
    public static final int ID_BSS_LOAD = 11;
    /** Country element (IE id 7). */
    public static final int ID_COUNTRY = 7;
    /** HT Capabilities (IE id 45). */
    public static final int ID_HT_CAPABILITIES = 45;
    /** HT Operation (IE id 61). */
    public static final int ID_HT_OPERATION = 61;
    /** VHT Capabilities (IE id 191). */
    public static final int ID_VHT_CAPABILITIES = 191;
    /** VHT Operation (IE id 192). */
    public static final int ID_VHT_OPERATION = 192;
    /** Vendor specific (IE id 221). */
    public static final int ID_VENDOR = 221;
    /** Element-ID Extension marker (IE id 255 = HE / EHT IEs). */
    public static final int ID_EXTENSION = 255;

    /**
     * Length of the body in bytes. Stored separately from the array
     * length to make the intent explicit even though they always match
     * for IEs minted by {@link IeWalker}.
     */
    public int length() { return body == null ? 0 : body.length; }
}
