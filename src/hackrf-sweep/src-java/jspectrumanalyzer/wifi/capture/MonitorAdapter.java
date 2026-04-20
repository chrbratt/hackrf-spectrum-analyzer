package jspectrumanalyzer.wifi.capture;

import java.util.Objects;

/**
 * Identifier for a Wi-Fi adapter visible to the monitor-mode backend.
 *
 * <p>{@code id} is whatever the backend uses internally - on Windows
 * with pcap4j this is the {@code \\Device\\NPF_{GUID}} path, on Linux
 * it would be {@code wlan0}. The UI never parses it, just shows
 * {@code description} and round-trips {@code id} back to
 * {@link MonitorModeCapture#start}. Equality is by {@code id} only so
 * the same adapter discovered by two backends is treated as one entry.
 */
public record MonitorAdapter(String id, String description) {
    public MonitorAdapter {
        Objects.requireNonNull(id, "id");
        if (description == null) description = "";
    }
}
