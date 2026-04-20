package jspectrumanalyzer.wifi.capture.ieee80211;

import java.util.Optional;

/**
 * Decoded contents of a Beacon or Probe Response management frame
 * body, restricted to the fields the Phase-2 UI consumes today (SSID
 * for hidden-AP discovery and BSS Load for per-channel utilization).
 *
 * <p>Fields are {@link Optional} so a caller can distinguish "the IE
 * was absent from this beacon" from "the IE was present but conveyed
 * something we treat as empty". An empty SSID IE
 * ({@code length == 0}) is a hidden network and surfaces here as
 * {@code Optional.of("")} - very different from
 * {@code Optional.empty()}, which means we did not see the IE at all
 * (e.g. a probe request, which has no SSID slot).
 */
public record BeaconBody(Optional<String> ssid, Optional<BssLoad> bssLoad) {

    public static BeaconBody empty() {
        return new BeaconBody(Optional.empty(), Optional.empty());
    }
}
