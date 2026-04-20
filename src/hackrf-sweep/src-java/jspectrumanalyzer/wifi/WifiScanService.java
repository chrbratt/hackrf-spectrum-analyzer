package jspectrumanalyzer.wifi;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Background polling wrapper around a {@link WifiScanner}.
 * <p>
 * Two cadences run on the same single-thread executor so we never have two
 * scans in flight at once:
 * <ul>
 *   <li><b>1 s</b> - read the OS BSS cache and publish a fresh snapshot.</li>
 *   <li><b>5 s</b> - hint the OS to actively re-scan; results show up in
 *       a subsequent 1-second poll once the radio finishes sweeping.</li>
 * </ul>
 *
 * <p>The latest snapshot is stored in an {@link AtomicReference} so UI code
 * can read it without blocking the polling thread; listeners receive each
 * fresh snapshot on the polling thread and are responsible for marshalling
 * to whatever UI thread they need (typically
 * {@code Platform.runLater(...)} for JavaFX).
 *
 * <p>Lifecycle: {@link #start()} once at app startup, {@link #stop()} once
 * at shutdown. Both are idempotent.
 */
public final class WifiScanService {

    private static final Logger LOG = LoggerFactory.getLogger(WifiScanService.class);

    private static final long POLL_INTERVAL_MS = 1000;
    private static final long ACTIVE_SCAN_INTERVAL_MS = 5000;

    /** Default rolling-history window per AP, in milliseconds. */
    public static final long HISTORY_WINDOW_MS = 60_000L;

    private final WifiScanner scanner;
    private final AtomicReference<List<WifiAccessPoint>> latest =
            new AtomicReference<>(Collections.emptyList());
    private final List<Consumer<List<WifiAccessPoint>>> listeners = new CopyOnWriteArrayList<>();

    /**
     * Per-BSSID rolling RSSI history. Each deque holds {@link RssiSample}s
     * appended in time order; samples older than {@link #HISTORY_WINDOW_MS}
     * are pruned at the start of each poll. Concurrent map so the FX-thread
     * reader and the polling-thread writer never deadlock; deque accesses
     * are synchronized on the deque itself for the same reason.
     */
    private final Map<String, Deque<RssiSample>> history = new ConcurrentHashMap<>();

    private ScheduledExecutorService executor;
    private ScheduledFuture<?> pollHandle;
    private ScheduledFuture<?> scanHandle;

    public WifiScanService(WifiScanner scanner) {
        this.scanner = scanner;
    }

    /** {@code true} if the wrapped scanner reports a usable Wi-Fi stack. */
    public boolean isAvailable() {
        return scanner.isAvailable();
    }

    /** Pass-through to {@link WifiScanner#listAdapters()}. */
    public List<WifiAdapter> listAdapters() {
        return scanner.listAdapters();
    }

    /**
     * Pass-through to {@link WifiScanner#setSelectedAdapter(String)}.
     * Safe to call from any thread; the change takes effect on the next
     * poll tick.
     */
    public void setSelectedAdapter(String guidHex) {
        scanner.setSelectedAdapter(guidHex);
    }

    /** Most recent snapshot; never null, may be empty. Cheap atomic read. */
    public List<WifiAccessPoint> getLatest() {
        return latest.get();
    }

    /**
     * Subscribe to fresh snapshots. The listener is invoked on the polling
     * thread - JavaFX consumers must wrap their callback in
     * {@code Platform.runLater}.
     *
     * <p>The current snapshot is delivered immediately so the UI does not
     * have to wait a full second for its first paint.
     */
    public void addListener(Consumer<List<WifiAccessPoint>> listener) {
        listeners.add(listener);
        listener.accept(latest.get());
    }

    public void removeListener(Consumer<List<WifiAccessPoint>> listener) {
        listeners.remove(listener);
    }

    /** Start the polling threads. No-op if already running. */
    public synchronized void start() {
        if (executor != null) return;
        executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "wifi-scan-service");
            t.setDaemon(true);
            return t;
        });
        // Schedule the active-scan hint first so the first poll has fresh
        // data to read. Initial delays are spaced (0 ms / 250 ms) so the
        // two callbacks never race for the executor's single slot on the
        // very first tick.
        scanHandle = executor.scheduleAtFixedRate(this::triggerActiveScan,
                0, ACTIVE_SCAN_INTERVAL_MS, TimeUnit.MILLISECONDS);
        pollHandle = executor.scheduleAtFixedRate(this::pollOnce,
                250, POLL_INTERVAL_MS, TimeUnit.MILLISECONDS);
        LOG.info("Wi-Fi scan service started (available={}).", scanner.isAvailable());
    }

    /** Stop polling and close the scanner. Safe to call multiple times. */
    public synchronized void stop() {
        if (executor == null) return;
        if (pollHandle != null) pollHandle.cancel(false);
        if (scanHandle != null) scanHandle.cancel(false);
        executor.shutdown();
        try {
            executor.awaitTermination(2, TimeUnit.SECONDS);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
        executor = null;
        try {
            scanner.close();
        } catch (RuntimeException ex) {
            LOG.warn("Wi-Fi scanner close failed", ex);
        }
        LOG.info("Wi-Fi scan service stopped.");
    }

    private void pollOnce() {
        try {
            List<WifiAccessPoint> fresh = scanner.scan();
            // Defensive: scan() should never return null but make absolutely
            // sure listeners always get a non-null list.
            if (fresh == null) fresh = Collections.emptyList();
            latest.set(fresh);
            updateHistory(fresh, System.currentTimeMillis());
            for (Consumer<List<WifiAccessPoint>> l : listeners) {
                try {
                    l.accept(fresh);
                } catch (RuntimeException ex) {
                    LOG.warn("Wi-Fi listener threw", ex);
                }
            }
        } catch (RuntimeException ex) {
            LOG.warn("Wi-Fi poll failed", ex);
        }
    }

    /**
     * Append the latest RSSI of every visible AP to its rolling history and
     * evict samples older than {@link #HISTORY_WINDOW_MS}. Also drops the
     * deque for any BSSID that has been gone for the entire window so the
     * map cannot grow without bound when APs disappear (mobile hotspots).
     */
    private void updateHistory(List<WifiAccessPoint> snapshot, long now) {
        Set<String> seenThisTick = new HashSet<>(snapshot.size() * 2);
        for (WifiAccessPoint ap : snapshot) {
            String key = ap.bssid();
            seenThisTick.add(key);
            Deque<RssiSample> dq = history.computeIfAbsent(key, k -> new ArrayDeque<>());
            synchronized (dq) {
                dq.addLast(new RssiSample(now, ap.rssiDbm()));
                pruneOlderThan(dq, now - HISTORY_WINDOW_MS);
            }
        }
        // Sweep map: drop entries whose newest sample is older than the
        // window so disappeared APs don't linger forever in the trend store.
        long cutoff = now - HISTORY_WINDOW_MS;
        Iterator<Map.Entry<String, Deque<RssiSample>>> it = history.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, Deque<RssiSample>> e = it.next();
            if (seenThisTick.contains(e.getKey())) continue;
            Deque<RssiSample> dq = e.getValue();
            synchronized (dq) {
                pruneOlderThan(dq, cutoff);
                if (dq.isEmpty()) it.remove();
            }
        }
    }

    private static void pruneOlderThan(Deque<RssiSample> dq, long minTimestampMs) {
        while (!dq.isEmpty() && dq.peekFirst().timestampMs < minTimestampMs) {
            dq.pollFirst();
        }
    }

    /**
     * Snapshot the rolling RSSI history for one BSSID. The returned list is a
     * defensive copy and ordered oldest-first; callers can render it directly
     * without worrying about concurrent modification from the polling thread.
     * Returns an empty list if the BSSID is unknown.
     */
    public List<RssiSample> getHistory(String bssid) {
        if (bssid == null) return Collections.emptyList();
        Deque<RssiSample> dq = history.get(bssid);
        if (dq == null) return Collections.emptyList();
        synchronized (dq) {
            return new ArrayList<>(dq);
        }
    }

    /** Single timestamped RSSI reading. {@code timestampMs} is wall-clock {@link System#currentTimeMillis()}. */
    public record RssiSample(long timestampMs, int rssiDbm) {}

    private void triggerActiveScan() {
        try {
            scanner.requestScan();
        } catch (RuntimeException ex) {
            LOG.warn("Wi-Fi active scan request failed", ex);
        }
    }
}
