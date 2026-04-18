package hackrfsweep;

import java.util.Arrays;
import java.util.List;

import com.sun.jna.Callback;
import com.sun.jna.Library;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;
import com.sun.jna.Structure.FieldOrder;
import com.sun.jna.ptr.DoubleByReference;
import com.sun.jna.ptr.FloatByReference;

/**
 * Direct-mapped JNA bindings for the hackrf-sweep DLL.
 *
 * <p>Mirrors {@code hackrf_sweep.h} exactly. Field order in
 * {@link DeviceInfo} matches the C struct layout (every field is naturally
 * 4-byte aligned so JNA needs no manual padding).
 */
public class HackrfSweepLibrary implements Library {

    public interface hackrf_sweep_lib_start__fft_power_callback_callback extends Callback {
        void apply(byte full_sweep_done, int bins, DoubleByReference freqStart,
                   float fft_bin_Hz, FloatByReference powerdBm);
    }

    /**
     * JNA-mapped {@code hackrf_sweep_device_info_t}.
     * <p>Sized at exactly 112 bytes: serial[40] + board_name[64] +
     * usb_board_id (uint32) + board_id (uint32).
     */
    @FieldOrder({"serial", "board_name", "usb_board_id", "board_id"})
    public static class DeviceInfo extends Structure {
        public byte[] serial = new byte[40];
        public byte[] board_name = new byte[64];
        public int usb_board_id;
        public int board_id;

        public DeviceInfo() { super(); }
        public DeviceInfo(Pointer p) { super(p); }

        public static class ByReference extends DeviceInfo
                implements Structure.ByReference {}

        /** Read a NUL-terminated ASCII byte array as a Java String. */
        private static String cstring(byte[] raw) {
            int len = 0;
            while (len < raw.length && raw[len] != 0) len++;
            return new String(raw, 0, len, java.nio.charset.StandardCharsets.US_ASCII);
        }

        public String serialString() { return cstring(serial); }
        public String boardNameString() { return cstring(board_name); }
    }

    /**
     * Start a blocking sweep. The {@code serial} parameter selects which
     * HackRF to open; pass {@code null} or empty string to use the first
     * available device.
     *
     * <p>Original signature: {@code int hackrf_sweep_lib_start(...,
     * const char* serial)}.
     */
    public static native int hackrf_sweep_lib_start(
            HackrfSweepLibrary.hackrf_sweep_lib_start__fft_power_callback_callback fft_power_callback,
            int freq_min,
            int freq_max,
            int fft_bin_width,
            int num_samples,
            int lna_gain,
            int vga_gain,
            int antennaPowerEnable,
            int enableAntennaLNA,
            String serial);

    public static native void hackrf_sweep_lib_stop();

    /**
     * Enumerate connected HackRF devices.
     *
     * <p>JNA direct mapping (which {@link com.sun.jna.Native#register} uses)
     * does not accept {@code Structure[]} as an argument, so the buffer is
     * passed as a raw {@link Pointer}. Callers should allocate it via
     * {@code (DeviceInfo[]) new DeviceInfo().toArray(max_entries)} and pass
     * the first element's pointer - {@link com.sun.jna.Structure#toArray}
     * guarantees a contiguous block laid out exactly like the C-side array.
     *
     * @param out_entries pointer to the first {@link DeviceInfo} of a
     *                    contiguous block of {@code max_entries} entries,
     *                    or {@link Pointer#NULL} when {@code max_entries == 0}
     *                    (useful for sizing).
     * @param max_entries capacity of the buffer.
     * @return total number of devices visible to libhackrf, or {@code -1} on
     *         libhackrf failure. May exceed {@code max_entries} when the
     *         buffer was too small.
     */
    public static native int hackrf_sweep_lib_list_devices(Pointer out_entries,
                                                           int max_entries);

    /**
     * @return {@code 1} if a device is currently held open by
     *         {@link #hackrf_sweep_lib_start}, otherwise {@code 0}. Always
     *         zero-initialises {@code out_info} so callers can rely on the
     *         struct contents.
     */
    public static native int hackrf_sweep_lib_get_opened_info(DeviceInfo out_info);

    /** Convenience for tests / introspection. */
    public static List<String> exportedSymbols() {
        return Arrays.asList(
                "hackrf_sweep_lib_start",
                "hackrf_sweep_lib_stop",
                "hackrf_sweep_lib_list_devices",
                "hackrf_sweep_lib_get_opened_info");
    }
}
