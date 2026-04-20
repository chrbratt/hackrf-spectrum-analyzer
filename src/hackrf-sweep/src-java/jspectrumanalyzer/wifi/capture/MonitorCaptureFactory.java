package jspectrumanalyzer.wifi.capture;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Picks a concrete {@link MonitorModeCapture} backend at startup.
 *
 * <p>Order:
 * <ol>
 *   <li>{@link Pcap4jMonitorCapture} when pcap4j classes are on the
 *       classpath <i>and</i> Npcap can be loaded by the JVM. We probe
 *       both via {@link #pcap4jUsable()} so a stale install (Npcap
 *       missing the {@code wpcap.dll}, or installed without monitor
 *       support) does not crash the whole UI.</li>
 *   <li>{@link NoOpMonitorCapture} as a last-resort no-op so callers
 *       never have to null-check.</li>
 * </ol>
 *
 * <p>The factory is intentionally stateless. Callers ask once at
 * startup (typically in {@code FxApp}) and pass the resulting instance
 * down into the Wi-Fi window. Two captures on the same adapter are not
 * supported by libpcap so reusing one instance is the right model.
 */
public final class MonitorCaptureFactory {

    private static final Logger LOG = LoggerFactory.getLogger(MonitorCaptureFactory.class);

    private MonitorCaptureFactory() {}

    public static MonitorModeCapture create() {
        if (pcap4jUsable()) {
            try {
                Pcap4jMonitorCapture cap = new Pcap4jMonitorCapture();
                if (cap.isAvailable()) {
                    LOG.info("Monitor-mode capture: pcap4j backend selected");
                    return cap;
                }
                LOG.info("pcap4j classes loaded but isAvailable=false; using no-op backend");
            } catch (Throwable t) {
                // Catch Throwable: a missing native dependency surfaces
                // as ExceptionInInitializerError on first static touch
                // of pcap4j classes.
                LOG.warn("Pcap4j backend init failed, falling back to no-op", t);
            }
        }
        return new NoOpMonitorCapture();
    }

    /**
     * Cheap classpath probe. We never instantiate the class here - just
     * check Class.forName so a missing dep does not throw past the
     * caller. Native library load is deferred to the backend's own
     * {@code isAvailable()} probe.
     */
    private static boolean pcap4jUsable() {
        try {
            Class.forName("org.pcap4j.core.Pcaps", false,
                    MonitorCaptureFactory.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException ex) {
            return false;
        }
    }
}
