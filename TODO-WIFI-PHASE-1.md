# Wi-Fi Analyzer - Phase 1 plan

A new "Wi-Fi" tab in the main window that combines two data sources we
*can* use today on a Windows machine without monitor mode:

- **HackRF sweep** - shared with the Scan tab, same engine.
- **Windows Native Wi-Fi API** (`wlanapi.dll` via JNA) - AP discovery
  (BSSID, SSID, RSSI, channel, channel width from HT/VHT/HE IEs).
  Windows-only; gracefully degrades to spectrum-only on other OSes
  (the project is Windows-targeted anyway, this is just defensive).

When the user opens the tab the engine auto-switches to the
`Wi-Fi 2.4 + 5 + 6E` multi-range preset; when the user leaves the tab
the previous frequency / plan is restored.

## Step-by-step (check off as we ship)

### Step 1 - Wi-Fi tab skeleton, channel grid, auto-config

- [ ] 1.1a  Add a `Wi-Fi` tab to the main `TabPane`, hosting its own
            content view alongside the existing Scan / Display tabs.
- [ ] 1.1b  On tab activation, snapshot current `frequencyPlan` /
            `frequency`, then apply the `Wi-Fi 2.4 + 5 + 6E` preset.
- [ ] 1.1c  On tab deactivation, restore the snapshot.
- [ ] 1.1d  Wi-Fi channel grid overlay (2.4 GHz ch 1-14,
            5 GHz UNII-1..8 ch 36-177, 6 GHz ch 1-233 with 20/40/80/160
            MHz shaded boxes). Shares the existing AllocationOverlayCanvas
            renderer; new dataset under `freq/Wi-Fi channel grid.csv`.
- [ ] 1.1e  Visual cue (small badge at top of tab) that explains the
            sweep is being managed by the Wi-Fi tab.

**Acceptance**: switching to the Wi-Fi tab automatically shows
2.4 / 5 / 6 GHz with all Wi-Fi channels labelled; switching back to Scan
restores whatever range the user had.

### Step 2 - AP discovery via Windows wlanapi

- [ ] 2.1  JNA mapping for `wlanapi.dll`:
           `WlanOpenHandle`, `WlanEnumInterfaces`, `WlanScan`,
           `WlanGetNetworkBssList`, `WlanFreeMemory`, `WlanCloseHandle`.
- [ ] 2.2  Polling background task (1 sample / second) that maintains a
           thread-safe `List<WifiAccessPoint>` snapshot.
           Falls back to "no source" on non-Windows.
- [ ] 2.3  AP list panel inside the Wi-Fi tab: BSSID, SSID, RSSI,
           channel, channel width, security. Sortable; default by RSSI
           descending.
- [ ] 2.4  Status indicator: number of APs visible per band
           (2.4 / 5 / 6 GHz totals).

**Acceptance**: with a real Wi-Fi adapter present the AP list updates
once per second and matches what `netsh wlan show networks mode=bssid`
reports.

### Step 3 - AP overlay on the spectrum chart

- [ ] 3.1  Per-AP marker on the spectrum chart at the AP's centre
           frequency, with width = channel width and height ~ RSSI.
           Drawn behind the live spectrum so it never hides the trace.
- [ ] 3.2  Hover tooltip: SSID + BSSID + RSSI + channel + width.
- [ ] 3.3  Per-AP signal trend (rolling 60 s line) shown in a small
           strip below the chart for the *selected* AP from the list.
- [ ] 3.4  Toggle in the Wi-Fi tab to show / hide AP markers.

**Acceptance**: a known AP shows up at the right frequency on the
spectrum, with the channel-width box matching its 802.11 capabilities.

### Step 4 - DSP layer (HackRF-only metrics)

- [ ] 4.1  Per-channel occupancy / duty cycle bar (% of sweeps where
           the average power in the channel exceeds a threshold).
- [ ] 4.2  Co-channel and adjacent-channel interference per Wi-Fi
           channel.
- [ ] 4.3  Density chart (heatmap of frequency vs. dBm vs. count).
- [ ] 4.4  Interferer signature classifier (microwave, BLE/BT FH,
           analog video sender / baby monitor) with detected-source list.

**Acceptance**: each metric is computed online from the existing sweep
stream without dropping samples (i.e. no `Arrays.sort` on the engine
thread - see the auto-fit lessons learned).

### Step 5 - Snapshot & scrub

- [ ] 5.1  Buffered-to-disk snapshot of waterfall + spectrum + AP list.
- [ ] 5.2  Scrub-back UI to inspect "what happened at HH:MM:SS".

**Acceptance**: the user can pause, rewind, and replay the last N
minutes of unified Wi-Fi data.

## Deliberate non-goals for Phase 1

Anything that needs raw 802.11 frame access stays in
`TODO-WIFI-PHASE-2.md`. Examples: per-client airtime, retries, MAC
roaming, hidden SSID discovery via probe-response capture.
