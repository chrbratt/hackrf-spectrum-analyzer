package jspectrumanalyzer.wifi;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
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
import jspectrumanalyzer.wifi.win32.WifiIeParser;
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
    /**
     * Offset of {@code strInterfaceDescription} (256 WCHAR, NUL-terminated)
     * inside {@code WLAN_INTERFACE_INFO}. Sits immediately after the GUID.
     */
    private static final int INTERFACE_DESC_OFFSET = 16;
    private static final int INTERFACE_DESC_BYTES = 256 * 2;

    /** Fixed size of one {@code WLAN_BSS_ENTRY} (variable IE data lives outside). */
    private static final int BSS_ENTRY_FIXED_SIZE = 360;

    /** Byte offsets within {@code WLAN_BSS_ENTRY} for the fields we care about. */
    private static final int OFF_SSID_LEN = 0;
    private static final int OFF_SSID_DATA = 4;
    private static final int OFF_BSSID = 40;
    private static final int OFF_RSSI = 56;
    private static final int OFF_CENTER_FREQ_KHZ = 92;
    private static final int OFF_PHY_TYPE = 52;
    /**
     * Offsets of {@code ulIeOffset} / {@code ulIeSize} inside
     * {@code WLAN_BSS_ENTRY}. They live at the very end of the fixed
     * region: 96 (rate set start) + 4 (uRateSetLength) + 252 (USHORT[126]
     * supported rates) = 352. The IE blob itself sits {@code ulIeOffset}
     * bytes from the entry start (NOT from the end of the fixed region),
     * so we read absolute offsets into the BSS list pointer below.
     */
    private static final int OFF_IE_OFFSET = 352;
    private static final int OFF_IE_SIZE = 356;

    private final Object lock = new Object();
    private Pointer handle;
    private List<AdapterEntry> adapters = Collections.emptyList();
    /**
     * GUID hex of the adapter the user picked in the UI, or
     * {@link WifiAdapter#ALL} (="") to query every adapter. Mutated by
     * {@link #setSelectedAdapter(String)} on the FX thread; read on the
     * polling thread inside {@link #scan()} - protected by {@link #lock}.
     */
    private String selectedGuidHex = WifiAdapter.ALL;
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
            this.adapters = enumerateAdapters();
            LOG.info("Native Wi-Fi scanner opened with {} interface(s):", adapters.size());
            for (AdapterEntry a : adapters) {
                LOG.info("  - {} ({})", a.description, a.guidHex);
            }
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
            return !closed && handle != null && !adapters.isEmpty();
        }
    }

    @Override
    public List<WifiAdapter> listAdapters() {
        synchronized (lock) {
            List<WifiAdapter> out = new ArrayList<>(adapters.size());
            for (AdapterEntry a : adapters) {
                out.add(new WifiAdapter(a.guidHex, a.description));
            }
            return out;
        }
    }

    @Override
    public void setSelectedAdapter(String guidHex) {
        synchronized (lock) {
            this.selectedGuidHex = (guidHex == null) ? WifiAdapter.ALL : guidHex;
        }
    }

    @Override
    public List<WifiAccessPoint> scan() {
        synchronized (lock) {
            if (closed || handle == null) return Collections.emptyList();
            if (adapters.isEmpty()) return Collections.emptyList();
            List<WifiAccessPoint> out = new ArrayList<>();
            for (AdapterEntry a : adapters) {
                if (!matchesSelection(a)) continue;
                try {
                    out.addAll(scanInterface(a.guidBytes));
                } catch (RuntimeException ex) {
                    LOG.warn("WlanGetNetworkBssList failed for adapter {}", a.description, ex);
                }
            }
            return out;
        }
    }

    @Override
    public void requestScan() {
        synchronized (lock) {
            if (closed || handle == null) return;
            for (AdapterEntry a : adapters) {
                if (!matchesSelection(a)) continue;
                Memory guidMem = new Memory(16);
                guidMem.write(0, a.guidBytes, 0, 16);
                int rc = Wlanapi.Holder.INSTANCE.WlanScan(handle, guidMem, null, null, null);
                if (rc != Wlanapi.ERROR_SUCCESS) {
                    LOG.debug("WlanScan returned rc={} for {}", rc, a.description);
                }
                // guidMem is freed by JNA when it goes out of scope.
            }
        }
    }

    /**
     * "" / null means "all adapters"; anything else is matched
     * case-insensitively against the canonical GUID string. Caller holds
     * {@link #lock}.
     */
    private boolean matchesSelection(AdapterEntry a) {
        if (selectedGuidHex == null || selectedGuidHex.isBlank()) return true;
        return selectedGuidHex.equalsIgnoreCase(a.guidHex);
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
            adapters = Collections.emptyList();
        }
    }

    /** Caller holds {@link #lock}. */
    private List<AdapterEntry> enumerateAdapters() {
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
            List<AdapterEntry> result = new ArrayList<>(numItems);
            for (int i = 0; i < numItems; i++) {
                long entryStart = base + (long) i * INTERFACE_INFO_SIZE;
                byte[] guid = listPtr.getByteArray(entryStart + INTERFACE_GUID_OFFSET, 16);
                String desc = readUtf16String(listPtr,
                        entryStart + INTERFACE_DESC_OFFSET, INTERFACE_DESC_BYTES);
                result.add(new AdapterEntry(guid, guidToString(guid), desc));
            }
            return result;
        } finally {
            Wlanapi.Holder.INSTANCE.WlanFreeMemory(listPtr);
        }
    }

    /**
     * Read a UTF-16LE NUL-terminated WCHAR string starting at the given
     * absolute offset. Caps at {@code maxBytes} so a missing terminator
     * never reads past the struct.
     */
    private static String readUtf16String(Pointer ptr, long offset, int maxBytes) {
        byte[] raw = ptr.getByteArray(offset, maxBytes);
        // Find NUL terminator (two zero bytes on a WCHAR boundary).
        int end = raw.length;
        for (int i = 0; i + 1 < raw.length; i += 2) {
            if (raw[i] == 0 && raw[i + 1] == 0) { end = i; break; }
        }
        return new String(raw, 0, end, StandardCharsets.UTF_16LE);
    }

    /**
     * Format a Windows {@code GUID} byte layout as a canonical
     * lower-case hyphenated UUID. Matches the format printed by
     * Npcap's {@code WlanHelper -i} so the user can map the picker
     * entries back to the OS view they already know.
     *
     * <p>Layout: Data1 (4 bytes LE DWORD), Data2 (2 LE), Data3 (2 LE),
     * Data4 (8 BE).
     */
    private static String guidToString(byte[] g) {
        ByteBuffer le = ByteBuffer.wrap(g).order(ByteOrder.LITTLE_ENDIAN);
        long d1 = Integer.toUnsignedLong(le.getInt());
        int d2 = Short.toUnsignedInt(le.getShort());
        int d3 = Short.toUnsignedInt(le.getShort());
        StringBuilder sb = new StringBuilder(36);
        sb.append(String.format("%08x-%04x-%04x-", d1, d2, d3));
        sb.append(String.format("%02x%02x", g[8] & 0xff, g[9] & 0xff));
        sb.append('-');
        for (int i = 10; i < 16; i++) sb.append(String.format("%02x", g[i] & 0xff));
        return sb.toString();
    }

    /**
     * Internal pairing of a Wi-Fi adapter's raw GUID bytes (passed back
     * to the native API) with the precomputed canonical hex form (used
     * for UI lookups) and OS-supplied description. Immutable; replaced
     * wholesale every time {@link #enumerateAdapters()} runs.
     */
    private static final class AdapterEntry {
        final byte[] guidBytes;
        final String guidHex;
        final String description;

        AdapterEntry(byte[] guidBytes, String guidHex, String description) {
            this.guidBytes = guidBytes;
            this.guidHex = guidHex;
            this.description = description;
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

        int primaryMhz = (int) Math.round(centerFreqKhz / 1000.0);
        WifiIeParser.OperatingInfo op = readOperatingInfo(listPtr, entryStart, primaryMhz);

        WifiAccessPoint ap = WifiAccessPoint.fromKhz(
                bssid, ssid, rssiDbm, centerFreqKhz, phyType,
                op.bandwidthMhz(), op.bondedCenterMhz());
        // Sanity filter: reject reports outside any Wi-Fi band; these are
        // almost always stale entries the driver has not aged out yet.
        if (ap.band() == null) return null;
        return ap;
    }

    /**
     * Pull the IE blob out of the BSS entry and ask {@link WifiIeParser}
     * for the operating channel width and bonded-channel centre frequency.
     * Defaults to {@code (20 MHz, 0)} on any error (truncated buffer,
     * missing HT/VHT/HE IE) so the marker overlay stays sane even when
     * the driver returns garbage; the {@code 0} bonded centre is the
     * documented signal for "fall back to primary".
     */
    private static WifiIeParser.OperatingInfo readOperatingInfo(
            Pointer listPtr, long entryStart, int primaryMhz) {
        try {
            long ieOffset = Integer.toUnsignedLong(listPtr.getInt(entryStart + OFF_IE_OFFSET));
            long ieSize = Integer.toUnsignedLong(listPtr.getInt(entryStart + OFF_IE_SIZE));
            // Defensive caps - a malformed driver report could otherwise
            // ask us to read megabytes from random memory. Real IE blobs
            // are well under 1 KB.
            if (ieSize == 0 || ieSize > 4096) return new WifiIeParser.OperatingInfo(20, 0);
            byte[] ieData = listPtr.getByteArray(entryStart + ieOffset, (int) ieSize);
            return WifiIeParser.parse(ieData, 0, ieData.length, primaryMhz);
        } catch (RuntimeException ex) {
            LOG.debug("IE parse failed; defaulting to 20 MHz", ex);
            return new WifiIeParser.OperatingInfo(20, 0);
        }
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
