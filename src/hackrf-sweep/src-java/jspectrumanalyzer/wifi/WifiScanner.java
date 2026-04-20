package jspectrumanalyzer.wifi;

import java.util.List;

/**
 * Platform-agnostic surface for "ask the OS what Wi-Fi networks are visible".
 * <p>
 * Implementations are picked by {@link WifiScannerFactory#create()} based on
 * the host OS. Callers should treat {@link #isAvailable()} as the single
 * source of truth for whether the UI can render an AP list at all - on
 * platforms that lack a Wi-Fi adapter the no-op implementation reports
 * {@code false} and the UI hides the panel cleanly.
 *
 * <p>{@link #scan()} is expected to be cheap (read a cached BSS list) and
 * is safe to call once per second; {@link #requestScan()} actively triggers
 * the radio to sweep channels and may take several seconds before its
 * results show up in subsequent {@code scan()} calls.
 */
public interface WifiScanner extends AutoCloseable {

    /** {@code false} on non-Windows or when the Wi-Fi service is disabled. */
    boolean isAvailable();

    /**
     * Read the current BSS cache for every Wi-Fi adapter on the system.
     * Returns an empty list (never null) if {@link #isAvailable()} is false
     * or if no APs are currently visible.
     */
    List<WifiAccessPoint> scan();

    /**
     * Hint the OS to perform an active scan on every adapter. Returns
     * immediately - results land in subsequent {@link #scan()} calls.
     */
    void requestScan();

    /**
     * Snapshot of every Wi-Fi interface the OS currently exposes.
     * Returns an empty list (never null) on platforms without a usable
     * Wi-Fi stack. Cheap: implementations cache the result and
     * re-enumerate only when the cache is empty (see
     * {@link WindowsWlanScanner}).
     */
    List<WifiAdapter> listAdapters();

    /**
     * Restrict {@link #scan()} and {@link #requestScan()} to a single
     * adapter, matched by the {@code guidHex} returned from
     * {@link #listAdapters()}. Pass {@link WifiAdapter#ALL} (or
     * {@code null}) to query every adapter (the default behaviour).
     *
     * <p>An unknown GUID is silently ignored - the scanner falls back to
     * "all adapters" so the UI never gets stuck on a stale selection
     * after a USB adapter is unplugged.
     */
    void setSelectedAdapter(String guidHex);

    /** Idempotent. Releases the OS handle / closes the session. */
    @Override
    void close();
}
