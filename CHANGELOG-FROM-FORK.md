# Changelog from fork

A short, complete summary of every change made on this fork relative to the
upstream **[voxo22/hackrf-spectrum-analyzer v2.21](https://github.com/voxo22/hackrf-spectrum-analyzer)**
(itself a fork of `pavsa/hackrf-spectrum-analyzer`). Use [CHANGELOG.md](CHANGELOG.md)
for the long-form, chronological version with rationale per change.

Diff size: **+6 714 / −3 913 lines across 73 files**, two big initiatives plus
several follow-ups.

---

## At a glance

| Area | Before (upstream v2.21) | After (this fork) |
|---|---|---|
| UI toolkit | Swing + MigLayout | **JavaFX 21 + ControlsFX** |
| Build | Ant + bundled jars | **Gradle 9 + Gradle Wrapper** |
| JDK | 8 | **21** |
| Native DLL | MinGW build of HackRF 2023.01.1 | **MSVC build of HackRF 2026.01.3** (vcpkg-based, reproducible) |
| Hardware | HackRF One only | HackRF One **and HackRF Pro** (auto-detect, multi-device dropdown) |
| Sweep API | Single `start_low..high` range | Single range **+ stitched multi-range** (`hackrf_sweep_lib_start_multi`) |
| Auto-start | Sweep starts on launch | Explicit **Start / Stop button** |
| Tests | none | **33 JUnit 5 tests** |
| Packaging | One `.jar` | `installDist`, `jpackageWinApp` (private JRE), `jpackageWinMsi` |

---

## Build & packaging

- **Removed**: Ant build (`hackrf_sweep_spectral_analyzer_buildjar_ant.xml`),
  jar-in-jar-loader, all bundled jars in `src/hackrf-sweep/lib/` (jcommon,
  jfreechart 1.0.14, jna 5.13, jnaerator, logback 1.0, slf4j 1.6, xuggle,
  miglayout).
- **Added**: `build.gradle.kts`, Gradle wrapper, `settings.gradle.kts`,
  `gradle.properties`. Gradle 9 + JDK 21 toolchain.
- **New tasks**:
  - `buildHackrfSweepDll` &mdash; CMake/MSVC build of the bundled DLL.
  - `installDist` &mdash; portable folder.
  - `jpackageWinApp` &mdash; self-contained Windows app-image with private JRE.
  - `jpackageWinMsi` &mdash; Windows MSI installer (needs WiX 3.x).
  - `generateFreqIndex` &mdash; lists `freq/*.csv` so the runtime can find
    allocation tables when running from a jar.
- **Resources**: `presets.csv` and `freq/*.csv` are loaded from the classpath;
  no `-Dhackrf.resources.dir` or working-directory hack any more.

## JavaFX UI rewrite (replaces all Swing)

- **Removed Swing layer** (\~3 850 LOC):
  `HackRFSweepSpectrumAnalyzer` (1 974-line god class),
  `HackRFSweepSettingsUI`, `FrequencySelectorPanel`,
  `FrequencySelectorRangeBinder`, `CircleDrawer`, `WaterfallPlot`,
  `MVCController`.
- **New package `jspectrumanalyzer.fx`** (\~3 994 LOC):
  - `Main` / `FxApp` &mdash; JavaFX entry point.
  - `MainWindow` &mdash; `BorderPane` layout with split panes, status bar,
    chart toolbar.
  - `chart/SpectrumChart` &mdash; JFreeChart hosted in a `ChartViewer`.
  - `chart/WaterfallCanvas` &mdash; Canvas-based waterfall correctly sized to
    the real plot pixel width (the old version was always sized to primary
    screen width).
  - `chart/PersistentDisplayController` &mdash; persistent display
    correctly sized to the chart and reset cleanly on Clear.
  - `engine/SpectrumEngine` &mdash; UI-independent sweep loop with launcher /
    sweep / processing threads, request-coalescing reconciler, max-hold decay
    timer, exponential `hackrf_open` retry back-off.
  - `frequency/FrequencyRangeSelector` + `FrequencyRangeValidator` &mdash;
    replacement for the buggy Swing selector that hid the selected MHz values.
  - `model/FxModelBinder` &mdash; wraps `ModelValue<T>` as JavaFX `Property<T>`
    with a re-entrancy guard.
  - `model/SettingsStore` &mdash; single source of truth for UI state.
  - `theme/dark.css` &mdash; modern dark theme.
  - `ui/{Scan,Params,Display,Recording}Tab` &mdash; tabbed sidebar.
  - `ui/ChartToolbar` &mdash; Freeze, Clear, Waterfall, Persistent toggles.
  - `ui/DeviceSection` &mdash; HackRF dropdown + refresh.
  - `util/FxControls` &mdash; shared form helpers (slider, spinner, checkbox,
    tooltip, sectioning).

## UX changes & polish

- **Compact device row**: HackRF dropdown + Start/Stop + Refresh fit on one line.
- **Tooltips on every sidebar control** (LNA, VGA, antenna power, RF amp,
  RBW, samples, persistence time, palette, pan buttons, multi-range combo,
  allocation overlay, etc.).
- **Pause renamed to Freeze** with a tooltip clarifying that the engine
  keeps sweeping in the background.
- **RBW preset chip buttons** (10 / 50 / 100 / 250 / 500 / 1000 kHz).
- **Pan buttons** show the next frequency range in their tooltips.
- **Single ControlsFX `RangeSlider`** for the palette (replaces two confusing
  start/size sliders).
- **Status bar**: vertical separators, bold peak readout, right-aligned stats,
  shows the actual board name (e.g. `HW: PRALINE` for HackRF Pro).
- **Scene-wide keyboard shortcuts**: <kbd>Space</kbd> = Freeze, <kbd>C</kbd> =
  Clear, <kbd>W</kbd> = Waterfall toggle. Skipped while a text input is focused.
- **Display tab** renamed (was "Waterfall/REC"), dedicated **Recording tab**
  added.
- **Sane defaults out of the box**: Wi-Fi 2.4 GHz preset, RBW 50 kHz,
  LNA 24 dB, VGA 8 dB, internal RF amp ON, palette mapped to −100..−50 dBm.

## Restored from the original fork (regressed during the rewrite)

- **Drag-to-zoom on the spectrum chart** &mdash; left-drag on the plot picks
  a new x-axis window; on release the sweep restarts at the narrower span
  (faster sweep, finer effective resolution). Disabled while a stitched
  multi-range plan is active.
- **Mouse-wheel zoom** anchored to the cursor frequency (one tick = ±20 % span).
- **Right-click reset** &mdash; jumps back to the preset / range that was
  active when the window opened.
- **Frequency-allocation overlay** &mdash; the colored bands with labels
  (e.g. "GSM900 Telekom downlink", "Wi-Fi 2.4") above the spectrum, driven
  by the `freq/*.csv` files. Toggle + country picker live on the **Display**
  tab. Plan-aware: bands inside a stitched multi-range plan land in the
  right segment; bands that fall in a gap are clipped out.

## New: multi-band "stitched" scanning

Sweep multiple non-contiguous bands as one continuous chart, skipping the
unused gaps in between (e.g. Wi-Fi 2.4 + 5 + 6E in a single sweep with no
"dead air" between 2.484 and 5.120 GHz).

- **Native**: new `hackrf_sweep_lib_start_multi(num_ranges, pairs, …)` C ABI.
  The single-range entry point is now a thin wrapper.
- **JNA**: matching binding + a `start(plan, …)` overload on
  `HackRFSweepNativeBridge`.
- **Domain**: new `FrequencyPlan` class &mdash; ordered, validated, immutable
  segment list, with full RF↔logical MHz mapping (round-trip tested).
- **Chart**: new `StitchedNumberAxis` (custom `NumberAxis`) renders RF MHz
  on the labels even though the underlying x-data is logical, and paints
  dashed separator lines at every segment boundary.
- **Data path**: `DatasetSpectrum` is plan-aware. `frequencyAxisMHz` stores
  *logical* MHz so JFreeChart's value→pixel math doesn't need to know about
  gaps; ingestion uses `plan.rfHzToGlobalBin` to land each sample in the
  right bin.
- **UI**: new "Stitched plan" combo on the Scan tab. `Off` returns to
  single-range; `Wi-Fi 2.4 + 5 + 6E` (preset in `FrequencyMultiRangePreset`)
  enables stitched mode.

## Performance & stability

- **JFreeChart entity collection disabled** &mdash; eliminates ~1.8 M
  `ChartEntity` allocations per second during fast sweeps (was the source
  of the "stutters after a minute" reports).
- **Cached spectrum X-axis arrays** + snapshot buffers so the chart hot path
  no longer clones arrays on every refresh.
- **`Platform.runLater` chart refreshes coalesced** &mdash; one pending
  frame at a time, never lets the FX queue grow unbounded.
- **Waterfall canvas paints 1:1** and drops history on resize so dragging
  the split divider no longer leaves color smear.
- **`WaterfallCanvas.clearHistory()`, `PersistentDisplay.reset()`,
  `engine.resetTraces()`** make the Clear button instant and consistent
  across all overlays.
- **LNA / VGA gain sliders snap to hardware steps** (LNA 8 dB, VGA 2 dB)
  so off-step values never reach `hackrf_set_*_gain()` (was a sweep-crash
  trigger when sliding too fast).
- **VGA range extended 0–60 → 0–62 dB** to match what the hardware accepts.
- **`hackrf_open` retry with exponential back-off** &mdash; suppresses the
  upstream usage-text spam and recovers cleanly when the HackRF is briefly
  unplugged.
- **Replaced `java.util.Observable`** in `ModelValue` with a plain listener
  list (deprecated since JDK 9).
- **Removed lock contention from `HackRFSweepNativeBridge`** &mdash; class-level
  `synchronized` on `listDevices()` and `getOpenedInfo()` was holding the JNA
  lock during a sweep and freezing the FX thread; replaced with thread-safe
  callers.
- **Engine reconciler** &mdash; all start/stop now goes through one
  launcher-thread queue, so Stop never blocks the FX thread on `Thread.join`
  (was the deadlock that froze the app on Stop).

## Hardware support: HackRF Pro

- **Native**: `hackrf_sweep_lib_list_devices`, `hackrf_sweep_lib_get_opened_info`
  exported from the DLL; opens by serial.
- **Java**: `HackRFDeviceInfo` POJO and a non-blocking `listDevices()` /
  `getOpenedInfo()` on `HackRFSweepNativeBridge`.
- **UI**: `DeviceSection` shows a dropdown with every connected HackRF
  (One / Pro / Jawbreaker), refresh button, and the active board name in the
  status bar.

## Native HackRF library upgrade (HackRF 2026.01.3, MSVC + vcpkg)

New module under `src/hackrf-sweep/native/`:

- `hackrf_sweep.h` &mdash; public C ABI (`hackrf_sweep_lib_start`,
  `hackrf_sweep_lib_start_multi`, `hackrf_sweep_lib_stop`,
  `hackrf_sweep_lib_list_devices`, `hackrf_sweep_lib_get_opened_info`),
  exported with `__declspec(dllexport)` + `__cdecl` for JNA.
- `hackrf_sweep_lib.c` &mdash; library-mode port of upstream
  `host/hackrf-tools/src/hackrf_sweep.c` from release **2026.01.3**:
  - `main()` and the getopt loop are compiled out; parameters arrive
    through the library entry point.
  - `binary_output` block delivers FFT bins via a user-supplied callback
    instead of `fwrite`.
  - All file-scope state is `static` (no DLL-global leaks).
  - `usleep_ms` shim for MSVC (no POSIX `usleep`).
  - Auto-imports system FFTW wisdom (`fftwf_import_system_wisdom()`).
  - `outfile` reused as a non-NULL sentinel so upstream's NULL check still
    passes.
- `CMakeLists.txt` &mdash; builds `libhackrf` from the upstream sources as a
  static lib in-tree, links it together with vcpkg's `fftw3f`, `libusb-1.0`,
  `pthreadVC3`, and stages all four runtime DLLs into `build/native/dist/`.
- `BUILD_NATIVE.md` &mdash; one-time toolchain setup.
- `HARDWARE_SMOKE_TEST.md` &mdash; manual checklist to run on real hardware.

The Gradle integration auto-detects whether `build/native/dist/hackrf-sweep.dll`
exists and falls back to the legacy MinGW DLL when missing, so checkout-and-run
keeps working out of the box.

## Tests (none → 33)

- `FrequencyPlanTest` &mdash; 9 cases, RF↔logical mapping, gap handling,
  segment validation, native pair packing, discontinuity boundaries.
- `FrequencyRangeValidatorTest` &mdash; 11 cases for the new selector.
- `FxControlsTest` &mdash; 5 cases for shared FX helpers.
- `ModelValueTest` &mdash; 7 cases for the new listener list.
- `HackRFSweepNativeBridgeLoadTest` &mdash; 1 case that loads the freshly
  built DLL via JNA and asserts every expected export resolves (proves the
  native build chain &mdash; wrapper + `fftw3f.dll` + `libusb-1.0.dll` +
  `pthreadVC3.dll` + UCRT &mdash; is satisfied with no HackRF attached).

## Removed

- `HackRFSweepSpectrumAnalyzer` (1 974 lines), `HackRFSweepSettingsUI`,
  `FrequencySelectorPanel`, `FrequencySelectorRangeBinder`, `CircleDrawer`,
  Swing `WaterfallPlot`, `MVCController`.
- Ant build (`hackrf_sweep_spectral_analyzer_buildjar_ant.xml`).
- jar-in-jar-loader.
- Bundled jars: `jcommon-1.0.17.jar`, `jfreechart-1.0.14.jar`,
  `jna-5.13.0.jar`, `jnaerator-0.13-…-shaded.jar`, `logback-classic-1.0.0.jar`,
  `logback-core-1.0.0.jar`, `slf4j-api-1.6.4.jar`, `xuggle-xuggler-5.4.jar`,
  `miglayout15-swing.jar`, `miglayout-src.zip`.

## Known follow-ups

- **Capture rewrite**: `capture/ScreenCapture`, `ScreenCaptureH264` and
  `GifSequenceWriter` need a JavaFX `WritableImage`/`SnapshotParameters`
  rewrite; the Record-video button is intentionally disabled until then.
  The legacy classes remain in `capture/` for reference, excluded from
  compilation.
- **Per-segment zoom in stitched mode**: today drag/wheel zoom is disabled
  while a multi-range plan is active. Three viable variants exist (collapse
  to single-range, narrow only the hovered segment, narrow all segments);
  picking one needs UX work.
- **Hardware smoke test on HackRF Pro**: see
  `src/hackrf-sweep/native/HARDWARE_SMOKE_TEST.md`. Required before tagging
  the new DLL as the default in a release build.
