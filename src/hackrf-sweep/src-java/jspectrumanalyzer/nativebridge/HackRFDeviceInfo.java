package jspectrumanalyzer.nativebridge;

import java.util.Objects;

/**
 * Immutable description of a HackRF device, returned by
 * {@link HackRFSweepNativeBridge#listDevices()} and
 * {@link HackRFSweepNativeBridge#getOpenedInfo()}.
 *
 * <p>Mirrors the C struct {@code hackrf_sweep_device_info_t}. Java users
 * should treat instances as value objects (equals/hashCode are keyed on
 * the serial because that is what uniquely identifies a physical
 * device).
 */
public final class HackRFDeviceInfo {

    /**
     * Sentinel value for {@link #boardId()} when the firmware-level board id
     * has not been read yet (e.g. before the device was opened).
     * Matches {@code HACKRF_SWEEP_BOARD_ID_UNKNOWN} in the C header.
     */
    public static final int BOARD_ID_UNKNOWN = 0xFF;

    private final String serial;
    private final String boardName;
    private final int usbBoardId;
    private final int boardId;

    public HackRFDeviceInfo(String serial, String boardName, int usbBoardId, int boardId) {
        this.serial = serial == null ? "" : serial;
        this.boardName = boardName == null ? "Unknown" : boardName;
        this.usbBoardId = usbBoardId;
        this.boardId = boardId;
    }

    /** Lower-case hex ASCII, possibly empty if libhackrf could not read it. */
    public String serial() { return serial; }

    /** Friendly composed name, e.g. "HackRF One" or "Praline (HackRF Pro)". */
    public String boardName() { return boardName; }

    /** USB descriptor product id (e.g. {@code 0x6089} for HackRF One/Pro). */
    public int usbBoardId() { return usbBoardId; }

    /**
     * Firmware-level board id read via {@code hackrf_board_id_read()}.
     * {@link #BOARD_ID_UNKNOWN} if not yet known (i.e. this entry came
     * from {@code listDevices()} rather than {@code getOpenedInfo()}).
     */
    public int boardId() { return boardId; }

    /**
     * Stable label suited for a dropdown: {@code "<board name> (<serial-suffix>)"}
     * with the serial suffix shortened to 8 chars so the combo stays narrow.
     * Falls back to just the board name when no serial is known.
     */
    public String displayLabel() {
        if (serial.isEmpty()) {
            return boardName;
        }
        String tail = serial.length() > 8
                ? serial.substring(serial.length() - 8)
                : serial;
        return boardName + " (" + tail + ")";
    }

    @Override public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof HackRFDeviceInfo)) return false;
        HackRFDeviceInfo that = (HackRFDeviceInfo) o;
        return Objects.equals(serial, that.serial);
    }

    @Override public int hashCode() {
        return Objects.hashCode(serial);
    }

    @Override public String toString() {
        return "HackRFDeviceInfo{serial='" + serial + "', boardName='" + boardName
                + "', usbBoardId=0x" + Integer.toHexString(usbBoardId)
                + ", boardId=" + boardId + "}";
    }
}
