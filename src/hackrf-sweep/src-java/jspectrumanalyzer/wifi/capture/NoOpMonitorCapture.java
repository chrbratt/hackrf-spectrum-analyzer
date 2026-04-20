package jspectrumanalyzer.wifi.capture;

import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;

/**
 * Default {@link MonitorModeCapture} backend used when no monitor-mode
 * dependency (pcap4j / jnetpcap / raw wpcap.dll) is on the classpath.
 *
 * <p>Reports unavailable, lists no adapters and rejects every
 * {@link #start} call with a message the UI can show verbatim. Lets
 * the rest of Phase 2 compile and even run end-to-end before the real
 * Npcap dependency is committed - everything degrades to a disabled
 * "Capture..." button instead of a {@code NullPointerException}.
 */
public final class NoOpMonitorCapture implements MonitorModeCapture {

    private static final String UNAVAILABLE_MSG =
            "Monitor-mode capture is not available in this build. "
            + "Phase 2 features require Npcap with monitor-mode support "
            + "(see TODO-WIFI-PHASE-2.md).";

    @Override
    public boolean isAvailable() {
        return false;
    }

    @Override
    public List<MonitorAdapter> listAdapters() {
        return Collections.emptyList();
    }

    @Override
    public void start(MonitorAdapter adapter, int channelMhz,
                      Consumer<RadiotapFrame> onFrame) throws MonitorModeException {
        throw new MonitorModeException(UNAVAILABLE_MSG);
    }

    @Override
    public void stop() {
        /* nothing to do */
    }
}
