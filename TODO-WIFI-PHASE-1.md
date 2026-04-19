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

### Step 1 - Wi-Fi tab skeleton, channel grid, auto-config  *(done)*

- [x] 1.1a  Add a `Wi-Fi` tab to the main `TabPane`, hosting its own
            content view alongside the existing Scan / Display tabs.
- [x] 1.1b  On tab activation, snapshot current `frequencyPlan` /
            `frequency`, then apply the `Wi-Fi 2.4 + 5 + 6E` preset.
- [x] 1.1c  On tab deactivation, restore the snapshot.
- [x] 1.1d  Wi-Fi channel grid overlay - 2.4 GHz ch 1/6/11 (the
            non-overlapping primaries; the existing renderer cannot
            stack overlapping bands without a rewrite, see note below),
            5 GHz UNII-1/2A/2C/3 (ch 36-165), and 6 GHz PSCs
            (15 channels covering 5945-7125 MHz). Shares the existing
            AllocationOverlayCanvas renderer; dataset shipped as
            `freq/Wi-Fi channel grid.csv` and auto-indexed by
            `generateFreqIndex`.
- [x] 1.1e  Visual cue (purple badge at the top of the Wi-Fi tab,
            `.wifi-mode-badge` in `dark.css`) explaining that the sweep
            is being managed by the Wi-Fi tab.

**Acceptance**: switching to the Wi-Fi tab automatically shows
2.4 / 5 / 6 GHz with the recommended Wi-Fi channels labelled; switching
back to any other tab restores whatever range / overlay state the user
had at the moment of entry.

### Step 1.b - Per-band picker inside the Wi-Fi tab  *(done)*

- [x] 1.b.1  Band combo at the top of the Wi-Fi tab body:
             "All bands" / "2.4 GHz" / "5 GHz" / "6 GHz (Wi-Fi 6E)".
- [x] 1.b.2  When a single band is picked, expose Start MHz / Stop MHz
             spinners bounded by that band so the user can channel-
             restrict (e.g. 2451-2473 = ch 11 only on 2.4 GHz).
- [x] 1.b.3  Live readout below the spinners: "Active range: A-B MHz
             (band, N MHz wide)".
- [x] 1.b.4  Plan changes are pushed to the model only while the tab is
             active (snapshot != null) so the user's local UI tweaks
             never leak to other tabs.

**Acceptance**: picking "2.4 GHz" reconfigures the engine to scan only
2400-2500 MHz, the chart's X-axis becomes proportional to that single
band (so individual channels are readable), and the channel grid still
overlays correctly. Narrowing the spinners to e.g. 2426-2448 MHz makes
only ch 6 visible.

**Notes / limitations carried into Step 2+**
- The 5 GHz / 6 GHz lists deliberately omit UNII-4 (169-177) and the
  full per-channel 6 GHz grid (every 4th channel = 59 entries) to keep
  labels readable at typical chart widths. UNII-4 plus a "show every
  6 GHz channel" toggle is a candidate for Step 4 once we have channel
  occupancy bars to anchor each entry.
- The Scan / Display tabs' combos are *not* synced when the Wi-Fi tab
  changes the frequency plan or allocation table externally. The model
  is the source of truth so the engine still behaves correctly; the
  combos just look stale until re-clicked. Wiring model-to-combo
  listeners is a small follow-up but out of Step 1 scope.
- The 2.4 GHz overlay shows only the non-overlapping primaries
  (ch 1, 6, 11). A full ch 1-13 lane-stacked view will be considered
  alongside the per-channel occupancy bars in Step 4.
- **All-bands view has a disproportionate X-axis**: JFreeChart's axis
  is linear in MHz, so 2.4 GHz (100 MHz wide) gets ~5% of the chart
  width while 5 + 6 GHz (~1945 MHz combined) eat the rest. The dead
  air at 2500-5150 and 5895-5925 is drawn as empty plot too. The
  band picker above lets the user work around this by selecting a
  single band; a proper *stitched* axis that gives each band equal
  width and visually marks the gaps is a candidate for Step 1.c, see
  below.

### Step 1.c - Stitched X-axis (equal-band weighting)  *(deferred)*

- [ ] 1.c.1  Custom JFreeChart {@code NumberAxis} subclass that knows
             about the active multi-range plan and remaps each segment
             to an equal slice of the plot width, with visual gap
             markers (e.g. a dashed vertical line + "..." label) where
             the dead air was elided.
- [ ] 1.c.2  Tick label formatting follows the segment the tick falls
             into: e.g. "2412 (ch 1)" instead of raw MHz, when the
             channel grid overlay is active.
- [ ] 1.c.3  Ensure the waterfall, allocation overlay, and persistent
             display all use the same remap so a vertical line is
             still aligned across all three views.

**Acceptance**: with the all-bands Wi-Fi preset selected, every band
takes 1/3 of the chart width and the gaps are visibly marked. A peak
at 2462 MHz lines up with the "2462 (ch 11)" tick on every overlay.

**Why deferred**: this is a 4-6 hour rewrite that touches the chart
axis, the allocation overlay, the waterfall offset calculation and
the persistent-display data-area listener. Worth doing once we have
AP markers to attach to channel ticks (Step 3); doing it now would
just move the cosmetic problem around.

### Step 2 - AP discovery via Windows wlanapi  *(done)*

- [x] 2.1  JNA mapping for `wlanapi.dll`:
           `WlanOpenHandle`, `WlanEnumInterfaces`, `WlanScan`,
           `WlanGetNetworkBssList`, `WlanFreeMemory`, `WlanCloseHandle`
           (see `jspectrumanalyzer.wifi.win32.Wlanapi`). All struct
           layouts are parsed by hand from the returned `Pointer` to
           keep struct knowledge in one place
           (`WindowsWlanScanner.parseEntry`).
- [x] 2.2  `WifiScanService` runs a single-thread `ScheduledExecutor`:
           passive cache poll every 1 s, active radio scan trigger
           every 5 s. Latest snapshot in an `AtomicReference` so UI
           reads are lock-free. Falls back to `NoOpWifiScanner` on
           non-Windows or when the WLAN service is stopped (factory
           swallows the load failure).
- [x] 2.3  AP list `TableView` inside the Wi-Fi tab with columns
           SSID / BSSID / RSSI dBm / Channel / Band / PHY type.
           Sortable per column; default sort RSSI descending. Hidden
           SSIDs render as `(hidden)`. Channel and band are derived
           from the reported centre frequency in `WifiAccessPoint`
           (`channel()` / `band()`).
- [x] 2.4  Status line above the table:
           `Visible APs: 2.4 GHz: N  /  5 GHz: N  /  6 GHz: N (total)`.
           Shown unconditionally so an empty per-band count still
           reads as `0`.

**Acceptance**: with a real Wi-Fi adapter present the AP list updates
once per second and matches what `netsh wlan show networks mode=bssid`
reports.

**Notes / limitations**
- Channel-width and security are *not* shown in v1. Both require
  parsing the IE blob (HT/VHT/HE capabilities, RSN element) which
  doubles the scanner code and is only useful once we have AP markers
  on the chart that visualise the width box (Step 3.1). Will revisit
  there.
- The active scan request hits the Wi-Fi radio every 5 seconds. If
  this turns out to interfere visibly with the HackRF spectrum we add
  a "passive only" toggle, but in casual testing it is below the SDR
  noise floor.
- `WindowsWlanScanner` enumerates interfaces once at construction. USB
  Wi-Fi adapters that arrive *after* the app started will be ignored
  until the app is restarted. Re-enumeration on hot-plug is a small
  follow-up but not in Step 2 scope.

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
