package jspectrumanalyzer.wifi;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.sun.jna.Memory;
import com.sun.jna.Pointer;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.ptr.PointerByReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import jspectrumanalyzer.wifi.win32.Wlanapi;

/**
 * Native Wi-Fi API (wlanapi.dll) implementation of {@link WifiScanner} for
 * Windows.
 *
 * <p>The class owns a single {@code WLAN_HANDLE} and caches the GUID byte
 * array for every Wi-Fi interface returned by {@code WlanEnumInterfaces}.
 * Re-enumerating on every scan would survive USB Wi-Fi adapter hot-plug, but
 * it would also hit the WLAN service every second; users are expected to
 * keep their adapter constant for a session, so we re-enumerate only when
 * {@link #requestScan()} is called and every BSS list comes back empty.
 *
 * <h3>Why hand-rolled struct parsing instead of JNA Structure subclasses?</h3>
 * The {@code WLAN_BSS_ENTRY} layout is fixed (360 bytes) but contains 6 fields
 * we never use plus a variable-length IE blob outside the fixed area.
 * Subclassing {@link com.sun.jna.Structure} for it would mean declaring all
 * 16 fields with the right padding hints; reading the four offsets we
 * actually need (SSID, BSSID, RSSI, centre frequency) directly from the
 * returned {@link Pointer} is shorter and keeps every offset in one place.
 *
 * <h3>Thread-safety</h3>
 * MSDN guarantees that all {@code Wlan*} entry points are safe to call from
 * multiple threads. The instance is still {@code synchronized} around the
 * pointer fetch / free pair to keep the leak window deterministic.
 */
public final class WindowsWlanScanner implements WifiScanner {

    private static final Logger LOG = LoggerFactory.getLogger(WindowsWlanScanner.class);

    /** Size of {@code WLAN_INTERFACE_INFO} in bytes (GUID + 256 WCHAR + DWORD). */
    private static final int INTERFACE_INFO_SIZE = 16 + 256 * 2 + 4;
    /** Offset of the GUID inside {@code WLAN_INTERFACE_INFO}. */
    private static final int INTERFACE_GUID_OFFSET = 0;

    /** Fixed size of one {@code WLAN_BSS_ENTRY} (variable IE data lives outside). */
    private static final int BSS_ENTRY_FIXED_SIZE = 360;

    /** Byte offsets within {@code WLAN_BSS_ENTRY} for the fields we care about. */
    private static final int OFF_SSID_LEN = 0;
    private static final int OFF_SSID_DATA = 4;
    private static final int OFF_BSSID = 40;
    private static final int OFF_RSSI = 56;
    private static final int OFF_CENTER_FREQ_KHZ = 92;
    private static final int OFF_PHY_TYPE = 52;

    private final Object lock = new Object();
    private Pointer handle;
    private List<byte[]> interfaceGuids = Collections.emptyList();
    private boolean closed = false;

    /**
     * Open a Native Wi-Fi session. Throws if {@code wlanapi.dll} is missing,
     * the WLAN service is stopped, or the user lacks privilege - the caller
     * (typically {@link WifiScannerFactory}) catches and falls back to the
     * no-op scanner.
     */
    public WindowsWlanScanner() {
        IntByReference negotiated = new IntByReference();
        PointerByReference handleRef = new PointerByReference();
        int rc = Wlanapi.Holder.INSTANCE.WlanOpenHandle(
                Wlanapi.CLIENT_VERSION_VISTA_AND_LATER, null, negotiated, handleRef);
        if (rc != Wlanapi.ERROR_SUCCESS) {
            throw new IllegalStateException("WlanOpenHandle failed, rc=" + rc);
        }
        this.handle = handleRef.getValue();
        try {
            this.interfaceGuids = enumerateInterfaceGuids();
            LOG.info("Native Wi-Fi scanner opened with {} interface(s).", interfaceGuids.size());
        } catch (RuntimeException ex) {
            // If enumeration failed we still have a handle to close before
            // bubbling the failure out.
            close();
            throw ex;
        }
    }

    @Override
    public boolean isAvailable() {
        synchronized (lock) {
            return !closed && handle != null && !interfaceGuids.isEmpty();
        }
    }

    @Override
    public List<WifiAccessPoint> scan() {
        synchronized (lock) {
            if (closed || handle == null) return Collections.emptyList();
            if (interfaceGuids.isEmpty()) return Collections.emptyList();
            List<WifiAccessPoint> out = new ArrayList<>();
            for (byte[] guid : interfaceGuids) {
                try {
                    out.addAll(scanInterface(guid));
                } catch (RuntimeException ex) {
                    LOG.warn("WlanGetNetworkBssList failed for one interface", ex);
                }
            }
            return out;
        }
    }

    @Override
    public void requestScan() {
        synchronized (lock) {
            if (closed || handle == null) return;
            for (byte[] guid : interfaceGuids) {
                Memory guidMem = new Memory(16);
                guidMem.write(0, guid, 0, 16);
                int rc = Wlanapi.Holder.INSTANCE.WlanScan(handle, guidMem, null, null, null);
                if (rc != Wlanapi.ERROR_SUCCESS) {
                    LOG.debug("WlanScan returned rc={} for one interface", rc);
                }
                // guidMem is freed by JNA when it goes out of scope.
            }
        }
    }

