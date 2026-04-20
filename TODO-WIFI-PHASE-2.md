# Wi-Fi Analyzer - Phase 2 backlog

Things the Wi-Fi tab **cannot** do today and that are explicitly out of
scope for Phase 1. They all share one root cause: they need access to
raw 802.11 frames, and that requires a Wi-Fi NIC running in **monitor
mode** (Linux: `airmon-ng`; Windows: an Npcap build with monitor-mode
support and a compatible chipset, e.g. Atheros AR9271,
Mediatek MT7612U, some Intel AX200 with patched drivers).

Phase 1 ships with two data sources:

- **HackRF sweep** - RF spectrum, waterfall, occupancy, density,
  interferer signatures
- **Windows Native Wi-Fi API** (`wlanapi.dll` via JNA) - AP discovery
  (BSSID, SSID, RSSI, channel, channel width from HT/VHT/HE IEs)

That covers the "physical RF environment" half of Chanalyzer.
Everything below is the "MAC-layer / packet" half.

## Packet capture & MAC-layer

- [x] Integrate libpcap / Npcap (monitor-mode build) - pcap4j wired
      via `Pcap4jMonitorCapture`, factory falls back to a no-op when
      Npcap is missing so the rest of the UI never has to null-check.
- [x] Live capture of 802.11 management / control / data frames -
      `RadiotapDecoder` + `CaptureStats` count beacons / probe-req /
      probe-resp / deauth and per-BSSID RSSI for the strongest 64
      BSSIDs.
- [x] Hidden-SSID discovery via probe-response capture -
      `BeaconParser` + `BeaconStore` resolve the BSSID -> real SSID
      map from beacons and probe responses; `ApMarkerCanvas`,
      WifiWindow AP table and `ApTrendChart` substitute the resolved
      name as `(hidden: name)` instead of the bare placeholder.
- [ ] Export sessions to pcapng for offline forensics

## Airtime & utilization

- [ ] Per-BSSID airtime (% of duty cycle consumed)
- [ ] Per-client airtime - "slow talker" detection
- [ ] Retransmission rate per BSSID / per client
- [ ] Frame-error / corruption stats
- [x] BSS Load IE parsing from beacons - `BssLoad.parse` extracts
      station count + 0-100 channel-utilization from IE id 11; the
      `MonitorCapturePanel` shows the top advertisers as
      "BSSID  N% util  S sta".

## Client tracking & roaming

- [ ] Live MAC list per BSSID
- [ ] "Follow-a-MAC" mode - track a single STA across channels and APs
- [ ] Roaming events timeline (BSSID transitions for a target STA)
- [ ] Probe-request analysis - which devices are looking for which SSIDs

## Multi-radio capture

- [ ] Support 2+ Wi-Fi adapters in parallel
- [ ] Adapter A = continuous channel-hop scanner
- [ ] Adapter B = locked on a target channel for deep capture

## Unified Time Graph - packet side

- [ ] Synchronized scrub / replay across SDR and pcap timelines
- [ ] Frame-level annotations on the spectrum waterfall
      ("at 14:23:07, Beacon from BSSID X, RSSI -67 dBm")

## 802.11k/v/r telemetry from the connected AP

- [ ] Neighbor reports via OS API (no monitor mode needed but
      requires the connected AP to actually advertise k/v)
- [ ] AP load reporting via 802.11k

## Stretch goals

- [ ] Decrypt WPA2 traffic (with known PSK/PMK) for in-app payload analysis
- [ ] Throughput estimation per BSSID from frame headers
      (modulation rate x utilization)
- [ ] Compare with `iperf3` results to flag radio vs. network bottlenecks

## Explicitly out of scope - use a dedicated tool

- WPA2/3 cracking - use Aircrack-ng
- Layer-3+ analysis (DHCP, DNS, HTTP, ...) - use Wireshark
- Site-survey heatmaps tied to floor plans - use Ekahau / NetSpot

---

## Phase 2 prerequisite: monitor-mode capture (Npcap)

This is a planning sketch - **no runtime dependency is committed yet**.
Read the trade-offs below before adding pcap4j / jnetpcap / wpcap.dll
bindings to `build.gradle.kts`. The skeletal Java interface
(`jspectrumanalyzer.wifi.capture.MonitorModeCapture`) is in tree so the
rest of Phase 2 can be wired against it; the only implementation today
is `NoOpMonitorCapture`.

### Hardware + driver requirements

Monitor-mode capture only works when **all** of these are true on the
end-user's box:

1. A Wi-Fi NIC whose driver supports the NDIS native 802.11 monitor
   mode. The user already verified one of their adapters (Wi-Fi 6) via
   `netsh wlan show wirelesscapabilities` -> `Network monitor mode :
   Supported` and `WlanHelper.exe -i <iface> mode monitor` returning
   "OK". Without that, Npcap installs fine but the open call fails.
