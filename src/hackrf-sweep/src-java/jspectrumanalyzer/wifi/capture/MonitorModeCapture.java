package jspectrumanalyzer.wifi.capture;

import java.util.List;
import java.util.function.Consumer;

/**
 * Phase-2 capture-layer abstraction. Surface area is intentionally tiny -
 * one method to discover monitor-capable adapters, one to start a stream
 * of radiotap-tagged 802.11 frames on a chosen channel, one to stop.
 *
 * <p><b>Status:</b> Phase 1 ships only {@link NoOpMonitorCapture}; the
 * {@code Pcap4jMonitorCapture} backend lives behind the {@code monitorMode}
 * Gradle feature flag (not yet committed) and depends on the user having
 * Npcap installed with monitor-mode support enabled. See
 * {@code TODO-WIFI-PHASE-2.md} for the full dependency / packaging
 * analysis. The interface exists today so the rest of Phase 2
 * (per-BSSID airtime, hidden-SSID discovery, frame-level annotations)
 * can be designed and partially implemented without blocking on the
 * Npcap roll-out.
 *
 * <p><b>Threading:</b> {@link #start} is called on the UI thread but the
 * supplied {@code onFrame} callback runs on the capture's internal
 * polling thread. UI consumers must marshal back via
 * {@code Platform.runLater} themselves - mirroring how
 * {@code WifiScanService} delivers its snapshots.
 */
public interface MonitorModeCapture extends AutoCloseable {

    /**
     * Whether the host has a monitor-mode capable backend installed and
     * accessible. Implementations should be cheap (no hardware probe) -
     * use {@link #listAdapters} for the actual enumeration.
     */
    boolean isAvailable();

    /**
     * Adapters that the backend reports as supporting NDIS native-802.11
     * monitor capability. Empty when {@link #isAvailable} is {@code false}
     * or when no compatible chipset is installed.
     */
    List<MonitorAdapter> listAdapters();

    /**
     * Tune {@code adapter} to the given centre frequency and start
     * streaming captured radiotap frames into {@code onFrame}. Throws
     * {@link MonitorModeException} when the capture handle could not be
     * opened (most often: not running as administrator, Npcap installed
     * without monitor-mode support, or unsupported chipset).
     *
     * <p>Calling {@link #start} while a capture is already running is an
     * error - call {@link #stop} first or instantiate a second
     * implementation if simultaneous captures on different adapters are
     * desired (Phase 2 multi-radio scenario).
     */
    void start(MonitorAdapter adapter, int channelMhz,
               Consumer<RadiotapFrame> onFrame) throws MonitorModeException;

    /**
     * Halt the in-flight capture and release the OS handle. Safe to call
     * when nothing is running (no-op).
     */
    void stop();

    /**
     * Human-readable result of the most recent channel-tune attempt
     * performed during {@link #start}. Empty string when the backend
     * does not tune (NoOp), when {@link #start} has not been called
     * yet, or when the capture started but no tuning was attempted.
     *
     * <p>The Windows pcap4j backend uses Npcap's {@code WlanHelper.exe}
     * to actually switch the radio's centre frequency after libpcap
     * puts the adapter into monitor mode; this method exposes the
     * helper's stdout/stderr so the UI can show the user whether the
     * channel was honoured ("tuned to 5590 MHz") or rejected
     * ("WlanHelper.exe not found", "exit 1: invalid frequency").
     */
    default String lastTuneStatus() { return ""; }

    /** Convenience for try-with-resources callers. Equivalent to {@link #stop}. */
    @Override
    default void close() {
        stop();
    }
}
