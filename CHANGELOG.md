# Changelog

All notable changes since this fork was created. Everything below was
developed and verified in collaboration; the previous upstream
`v2.21`/`v2.10` commits are not described here.

## [3.0.0] - Phase 1 rewrite + 2026.01.3 native upgrade

Two large initiatives landed in this version:

1. A full **JavaFX 21 rewrite** of the Swing UI (sole commit
   `Rewrite phase1`, ~3 994 LOC added / ~3 851 LOC removed across 58
   files) with the explicit goal of fixing the buggy frequency
   selectors and modernising the look/feel.
2. A **native-tools upgrade** from the legacy MinGW-built
   `hackrf-sweep.dll` (HackRF release 2023.01.1) to a fresh **MSVC**
   build against the official **HackRF 2026.01.3** source, with a
   reproducible CMake + vcpkg pipeline driven from Gradle.

### Build system

- Replaced the Ant build (`hackrf_sweep_spectral_analyzer_buildjar_ant.xml`,
  jar-in-jar-loader, MigLayout, Xuggler, old slf4j/logback/JFreeChart
  jars under `src/hackrf-sweep/lib/`) with a **Gradle 9 / JDK 21**
  build.
- Added the **Gradle wrapper** (`gradlew`, `gradlew.bat`, `gradle/wrapper/`)
  so the project builds without a system Gradle install.
- New `build.gradle.kts` wires:
  - `application` + `org.openjfx.javafxplugin` (JavaFX 21.0.5
    `controls`, `graphics`, `swing`, `fxml`),
  - `JFreeChart 1.5.5` + `org.jfree.chart.fx 2.0.1`,
  - `ControlsFX 11.2.1`,
  - `JNA 5.14.0` for the native bridge,
  - `Logback 1.5.6` runtime,
  - `JUnit Jupiter 5.10.2` for unit tests.
- `processResources` runs a `generateFreqIndex` task that enumerates
  `src/hackrf-sweep/freq/*.csv` into `freq/index.txt` so the runtime
  can discover bandplans when the app runs from a jar.
- `installDist` and the `jpackage*` tasks bundle the native DLLs from
  whichever directory contains a fresh `hackrf-sweep.dll`
  (`build/native/dist/` if present, otherwise the legacy
  `src/hackrf-sweep/lib/win32-x86-64/`).
- `startScripts` injects `-Djna.library.path=%APP_HOME%/native` into the
  Windows/Unix launchers.

### Packaging

- New `jpackageWinApp` task — produces a self-contained Windows
  app-image folder with a private JRE; no installer toolchain required.
- New `jpackageWinMsi` task — produces a Windows MSI with a private
  JRE; requires the WiX Toolset on `PATH`.
- Readme documents the WiX 3.x install (`candle.exe` / `light.exe`).
- CSV resources (`presets.csv`, `freq/*.csv`) are loaded from the
  classpath so users no longer need to set CWD or
  `-Dhackrf.resources.dir`.

### JavaFX UI rewrite (replaces all Swing code)

- Removed the entire Swing layer:
  - `HackRFSweepSpectrumAnalyzer` (1 974-line god class),
  - `HackRFSweepSettingsUI`, `FrequencySelectorPanel`,
    `FrequencySelectorRangeBinder`, `CircleDrawer`, `WaterfallPlot`,
    `MVCController`.
