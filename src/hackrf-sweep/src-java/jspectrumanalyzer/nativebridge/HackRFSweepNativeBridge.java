package jspectrumanalyzer.nativebridge;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.sun.jna.CallbackThreadInitializer;
import com.sun.jna.Native;
import com.sun.jna.NativeLibrary;
import com.sun.jna.Platform;
import com.sun.jna.Pointer;
import com.sun.jna.ptr.DoubleByReference;
import com.sun.jna.ptr.FloatByReference;

import hackrfsweep.HackrfSweepLibrary;
import hackrfsweep.HackrfSweepLibrary.DeviceInfo;
import hackrfsweep.HackrfSweepLibrary.hackrf_sweep_lib_start__fft_power_callback_callback;

/**
 * Java-friendly facade in front of the JNA bindings.
 *
 * <p>All entry points are static (the underlying DLL only supports one sweep
 * at a time anyway). The class is loaded once during JVM start-up; if the DLL
 * is missing or the new symbols introduced for HackRF Pro support are not
 * present, the static initialiser fails fast with a clear error.
 */
public class HackRFSweepNativeBridge {

    public static final String          JNA_LIBRARY_NAME = "hackrf-sweep";
    public static final NativeLibrary   JNA_NATIVE_LIB;

    /**
     * Hard upper bound on the device list. libusb in practice never reports
     * more than a handful of HackRFs on the same host (one bus = up to 127
     * devices in theory, but the only realistic case is "a few").
     */
    private static final int MAX_DEVICES = 32;

    static {
        // Force JNA to load jnidispatch.dll from the unpacked location so the
        // bundled copy is used instead of the (slower) jar-extracted one.
        String pathPrefix = "./" + Platform.RESOURCE_PREFIX + "/";
        System.setProperty("jna.boot.library.path", pathPrefix);
        System.setProperty("jna.nosys", "true");

        NativeLibrary.addSearchPath(JNA_LIBRARY_NAME, pathPrefix);
        JNA_NATIVE_LIB = NativeLibrary.getInstance(JNA_LIBRARY_NAME);
        Native.register(HackrfSweepLibrary.class, JNA_NATIVE_LIB);

        // Fail fast at startup (rather than at first device-list click) if the
        // DLL bundled with the install lacks the new HackRF Pro entry points.
        // Old MinGW DLLs that only export start/stop are no longer supported.
        for (String sym : HackrfSweepLibrary.exportedSymbols()) {
            try {
                JNA_NATIVE_LIB.getFunction(sym);
            } catch (UnsatisfiedLinkError e) {
                throw new UnsatisfiedLinkError(
                        "hackrf-sweep.dll is missing required export '" + sym
                        + "'. Rebuild it with `gradlew buildHackrfSweepDll` "
                        + "(see src/hackrf-sweep/native/BUILD_NATIVE.md).");
            }
        }
    }

    private HackRFSweepNativeBridge() { }

    /**
     * Start a blocking sweep on the device with the given {@code serial}.
     * Pass {@code null} or {@code ""} to open whatever HackRF the OS hands
     * libhackrf first (legacy behaviour).
     */
    public static synchronized void start(HackRFSweepDataCallback dataCallback,
                                          int freq_min_MHz,
                                          int freq_max_MHz,
                                          int fft_bin_width,
                                          int num_samples,
                                          int lna_gain,
                                          int vga_gain,
                                          boolean antennaPowerEnable,
                                          boolean internalLNA,
                                          String serial) {
        hackrf_sweep_lib_start__fft_power_callback_callback callback =
                new hackrf_sweep_lib_start__fft_power_callback_callback() {
                    @Override
                    public void apply(byte sweep_started, int bins,
                                      DoubleByReference freqStart,
                                      float fftBinWidth,
                                      FloatByReference powerdBm) {
                        double[] freqStartArr = bins == 0
                                ? null
                                : freqStart.getPointer().getDoubleArray(0, bins);
                        float[] powerArr = bins == 0
                                ? null
                                : powerdBm.getPointer().getFloatArray(0, bins);
                        dataCallback.newSpectrumData(sweep_started != 0,
                                freqStartArr, fftBinWidth, powerArr);
                    }
                };
        Native.setCallbackThreadInitializer(callback,
                new CallbackThreadInitializer(true));

        String openSerial = (serial == null) ? "" : serial;
        HackrfSweepLibrary.hackrf_sweep_lib_start(callback,
                freq_min_MHz, freq_max_MHz,
                fft_bin_width, num_samples,
                lna_gain, vga_gain,
                antennaPowerEnable ? 1 : 0,
                internalLNA ? 1 : 0,
                openSerial);
    }

    public static void stop() {
        HackrfSweepLibrary.hackrf_sweep_lib_stop();
    }

    /**
     * @return immutable snapshot of the HackRF devices currently visible to
     *         libhackrf. Empty list if none are connected.
     *
     * <p><b>Not synchronized on purpose.</b> The class lock is held for the
     * entire duration of {@link #start} (which blocks until the sweep ends),
     * so a synchronized list-call would deadlock the UI thread the moment a
     * sweep is in progress. The underlying libhackrf entry points
     * ({@code hackrf_init / hackrf_device_list / hackrf_exit}) are reentrant
     * and ref-count their state, so calling them while another device is
     * already open is safe.
     */
    public static List<HackRFDeviceInfo> listDevices() {
        // Probe the count first so we don't over-allocate. NULL pointer is
        // safe when capacity is zero - the native side honours that.
        int total = HackrfSweepLibrary.hackrf_sweep_lib_list_devices(Pointer.NULL, 0);
        if (total <= 0) {
            return Collections.emptyList();
        }
        int capacity = Math.min(total, MAX_DEVICES);

        // Structure.toArray() allocates one contiguous native block exactly
        // like the C-side array, which is what the DLL expects. Passing the
        // first element's pointer hands the whole block to the native side.
        DeviceInfo[] block = (DeviceInfo[]) new DeviceInfo().toArray(capacity);
        int written = HackrfSweepLibrary.hackrf_sweep_lib_list_devices(
                block[0].getPointer(), capacity);
        int count = Math.min(Math.max(written, 0), capacity);

        List<HackRFDeviceInfo> result = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            block[i].read();
            result.add(toPojo(block[i]));
        }
        return Collections.unmodifiableList(result);
    }

    /**
     * @return device currently opened by {@link #start}, or {@code null} when
     *         no sweep is active. The returned info includes the firmware
     *         board id (e.g. PRALINE = HackRF Pro), unlike entries from
     *         {@link #listDevices()}.
     *
     * <p><b>Not synchronized on purpose</b> - see {@link #listDevices()} for
     * why. The native side just memcpys a cached struct, so a torn read
     * (label flickering for one frame around start/stop) is the worst that
     * can happen and is preferable to freezing the UI.
     */
    public static HackRFDeviceInfo getOpenedInfo() {
        DeviceInfo info = new DeviceInfo();
        int populated = HackrfSweepLibrary.hackrf_sweep_lib_get_opened_info(info);
        if (populated == 0) {
            return null;
        }
        info.read();
        return toPojo(info);
    }

    private static HackRFDeviceInfo toPojo(DeviceInfo info) {
        return new HackRFDeviceInfo(
                info.serialString(),
                info.boardNameString(),
                info.usb_board_id & 0xFFFF,
                info.board_id & 0xFF);
    }
}
