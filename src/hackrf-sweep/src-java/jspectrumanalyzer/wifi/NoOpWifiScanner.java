package jspectrumanalyzer.wifi;

import java.util.Collections;
import java.util.List;

/**
 * Returned by {@link WifiScannerFactory#create()} on platforms that do not
 * ship a usable Native Wi-Fi API (Linux, macOS, container builds). Reports
 * {@code isAvailable() == false} so the UI can hide the AP list cleanly
 * without a special case at every call site.
 */
public final class NoOpWifiScanner implements WifiScanner {

    @Override public boolean isAvailable() { return false; }

    @Override public List<WifiAccessPoint> scan() { return Collections.emptyList(); }

    @Override public void requestScan() { /* no-op */ }

    @Override public void close() { /* no-op */ }
}
