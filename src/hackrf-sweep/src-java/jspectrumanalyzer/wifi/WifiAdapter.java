package jspectrumanalyzer.wifi;

/**
 * Lightweight description of a Wi-Fi interface known to the host OS.
 *
 * <p>The {@code guidHex} is the canonical lower-case hyphenated UUID
 * representation of the Windows {@code GUID} (e.g.
 * {@code 2debc390-c8bb-40b7-a714-349d32f4b2e6}) - the same format that
 * Npcap's {@code WlanHelper -i} prints. We use the string form everywhere
 * outside the scanner so model values, settings persistence and UI
 * comparisons can stay platform-agnostic.
 *
 * <p>{@code description} is the OS-supplied friendly name shown in
 * Network Connections (e.g. {@code Intel(R) Wi-Fi 7 BE200 320MHz}).
 * It is presented as-is in the UI; nothing parses it.
 */
public record WifiAdapter(String guidHex, String description) {

    /**
     * Sentinel meaning "scan every adapter the OS exposes". Used by the
     * UI's adapter combo as the always-present first entry so the user
     * can opt out of picking a specific radio.
     */
    public static final String ALL = "";

    public WifiAdapter {
        if (guidHex == null) guidHex = "";
        if (description == null) description = "";
    }
}