    @Override
    public void close() {
        synchronized (lock) {
            if (closed) return;
            closed = true;
            if (handle != null) {
                int rc = Wlanapi.Holder.INSTANCE.WlanCloseHandle(handle, null);
                if (rc != Wlanapi.ERROR_SUCCESS) {
                    LOG.debug("WlanCloseHandle returned rc={}", rc);
                }
                handle = null;
            }
            interfaceGuids = Collections.emptyList();
        }
    }

    /** Caller holds {@link #lock}. */
    private List<byte[]> enumerateInterfaceGuids() {
        PointerByReference listRef = new PointerByReference();
        int rc = Wlanapi.Holder.INSTANCE.WlanEnumInterfaces(handle, null, listRef);
        if (rc != Wlanapi.ERROR_SUCCESS) {
            throw new IllegalStateException("WlanEnumInterfaces failed, rc=" + rc);
        }
        Pointer listPtr = listRef.getValue();
        if (listPtr == null) return Collections.emptyList();
        try {
            int numItems = listPtr.getInt(0);
            // Skip the 4-byte dwIndex after dwNumberOfItems before the array.
            long base = 8;
            List<byte[]> guids = new ArrayList<>(numItems);
            for (int i = 0; i < numItems; i++) {
                long entryStart = base + (long) i * INTERFACE_INFO_SIZE;
                byte[] guid = listPtr.getByteArray(entryStart + INTERFACE_GUID_OFFSET, 16);
                guids.add(guid);
            }
            return guids;
        } finally {
            Wlanapi.Holder.INSTANCE.WlanFreeMemory(listPtr);
        }
    }

    /** Caller holds {@link #lock}. */
    private List<WifiAccessPoint> scanInterface(byte[] guid) {
        Memory guidMem = new Memory(16);
        guidMem.write(0, guid, 0, 16);
        PointerByReference bssListRef = new PointerByReference();
        int rc = Wlanapi.Holder.INSTANCE.WlanGetNetworkBssList(
                handle, guidMem, null, Wlanapi.DOT11_BSS_TYPE_ANY, 0, null, bssListRef);
        if (rc != Wlanapi.ERROR_SUCCESS) {
            return Collections.emptyList();
        }
        Pointer listPtr = bssListRef.getValue();
        if (listPtr == null) return Collections.emptyList();
        try {
            int numEntries = listPtr.getInt(4);
            // Header (dwTotalSize + dwNumberOfItems) is 8 bytes; entries follow.
            long base = 8;
            List<WifiAccessPoint> out = new ArrayList<>(numEntries);
            for (int i = 0; i < numEntries; i++) {
                long entryStart = base + (long) i * BSS_ENTRY_FIXED_SIZE;
                WifiAccessPoint ap = parseEntry(listPtr, entryStart);
                if (ap != null) out.add(ap);
            }
            return out;
        } finally {
            Wlanapi.Holder.INSTANCE.WlanFreeMemory(listPtr);
        }
    }

    /** Returns null on out-of-bounds frequencies (junk reports we ignore). */
    private static WifiAccessPoint parseEntry(Pointer listPtr, long entryStart) {
        int ssidLen = listPtr.getInt(entryStart + OFF_SSID_LEN);
        // Clamp to the 32-byte spec maximum; defends against rare drivers
        // that report SSID length > 32 which would otherwise overrun the
        // fixed buffer.
        ssidLen = Math.max(0, Math.min(32, ssidLen));
        byte[] ssidBytes = listPtr.getByteArray(entryStart + OFF_SSID_DATA, ssidLen);
        String ssid = new String(ssidBytes, StandardCharsets.UTF_8);

        byte[] bssidBytes = listPtr.getByteArray(entryStart + OFF_BSSID, 6);
        String bssid = formatMac(bssidBytes);

        int rssiDbm = listPtr.getInt(entryStart + OFF_RSSI);
        long centerFreqKhz = Integer.toUnsignedLong(
                listPtr.getInt(entryStart + OFF_CENTER_FREQ_KHZ));
        int phyTypeId = listPtr.getInt(entryStart + OFF_PHY_TYPE);
        String phyType = phyTypeName(phyTypeId);

        WifiAccessPoint ap = WifiAccessPoint.fromKhz(bssid, ssid, rssiDbm, centerFreqKhz, phyType);
        // Sanity filter: reject reports outside any Wi-Fi band; these are
        // almost always stale entries the driver has not aged out yet.
        if (ap.band() == null) return null;
        return ap;
    }

    private static String formatMac(byte[] mac) {
        StringBuilder sb = new StringBuilder(17);
        for (int i = 0; i < mac.length; i++) {
            if (i > 0) sb.append(':');
            sb.append(String.format("%02x", mac[i] & 0xff));
        }
        return sb.toString();
    }

    /** Map a {@code DOT11_PHY_TYPE} enum value to a friendly 802.11 marketing name. */
    private static String phyTypeName(int phyTypeId) {
        return switch (phyTypeId) {
            case 4 -> "802.11a";
            case 5 -> "802.11b";
            case 6 -> "802.11g";
            case 7 -> "802.11n";
            case 8 -> "802.11ac";
            case 9 -> "802.11ad";
            case 10 -> "802.11ax";
            case 11 -> "802.11be";
            default -> null;
        };
    }
}