- Introduced new package `jspectrumanalyzer.fx`:
  - `Main` / `FxApp` — JavaFX entry point + `Stage` setup.
  - `MainWindow` — top-level layout with `SplitPane`, status bar,
    chart toolbar.
  - `chart/SpectrumChart` — JFreeChart hosted in a `ChartViewer`.
  - `chart/WaterfallCanvas` — Canvas-based waterfall sized to the
    real plot pixel width.
  - `chart/PersistentDisplayController` — persistent display sized
    correctly to the chart and reset cleanly on Clear.
  - `engine/SpectrumEngine` + `SpectrumFrame` — sweep loop and frame
    buffering.
  - `frequency/FrequencyRangeSelector` + `FrequencyRangeValidator`
    — replacement for the buggy Swing selector that used to hide
    the selected frequency values.
  - `model/FxModelBinder` — wraps `ModelValue<T>` as JavaFX
    `Property<T>` with a re-entrancy guard so two-way binding does
    not infinite-loop.
  - `model/SettingsStore` — single source of truth for UI state;
    sane palette defaults (start step 10, size step 5).
  - `theme/dark.css` — modern dark theme.
  - `ui/ScanTab`, `ParamsTab`, `DisplayTab`, `RecordingTab` — Scan
    (gain/antenna), Params (peak/avg, offset, power-flux cal, spur
    filter), Display (waterfall + persistent), Recording (data + the
    disabled video controls).
  - `ui/ChartToolbar` — Freeze, Clear, Waterfall and Persistent
    toggles above the chart stack.
  - `util/FxControls` — shared `TextFormatter`, slider helpers,
    keyboard-shortcut helpers.

### UX fixes and polish

- **Frequency selector** rewritten with an explicit validator and
  unit tests; the bug where dragging hid the selected MHz values is
  gone.
- **Pause renamed to Freeze** (display only) with a tooltip clarifying
  that the engine keeps sweeping in the background.
- **RBW preset chip buttons** (10 / 50 / 100 / 250 / 500 / 1000 kHz).
- **Pan buttons** now show the next frequency range in their
  tooltips.
- **Tooltips** added to LNA, VGA, antenna power, RF amp, Speed,
  Palette start, Palette size, Persistence time.
- **Status bar**: vertical separators, bold peak readout,
  right-aligned stats.
- **Single ControlsFX `RangeSlider`** replaces the two confusing
  palette sliders.
- **Display** tab renamed (was Waterfall/REC) and a dedicated
  **Recording** tab added.
- **Scene-level keyboard shortcuts**: Space = Freeze, C = Clear,
  W = Waterfall toggle. Shortcuts are skipped when a text field is
  focused.

### Performance

- **JFreeChart entity collection disabled** — eliminates ~1.8M
  `ChartEntity` allocations per second during fast sweeps.
- **Cached spectrum X-axis arrays** + new snapshot buffers so the
  chart hot path no longer clones arrays on every refresh.
- **`Platform.runLater` chart refreshes coalesced** so a slow UI
  thread cannot let the queue grow unbounded (root cause of the
  "stutters after a minute" report).
- `WaterfallCanvas` paints 1:1 and drops history on resize so dragging
  the `SplitPane` divider no longer leaves banding/colour smear.
- `WaterfallCanvas.clearHistory()`, `PersistentDisplay.reset()` and
  new engine `resetTraces()` make the Clear button instant and
  consistent across all overlays.

### Stability / correctness

- **LNA / VGA gain sliders snap to hardware steps** (LNA 8 dB,
  VGA 2 dB) so off-step values never reach `hackrf_sweep` (was
  causing `hackrf_set_*_gain()` failures and crashes when sliding
  too fast).
- **VGA range extended 0-60 → 0-62 dB** to match what the hardware
  actually accepts.
- **`hackrf_open` retry back-off** — exponential delay on repeated
  failures, suppressed the upstream usage-text spam in the console.
- **Replaced `java.util.Observable`** in `ModelValue` with a plain
  listener list (deprecated in JDK 9+).
- The Record video button + video resolution/fps fields are
  **disabled** in the UI — the underlying ScreenCapture/Xuggler
  pipeline doesn't have a JavaFX-native rewrite yet (kept as a
  follow-up task; the legacy classes remain in `capture/**` for
  reference, excluded from compilation).

### Native HackRF library upgrade (MSVC + vcpkg, HackRF 2026.01.3)

The legacy DLL was a MinGW build of `hackrf_sweep` from 2023.01.1.
This release ships a fresh MSVC build of the **2026.01.3** sources
together with a fully reproducible build pipeline.

New module under `src/hackrf-sweep/native/`:

