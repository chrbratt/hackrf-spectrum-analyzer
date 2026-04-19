package jspectrumanalyzer.wifi.win32;

import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.ptr.PointerByReference;

/**
 * Minimal JNA binding for the Windows {@code wlanapi.dll} (Native Wi-Fi API).
 * <p>
 * Only the entry points we actually need for AP discovery are mapped:
 * open / enum interfaces / trigger scan / get BSS list / free memory / close.
 * Everything is typed as {@link Pointer} (HANDLE / GUID / structs) and parsed
 * by hand in {@link jspectrumanalyzer.wifi.WindowsWlanScanner}; this avoids
 * the JNA Structure ceremony for variable-length Win32 lists and keeps all
 * struct-layout knowledge in one place.
 *
 * <p>Loading is deferred until the first call so the class can be referenced
 * (and the no-op fallback can be selected) on non-Windows platforms without
 * a {@code UnsatisfiedLinkError} at class init time. Use
 * {@link #isAvailable()} to probe support before invoking any function.
 */
public interface Wlanapi extends Library {

    /**
     * Holder pattern: the static {@code load} only runs the first time
     * {@link Holder#INSTANCE} is read, so non-Windows platforms can probe
     * {@link #isAvailable()} without ever attempting the load.
     */
    final class Holder {
        public static final Wlanapi INSTANCE = Native.load("wlanapi", Wlanapi.class);
        private Holder() {}
    }

    /**
     * @return {@code true} if {@code wlanapi.dll} could be loaded; {@code false}
     *         on any platform that does not ship it (Linux, macOS, headless
     *         Windows server SKUs that excluded the Wireless LAN feature).
     */
    static boolean isAvailable() {
        if (!System.getProperty("os.name", "").toLowerCase().contains("win")) {
            return false;
        }
        try {
            return Holder.INSTANCE != null;
        } catch (Throwable t) {
            return false;
        }
    }

    /** Use the Vista+ ABI; required on every supported Windows release. */
    int CLIENT_VERSION_VISTA_AND_LATER = 2;

    /** {@code dot11_BSS_type_any}: list every BSS regardless of mode. */
    int DOT11_BSS_TYPE_ANY = 3;

    /** Successful return value, identical to the Win32 {@code ERROR_SUCCESS}. */
    int ERROR_SUCCESS = 0;

    /**
     * @param dwClientVersion         Pass {@link #CLIENT_VERSION_VISTA_AND_LATER}.
     * @param pReserved               Must be {@code null}.
     * @param pdwNegotiatedVersion    Out: actually negotiated version.
     * @param phClientHandle          Out: opaque session handle to use in subsequent calls.
     * @return {@link #ERROR_SUCCESS} or a Win32 error code.
     */
    int WlanOpenHandle(int dwClientVersion, Pointer pReserved,
                       IntByReference pdwNegotiatedVersion,
                       PointerByReference phClientHandle);

    int WlanCloseHandle(Pointer hClientHandle, Pointer pReserved);

    /**
     * Enumerate every Wi-Fi adapter known to the WLAN service.
     *
     * @param ppInterfaceList Out: pointer to a {@code WLAN_INTERFACE_INFO_LIST}
     *                        we own and must free with {@link #WlanFreeMemory}.
     */
    int WlanEnumInterfaces(Pointer hClientHandle, Pointer pReserved,
                           PointerByReference ppInterfaceList);

    /**
     * Asynchronously trigger an active scan on the given interface. The function
     * returns immediately; results land in the BSS cache once Windows has
     * actually swept the channels (a few seconds typically).
     *
     * @param pInterfaceGuid Pointer to a 16-byte GUID identifying the adapter.
     * @param pDot11Ssid     Optional SSID to bias the scan; {@code null} = scan all.
     * @param pIeData        Optional probe-request IEs; {@code null} = use defaults.
     */
    int WlanScan(Pointer hClientHandle, Pointer pInterfaceGuid,
                 Pointer pDot11Ssid, Pointer pIeData, Pointer pReserved);

    /**
     * Read the current BSS list maintained by the WLAN service for the given
     * interface. The cache is updated by background scans and explicit
     * {@link #WlanScan} requests.
     *
     * @param dot11BssType        Use {@link #DOT11_BSS_TYPE_ANY}.
     * @param bSecurityEnabled    Win32 BOOL (4 bytes); pass 0 to include all.
     * @param ppWlanBssList       Out: pointer to a {@code WLAN_BSS_LIST} we own
     *                            and must free with {@link #WlanFreeMemory}.
     */
    int WlanGetNetworkBssList(Pointer hClientHandle, Pointer pInterfaceGuid,
                              Pointer pDot11Ssid, int dot11BssType,
                              int bSecurityEnabled, Pointer pReserved,
                              PointerByReference ppWlanBssList);

    /**
     * Release any pointer returned by a {@code Wlan*} function. Skipping this
     * is a memory leak that survives until the process exits, so wrap every
     * scan in try/finally.
     */
    void WlanFreeMemory(Pointer pMemory);
}
