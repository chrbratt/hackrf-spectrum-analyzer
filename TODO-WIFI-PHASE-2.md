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

- [ ] Integrate libpcap / Npcap (monitor-mode build)
- [ ] Live capture of 802.11 management / control / data frames
- [ ] Hidden-SSID discovery via probe-response capture
- [ ] Export sessions to pcapng for offline forensics

## Airtime & utilization

- [ ] Per-BSSID airtime (% of duty cycle consumed)
- [ ] Per-client airtime - "slow talker" detection
- [ ] Retransmission rate per BSSID / per client
- [ ] Frame-error / corruption stats
- [ ] BSS Load IE parsing from beacons
      (station count, channel utilization announced by AP)

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
