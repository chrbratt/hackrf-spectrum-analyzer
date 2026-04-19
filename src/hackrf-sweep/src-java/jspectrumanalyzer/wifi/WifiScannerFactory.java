package jspectrumanalyzer.wifi;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import jspectrumanalyzer.wifi.win32.Wlanapi;

/**
 * Picks the right {@link WifiScanner} implementation for the host OS.
 * <p>
 * On Windows we attempt to open a Native Wi-Fi session; any failure (DLL
 * missing, WLAN service stopped, no Wi-Fi adapter) silently falls back to
 * the {@link NoOpWifiScanner}. Non-Windows platforms always get the no-op.
 *
 * <p>This indirection keeps every UI-side caller free of OS conditionals -
 * they instantiate one scanner and read {@link WifiScanner#isAvailable()}.
 */
public final class WifiScannerFactory {

    private static final Logger LOG = LoggerFactory.getLogger(WifiScannerFactory.class);

    private WifiScannerFactory() {}

    public static WifiScanner create() {
        if (!Wlanapi.isAvailable()) {
            LOG.info("Native Wi-Fi API unavailable on this platform; AP discovery disabled.");
            return new NoOpWifiScanner();
        }
        try {
            return new WindowsWlanScanner();
        } catch (Throwable t) {
            LOG.warn("Native Wi-Fi scanner init failed; falling back to no-op.", t);
            return new NoOpWifiScanner();
        }
    }
}
