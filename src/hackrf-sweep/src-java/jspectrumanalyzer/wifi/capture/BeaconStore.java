package jspectrumanalyzer.wifi.capture;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

import jspectrumanalyzer.wifi.capture.ieee80211.BeaconBody;
import jspectrumanalyzer.wifi.capture.ieee80211.BeaconParser;
import jspectrumanalyzer.wifi.capture.ieee80211.BssLoad;

/**
 * Per-BSSID store of beacon-derived facts: discovered SSID (resolves
 * the "(hidden)" placeholder in the AP table once we hear a probe
 * response that carries the real name) and the latest BSS Load IE
 * (per-AP utilization + station count, straight from the AP's own
 * MAC counters).
 *
 * <h2>Threading</h2>
 * One writer (the monitor-capture polling thread feeds {@link #accept})
 * and many readers (UI rendering on the JavaFX thread). Backed by
 * {@link ConcurrentHashMap} so reads are lock-free; the listener list
 * is a {@link CopyOnWriteArrayList} for the same reason. Listeners
 * fire on the writer thread so UI subscribers must marshal back via
 * {@code Platform.runLater} themselves - the contract mirrors
 * {@link jspectrumanalyzer.wifi.WifiScanService#addListener}.
 *
 * <h2>Why a single store rather than two services?</h2>
 * The two pieces of state share the same source (a beacon /
 * probe-response body) and the same lifecycle (cleared on Start).
 * Splitting them would mean parsing each frame twice, which adds up
 * fast on a busy 80 MHz channel. Keep them together; the public
 * methods make the two concerns easy to read.
 */
public final class BeaconStore {

    /**
     * Listeners are notified at most once per
     * {@link #LISTENER_DEBOUNCE_FRAMES} frame batches so a busy
     * channel does not spam the UI. The constant trades freshness for
     * paint cost; 16 means we wake the UI no more than ~ once per
     * 16 management frames.
     */
    private static final int LISTENER_DEBOUNCE_FRAMES = 16;

    /** Discovered SSID per BSSID; never {@code null}, may be empty string. */
    private final Map<String, String> discoveredSsids = new ConcurrentHashMap<>();
    /** Latest BSS Load IE per BSSID. Absent BSSIDs simply have no entry. */
    private final Map<String, BssLoad> bssLoads = new ConcurrentHashMap<>();
    private final CopyOnWriteArrayList<Runnable> listeners = new CopyOnWriteArrayList<>();

    private final AtomicLong frameCounter = new AtomicLong();

    /**
     * Feed one captured frame. No-op if the frame is not a beacon or
     * probe response - we accept the cost of the type check rather
     * than forcing every caller to filter, because {@link
     * jspectrumanalyzer.fx.ui.MonitorCapturePanel} has historically
     * forwarded raw frames to multiple consumers and the type check
     * is one byte read.
     */
    public void accept(RadiotapFrame frame) {
        if (frame == null) return;
        byte[] mac = frame.frame();
        if (!BeaconParser.isBeaconOrProbeResp(mac)) return;
        String bssid = BeaconParser.bssidOf(mac);
        if (bssid == null) return;
        BeaconBody body = BeaconParser.parse(mac);
        boolean changed = false;
        if (body.ssid().isPresent() && !body.ssid().get().isEmpty()) {
            // Only remember non-empty SSIDs - hidden beacons (length-0
            // SSID IE) do not help anyone resolve the placeholder.
            String previous = discoveredSsids.put(bssid.toLowerCase(), body.ssid().get());
            if (previous == null || !previous.equals(body.ssid().get())) changed = true;
        }
        if (body.bssLoad().isPresent()) {
            BssLoad newLoad = body.bssLoad().get();
            BssLoad prev = bssLoads.put(bssid.toLowerCase(), newLoad);
            if (prev == null || prev.channelUtilizationPercent() != newLoad.channelUtilizationPercent()
                    || prev.stationCount() != newLoad.stationCount()) {
                changed = true;
            }
        }
        if (changed && (frameCounter.incrementAndGet() % LISTENER_DEBOUNCE_FRAMES) == 0) {
            for (Runnable r : listeners) {
                try { r.run(); } catch (RuntimeException ignored) { /* listener bug, swallow */ }
            }
        }
    }

    /**
     * Discovered SSID for this BSSID, if we have ever heard one.
     *
     * <p>Both producers ({@code WindowsWlanScanner} and
     * {@link BeaconParser}) emit lowercase colon-separated MACs, so a
     * case-sensitive lookup is enough; we still normalise the input to
     * be defensive against future callers that pass an upper-case
     * literal.
     */
    public Optional<String> discoveredSsid(String bssid) {
        if (bssid == null) return Optional.empty();
        return Optional.ofNullable(discoveredSsids.get(bssid.toLowerCase()));
    }

    /** Latest BSS Load IE we have parsed for this BSSID, if any. */
    public Optional<BssLoad> bssLoad(String bssid) {
        if (bssid == null) return Optional.empty();
        return Optional.ofNullable(bssLoads.get(bssid.toLowerCase()));
    }

    /** Defensive copy of the BSSID -> discovered SSID map. */
    public Map<String, String> discoveredSsidSnapshot() {
        return Map.copyOf(discoveredSsids);
    }

    /** Defensive copy of the BSSID -> BSS Load map. */
    public Map<String, BssLoad> bssLoadSnapshot() {
        return Map.copyOf(bssLoads);
    }

    /** Total number of BSSIDs whose hidden SSID we resolved this session. */
    public int discoveredCount() { return discoveredSsids.size(); }

    /** Forget every observation. Called from the panel on Start. */
    public void reset() {
        discoveredSsids.clear();
        bssLoads.clear();
        frameCounter.set(0);
    }

    /**
     * Register a listener that fires (on the capture polling thread)
     * whenever the store learns something new. UI consumers MUST
     * marshal back to the JavaFX thread themselves - the listener may
     * be invoked tens of times per second on a busy channel and we
     * never want to drag {@code Platform.runLater} into the parser
     * hot loop.
     */
    public void addListener(Runnable r) {
        if (r != null) listeners.add(r);
    }

    public void removeListener(Runnable r) {
        listeners.remove(r);
    }
}
