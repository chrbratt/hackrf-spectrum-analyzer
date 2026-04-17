# Hardware smoke test - new MSVC `hackrf-sweep.dll` against HackRF Pro

This is the manual checklist to run after `./gradlew buildHackrfSweepDll`
on a machine with a HackRF Pro plugged in (firmware 2026.01.3 confirmed).
None of this can be automated; it needs eyes on the GUI and a known RF
source.

## 0. Prerequisites

- [ ] `./gradlew test` passes (so JNA load-test confirms the new DLL
      binds without hardware).
- [ ] HackRF Pro has firmware 2026.01.3 (`hackrf_info.exe` from the
      official tools, or the firmware version shown in the device).
- [ ] Antenna or 50 Ω terminator attached - never sweep an open SMA
      with the RF amp on.

## 1. Cold start

- [ ] Run `./gradlew run` (or launch the jpackaged MSI) - the main
      window opens, no `UnsatisfiedLinkError` in the console.
- [ ] Status bar shows "Sweeping ..." within 2 seconds.
- [ ] First waterfall row paints across the full chart width (no
      half-width artefact).

## 2. Sweep correctness

Set Range = 88-108 MHz, RBW = 50 kHz, LNA = 16, VGA = 20, RF amp = off,
antenna power = off.

- [ ] Strong FM stations show up at the expected frequencies (within
      ±100 kHz; if everything is shifted by exactly 7.5 MHz the OFFSET
      constant got lost).
- [ ] Sweep rate counter in the status bar settles around 1500-3000
      sweeps/s (matches what the upstream tool prints).
- [ ] Peak marker tracks the strongest visible carrier.

## 3. Gain controls (the regression that bit us last time)

For each of the steps below, watch the noise floor in the chart:

- [ ] LNA slider: 0 / 8 / 16 / 24 / 32 / 40 dB - each step changes
      the floor; the app does not crash.
- [ ] VGA slider: 0 / 20 / 40 / 60 / 62 dB - each step changes the
      floor; the app does not crash.
- [ ] RF amp toggle: noise floor jumps by ~14 dB when enabled.
- [ ] Antenna power toggle: no crash, no DLL reload glitches.

(LNA must be a multiple of 8 and VGA a multiple of 2 - the sliders
already enforce this; we're checking that the new DLL respects it.)

## 4. Wide sweep stability

Set Range = 100-2700 MHz, RBW = 250 kHz.

- [ ] Sweep completes a full pass every ~0.5-1 s.
- [ ] Run for 5 minutes - sweep rate stays steady (no slow drop or
      "hackrf_is_streaming() == 0" message in the log).
- [ ] No memory growth visible in Task Manager beyond ~50 MB over
      that 5-minute window.

## 5. Restart loop

- [ ] Click Stop → Start ten times in a row. Every restart logs
      "hackrf_close() done" and the next sweep begins within 1 s.
- [ ] Unplug HackRF mid-sweep - app shows the open-failed message
      and backs off retries (no spam in the console).
- [ ] Re-plug HackRF - sweep resumes on the next retry.

## 6. Compare against upstream `hackrf_sweep.exe`

Just to anchor: take a 10-second binary capture with the upstream
2026.01.3 `hackrf_sweep.exe -B -f 88:108 -w 50000 -r ref.bin` and
overlay it (manually or in a scratch script) against the powers our
DLL reports for the same range. They should agree to within ±1 dB
across the band.

## 7. If anything fails

Capture the console output (the DLL writes to stderr) and the line in
`HackRFSweepNativeBridge` that the stack trace points at, then revert
to the legacy DLL by deleting `build/native/dist/` and re-running
`./gradlew run`. The legacy DLL is still in
`src/hackrf-sweep/lib/win32-x86-64/` and the build script falls back to
it automatically when the MSVC build is missing.