- `hackrf_sweep.h` — public C ABI (`hackrf_sweep_lib_start` /
  `_stop`) with `__declspec(dllexport)` + `__cdecl` for JNA.
- `hackrf_sweep_lib.c` — library-mode port of upstream
  `host/hackrf-tools/src/hackrf_sweep.c` (release 2026.01.3). Changes
  vs upstream:
  - `main()` and the getopt loop are compiled out; parameters arrive
    through the library entry point instead.
  - `binary_output` block delivers FFT bins via a user-supplied
    callback instead of `fwrite`.
  - All file-scope state is `static` so the DLL doesn't leak globals.
  - `usleep_ms` shim for MSVC (no POSIX `usleep`).
  - Calls `fftwf_import_system_wisdom()` automatically — free FFT
    speedup if the user has a wisdom file installed.
  - `outfile` reused as a non-NULL sentinel so upstream's NULL check
    in `rx_callback` still passes.
- `CMakeLists.txt` — builds `libhackrf` (`hackrf-2026.01.3/host/libhackrf/src/hackrf.c`)
  as a static lib in-tree, links it into our DLL together with vcpkg's
  `fftw3f`, `libusb-1.0` and `pthreadVC3`, and stages all four
  runtime DLLs into `build/native/dist/` for a single-folder pickup.
- `BUILD_NATIVE.md` — one-time toolchain setup (Visual Studio 2022
  Build Tools, vcpkg, the three vcpkg packages) and the full rebuild
  command sequence.
- `HARDWARE_SMOKE_TEST.md` — manual checklist to run on a real
  HackRF Pro after rebuilding the DLL.

Gradle integration:

- New `buildHackrfSweepDll` task drives `cmake -G "Visual Studio 17 2022"`
  + `cmake --build … --config Release` end-to-end.
- `nativeLibDir` auto-detects `build/native/dist/hackrf-sweep.dll`
  and falls back to the legacy MinGW DLL when missing, so the rest
  of the build keeps working out of the box.

### Tests

- `FrequencyRangeValidatorTest` — 11 cases covering the new selector.
- `FxControlsTest` — 5 cases for the shared FX helpers.
- `ModelValueTest` — 7 cases verifying the new listener list.
- `HackRFSweepNativeBridgeLoadTest` — uses `jna.library.path` to load
  whichever `hackrf-sweep.dll` is currently bundled and asserts that
  both exported entry points resolve. Passes against the new MSVC
  DLL, which proves the entire load chain (our wrapper +
  `fftw3f.dll` + `libusb-1.0.dll` + `pthreadVC3.dll` + UCRT) is
  satisfied without HackRF hardware attached.

All 24 tests pass on Windows 10/11 + JDK 21.

### Removed

- `HackRFSweepSpectrumAnalyzer` (1 974 lines), `HackRFSweepSettingsUI`,
  `FrequencySelectorPanel`, `FrequencySelectorRangeBinder`,
  `CircleDrawer`, Swing `WaterfallPlot`, `MVCController`.
- Ant build (`hackrf_sweep_spectral_analyzer_buildjar_ant.xml`).
- jar-in-jar-loader.
- Bundled `jcommon-1.0.17.jar`, `jfreechart-1.0.14.jar`,
  `jna-5.13.0.jar`, `jnaerator-0.13-…-shaded.jar`,
  `logback-classic-1.0.0.jar`, `logback-core-1.0.0.jar`,
  `slf4j-api-1.6.4.jar`, `xuggle-xuggler-5.4.jar`,
  `miglayout15-swing.jar`, `miglayout-src.zip`.

### Known follow-ups

- **Capture rewrite**: `capture/ScreenCapture`, `ScreenCaptureH264`
  and `GifSequenceWriter` need to be reimplemented against JavaFX
  `WritableImage`/`SnapshotParameters`; the Record video button is a
  no-op until then.
- **Hardware smoke test on HackRF Pro**: see
  `src/hackrf-sweep/native/HARDWARE_SMOKE_TEST.md`. Required before
  we tag the new DLL as the default in a release build.
