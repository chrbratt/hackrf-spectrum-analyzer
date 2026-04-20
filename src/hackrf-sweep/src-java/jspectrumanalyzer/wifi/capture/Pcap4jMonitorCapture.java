package jspectrumanalyzer.wifi.capture;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import org.pcap4j.core.PcapHandle;
import org.pcap4j.core.PcapNativeException;
import org.pcap4j.core.PcapNetworkInterface;
import org.pcap4j.core.PcapNetworkInterface.PromiscuousMode;
import org.pcap4j.core.Pcaps;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * pcap4j-backed {@link MonitorModeCapture}. Opens the chosen Wi-Fi
 * adapter with {@code rfmon=true} and pumps raw 802.11+radiotap frames
 * onto a callback as fast as the OS delivers them.
 *
 * <h2>Runtime expectations</h2>
 * <ul>
 *   <li>Npcap installed with the "Support raw 802.11 traffic" check-box
 *       enabled - without it the {@link #start} call fails with
 *       "rfmon mode is not supported" from libpcap.</li>
 *   <li>The host process running as <b>administrator</b> - non-elevated
 *       processes get "Access denied" when they try to put the NIC into
 *       monitor mode.</li>
 *   <li>The selected adapter's driver supports NDIS native 802.11
 *       monitor mode (verify with
 *       {@code netsh wlan show wirelesscapabilities}).</li>
 * </ul>
 *
 * <h2>Threading</h2>
 * One daemon thread per active capture. The thread loops on
 * {@code PcapHandle.getNextRawPacket} and forwards every frame to the
 * caller-supplied {@code onFrame} callback. The callback runs on the
 * polling thread; UI consumers must marshal back via
 * {@code Platform.runLater}. {@link #stop} flips an atomic flag, calls
 * {@code handle.breakLoop()} and waits up to 500 ms for the thread to
 * exit before releasing the handle.
 */
public final class Pcap4jMonitorCapture implements MonitorModeCapture {

    private static final Logger LOG = LoggerFactory.getLogger(Pcap4jMonitorCapture.class);

    /**
     * Capture buffer size. 65 535 covers the largest 802.11 A-MPDU
     * Windows surfaces (~64 KB). Smaller would silently truncate
     * aggregated frames - we have no way to recover the missing bytes.
     */
    private static final int SNAPLEN = 65_535;
    /**
     * pcap read timeout in ms. Determines how often the polling thread
     * wakes up to check the stop flag when no packets arrive. 100 ms is
     * a balance between latency on stop and idle CPU.
     */
    private static final int READ_TIMEOUT_MS = 100;

    private PcapHandle handle;
    private Thread pollThread;
    private final AtomicBoolean running = new AtomicBoolean(false);

    /**
     * Cheap probe: try to enumerate adapters. Catches the
     * {@link UnsatisfiedLinkError} that fires when wpcap.dll / Npcap is
     * missing so the UI can hide the "Start capture" button instead of
     * crashing on first paint.
     */
    @Override
    public boolean isAvailable() {
        try {
            Pcaps.findAllDevs();
            return true;
        } catch (UnsatisfiedLinkError | PcapNativeException ex) {
            LOG.debug("pcap4j unavailable: {}", ex.getMessage());
            return false;
        }
    }

    @Override
    public List<MonitorAdapter> listAdapters() {
        List<MonitorAdapter> out = new ArrayList<>();
        try {
            for (PcapNetworkInterface nif : Pcaps.findAllDevs()) {
                if (nif == null || nif.getName() == null) continue;
                // Filter out non-wireless devices when the description
                // makes that obvious. Loopback and tunnels have no SSID
                // and would just clutter the picker. Anything else stays
                // in - the Wi-Fi NIC gets vendor-specific names like
                // "Intel(R) Wi-Fi 6 AX201 160MHz" that we cannot reliably
                // pattern-match against.
                String desc = nif.getDescription();
                if (desc != null
                        && (desc.toLowerCase().contains("loopback")
                            || desc.toLowerCase().contains("vmware")
                            || desc.toLowerCase().contains("virtual"))) {
                    continue;
                }
                String label = (desc == null || desc.isBlank())
                        ? nif.getName()
                        : desc;
                out.add(new MonitorAdapter(nif.getName(), label));
            }
        } catch (PcapNativeException ex) {
            LOG.warn("findAllDevs failed", ex);
        }
        return Collections.unmodifiableList(out);
    }

    @Override
    public synchronized void start(MonitorAdapter adapter, int channelMhz,
                                   Consumer<RadiotapFrame> onFrame) throws MonitorModeException {
        if (running.get()) {
            throw new MonitorModeException("Capture already running; call stop() first.");
        }
        try {
            // Builder pattern is the only way to enable rfmon (monitor
            // mode) in pcap4j 1.8 - the openLive shortcut does not
            // expose the rfmon flag.
            PcapHandle.Builder builder = new PcapHandle.Builder(adapter.id())
                    .snaplen(SNAPLEN)
                    .promiscuousMode(PromiscuousMode.PROMISCUOUS)
                    .rfmon(true)
                    .timeoutMillis(READ_TIMEOUT_MS);
            handle = builder.build();
        } catch (PcapNativeException ex) {
            throw new MonitorModeException(
                    "Could not open monitor mode on '" + adapter.description()
                    + "'. Causes: not running as Administrator, Npcap installed "
                    + "without monitor-mode support, or driver does not advertise "
                    + "NDIS native 802.11 monitor capability. (" + ex.getMessage() + ")",
                    ex);
        }
        // Channel selection is not exposed by libpcap on Windows - the
        // OS controls that via the connection profile. The user has to
        // pre-tune via "netsh wlan disconnect" + WlanHelper or rely on
        // whatever channel the adapter is parked on. We log the request
        // so the UI can show "captured on whatever-the-adapter-was-on"
        // explicitly. Phase 2 channel-hop scheduler will tackle this
        // properly once we have a Windows-specific tuning path.
        LOG.info("Capture started on {} (requested channel {} MHz - tuning is OS-managed)",
                adapter.description(), channelMhz);

        running.set(true);
        pollThread = new Thread(() -> pollLoop(onFrame, channelMhz),
                "monitor-capture-" + adapter.id());
        pollThread.setDaemon(true);
        pollThread.start();
    }

    /**
     * Tight loop that copies each captured frame into a {@link RadiotapFrame}.
     * The radiotap header is parsed for RSSI and then stripped so downstream
     * consumers can index from byte 0 of the 802.11 MAC header (the contract
     * spelled out in the {@link RadiotapFrame} javadoc).
     */
    private void pollLoop(Consumer<RadiotapFrame> onFrame, int channelMhz) {
        while (running.get()) {
            try {
                byte[] raw = handle.getNextRawPacket();
                if (raw == null) continue; // timeout - normal, just retry
                RadiotapDecoder.Decoded d = RadiotapDecoder.decode(raw);
                int rtLen = d.radiotapLen();
                byte[] mac;
                if (rtLen <= 0 || rtLen >= raw.length) {
                    mac = new byte[0]; // truncated / unknown radiotap version
                } else {
                    mac = new byte[raw.length - rtLen];
                    System.arraycopy(raw, rtLen, mac, 0, mac.length);
                }
                long nowNs = System.nanoTime();
                onFrame.accept(new RadiotapFrame(nowNs, channelMhz, d.rssiDbm(), 0, mac));
            } catch (org.pcap4j.core.NotOpenException ex) {
                // Handle was closed under us by stop() - exit cleanly.
                break;
            } catch (RuntimeException ex) {
                LOG.warn("Capture poll error", ex);
                // Fall through and try again; transient driver errors
                // (e.g. radio briefly off) are common and recoverable.
            }
        }
    }

    @Override
    public synchronized void stop() {
        if (!running.getAndSet(false)) return;
        try {
            if (handle != null) handle.breakLoop();
        } catch (org.pcap4j.core.NotOpenException ignored) {
            /* already closed */
        }
        if (pollThread != null) {
            try {
                pollThread.join(500);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
            pollThread = null;
        }
        if (handle != null) {
            try {
                handle.close();
            } catch (RuntimeException ex) {
                LOG.debug("Handle close error", ex);
            }
            handle = null;
        }
    }
}