2. **Npcap** (https://npcap.com) installed with the
   "Support raw 802.11 traffic (and monitor mode) for wireless
   adapters" check-box enabled. That option is **off by default** in
   the Npcap installer.
3. The host process running with **administrator** privileges, or
   Npcap installed with the "Restrict Npcap driver's access to
   Administrators only" check-box unchecked. Java will not silently
   prompt for elevation; a non-admin run produces an "access denied"
   error from `pcap_open` that we have to surface clearly.

### Java binding options

| Option | Pros | Cons | License | JPMS |
| --- | --- | --- | --- | --- |
| **pcap4j** 1.8.2 | Pure Java + JNA (we already use JNA), 802.11 packet decoders out of the box (`Dot11ManagementPacket`, beacon / probe / radiotap), MIT, well-documented | Last release 2022 - some staleness. EHT (Wi-Fi 7) parsing not supported. | MIT | Auto-module `org.pcap4j.core`, no `module-info` |
| **jnetpcap** 2.x | Project Panama bindings (no JNA), more modern. Apache 2.0. | Requires JDK 22+ for v2; toolchain bump. Smaller community than pcap4j. | Apache 2.0 | Real `module-info` in v2 |
| **Raw JNA -> wpcap.dll** | Zero new dep surface (we already have JNA). Total control. | We write radiotap + 802.11 decoders ourselves (~1000 LOC). EHT support still our problem. | n/a | Same as today |

**Recommendation for Phase 2 prototype: pcap4j.** Same JNA backend we
already use, MIT-friendly, ships ready-made 802.11 decoders. We can
revisit jnetpcap once we bump the project's Java toolchain to 22+ for
unrelated reasons.

Maven coordinates if/when we commit:
```
org.pcap4j:pcap4j-core:1.8.2
org.pcap4j:pcap4j-packetfactory-static:1.8.2
```

### `build.gradle.kts` impact

- **Two new compile dependencies** (~600 KB jar total).
- **No native binaries shipped by us** - pcap4j calls `wpcap.dll` /
  `Packet.dll` from the user's Npcap install. Linux dev boxes need
  `libpcap-dev` for compile parity but the runtime detection lives in
  the implementation, not the build.
- **Test runtime** can stay on `NoOpMonitorCapture` so CI without Npcap
  installed keeps green.
- `--enable-native-access=ALL-UNNAMED` JVM arg already present for our
  JNA usage; pcap4j piggy-backs on the same flag - no extra modular
  wiring required for Java 17 / 21.

### Packaging / distribution impact

- We **cannot bundle the Npcap installer** - the OEM redistribution
  license costs $7.5k+/year. Users have to install Npcap themselves
  (free for personal use, free for commercial use within the same
  organisation that bought a single OEM license).
- Add a **first-launch capability check**: if `Pcaps.findAllDevs()`
  throws `PcapNativeException`, show a one-shot dialog with a link to
  https://npcap.com and a one-line install hint:
  > "Phase 2 packet-capture features need Npcap. Install with the
  > 'Support raw 802.11 traffic' option enabled and re-run as
  > Administrator."
- Document the chipset list in the README so a user without compatible
  hardware does not wonder why no adapters show up in the monitor-mode
  picker.

### API surface (lives in `wifi.capture`)

```java
public interface MonitorModeCapture extends AutoCloseable {
    /** Whether the host has Npcap installed and accessible. */
    boolean isAvailable();

    /** Adapters that report NDIS native-802.11 monitor capability. */
    List<MonitorAdapter> listAdapters();

    /** Tune the adapter to a channel and start streaming radiotap frames. */
    void start(MonitorAdapter adapter, int channelMhz,
               Consumer<RadiotapFrame> onFrame);

    void stop();
}

public record MonitorAdapter(String id, String description) {}

public record RadiotapFrame(long timestampNs, int channelMhz,
                            int rssiDbm, int rateMbps,
                            byte[] frame) {}
```

`Pcap4jMonitorCapture` implements `start` by:

1. `Pcaps.getDevByName(adapter.id()).openLive(BUFSIZE, RFMON, TIMEOUT)`.
2. Spawning a daemon polling thread that calls `handle.getNextPacket()`
   and translates each radiotap header + 802.11 frame into a
   `RadiotapFrame`.
3. Honouring `stop()` by `breakLoop()` + `close()` + `interrupt()`.

Channel hopping (Phase 2 stretch goal) lives one layer up in a
separate `ChannelHopController` so a single capture handle can be
re-tuned without recreating the pcap session.

### Phased rollout (after this prototype is reviewed)

1. ~~Wire pcap4j dep behind a Gradle `monitorMode` feature flag so
   default builds stay dependency-free.~~ **Done** - pcap4j is an
   `implementation` dep but loaded via reflection in
   `Pcap4jMonitorCapture`; missing Npcap degrades to
   `NoOpMonitorCapture` so dependency-free builds were not needed.
2. ~~Implement `Pcap4jMonitorCapture`~~ **Done**. The first-launch
   capability dialog was downsized to an inline explanatory label
   inside `MonitorCapturePanel` (no modal, no first-paint blocking) -
   simpler for users than yet another popup and serves the same
   purpose.
3. ~~Beacon decoder -> hidden-SSID discovery (probe-response
   capture).~~ **Done**. See `wifi.capture.ieee80211.BeaconParser`
   and `wifi.capture.BeaconStore`. The store is app-scope so resolved
   names survive Wi-Fi-window close/reopen cycles. Display chain:
   `ApMarkerCanvas`, the AP table in `WifiWindow`, and `ApTrendChart`
   all render `"(hidden: name)"` once the store has heard a real
   SSID for that BSSID.
4. ~~BSS Load IE parser -> per-AP utilization without sweep
   mismatch.~~ **Done**. `BssLoad.parse` extracts the IE-id-11 5-byte
   body. `MonitorCapturePanel` ranks advertisers by self-reported
   channel utilization and shows the top four.
5. **Per-BSSID airtime tally** -> "slow talker" detection.
   _Next up._ Track per-BSSID frame counts over a rolling window in
   the same `BeaconStore`-style service; weight by frame length when
   we add radiotap "data rate" parsing.
6. **Channel-hop scheduler** -> multi-channel discovery on a single
   NIC. Requires a Windows-specific tuning path
   (`netsh wlan` / WlanHelper) since libpcap on Windows cannot
   re-tune the radio. Defer until step 5 lands so we have a real
   user of the multi-channel discovery story.
