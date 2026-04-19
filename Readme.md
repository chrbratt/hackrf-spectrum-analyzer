# HackRF Spectrum Analyzer for Windows
based on Pavsa's HackRF hackrf_sweep Spectrum Analyzer

## Run from source

```
.\gradlew.bat run
```

## Build from source

The build requires JDK 21 (Gradle's toolchain support will download it if missing).
Source layout: CSV resources (`presets.csv`, `freq/*.csv`) live on the classpath
inside the application jar, so no external resource folder is needed.

| Task                      | Output                                                       |
| ------------------------- | ------------------------------------------------------------ |
| `.\gradlew.bat build`     | Compiles, runs tests, produces `build/libs/*.jar`.           |
| `.\gradlew.bat installDist` | Self-contained folder in `build/install/...` (no JRE bundled). |
| `.\gradlew.bat jpackageWinApp` | Self-contained Windows **app-image** (folder) with private JRE. No installer tools required. Output: `build/jpackage/HackRF Spectrum Analyzer/`. |
| `.\gradlew.bat jpackageWinMsi` | Windows **MSI installer** with private JRE. Requires the WiX Toolset (see below). Output: `build/jpackage/*.msi`. |
| `.\gradlew.bat buildHackrfSweepDll` | Rebuild the bundled `hackrf-sweep.dll` from the upstream HackRF 2026.01.3 source with MSVC. See [src/hackrf-sweep/native/BUILD_NATIVE.md](src/hackrf-sweep/native/BUILD_NATIVE.md) for the one-time toolchain setup (MSVC + vcpkg). The other tasks above pick up the freshly built DLL automatically; if it's missing they fall back to the legacy MinGW DLL in `src/hackrf-sweep/lib/win32-x86-64/`. |

### WiX Toolset (only for `jpackageWinMsi`)

`jpackage --type msi` invokes the WiX 3.x command-line tools (`candle.exe` /
`light.exe`). The Gradle task auto-detects WiX in PATH **and** in the
standard install locations (`C:\Program Files (x86)\WiX Toolset v3.*\bin\`,
same under `Program Files\`), and prepends the detected directory to the
jpackage subprocess PATH - so a fresh install works in the same shell
session, no logout / terminal restart needed.

Install WiX with one of:
```
winget install WiXToolset.WiXToolset
choco install wixtoolset
```
or download **WiX Toolset v3.14** (or later 3.x) from
https://github.com/wixtoolset/wix3/releases and run the installer with
defaults.

If WiX is missing the build prints exactly which paths were searched and
how to install it.

> `jpackageWinApp` does **not** need WiX and is a good way to smoke-test the
> packaged app without touching installer infrastructure.

### Screenshots:
![FULL_920-960MHz_demo](https://github.com/user-attachments/assets/63bb1506-ebf1-4002-b53a-cc934590743b)
![FULL_2400-2483MHz_demo3](https://github.com/user-attachments/assets/699d551f-88f3-4ad6-a107-120bf3b513e4)

### Download:
[Download the latest version](https://github.com/voxo22/hackrf-spectrum-analyzer/releases) 

### User manual:
See Wiki

### Features:
- Make your HackRF a semi-profi spectrum analyzer
- RBW from 3 kHz to 2 MHz
- Realtime / Peak / Average / Max Hold / Persistent scanning with adjustable timings
- Peak and Max Hold markers
- Customizable Frequency band presets with detail setting
- Customizable multicolored Frequency allocation bands -> you can make your own!
- Adjustable high resolution Waterfall Plot
- Widely adjustable live screen recording into GIF or MP4 video
- Data recording feature into CSV file with MaxFreq, TotalPower, PeakPower + adjustable timing (minutes, seconds, fractions)
- Power Calibration adjustment for RF Power Flux Density sum reading in µW/m²
- Spur filter (no DC) for removing spur artifacts
- Arrow left/right button, X-axis mouse drag for comfortable frequency range setting
- Spectrum zooming by mouse dragging, mouse wheel for quick zooming/unzooming
- Adjustable amplitude and average chart offset
- Selectable Frequency Shift for up/down-converters
- Switchable Datestamp
- hackrf_sweep integrated as a shared library

You can customize "presets.csv" file by adding or deleting requested rows. Follow the structure and column meaning.
Additionaly, in "freq" folder you can edit frequency allocation tables or make your own. "Slash" character (/) in text columns hyphenates rows.

### Requirements:
* HackRF One or HackRF Pro with firmware 2023.01.1 or newer (the bundled DLL is built against the [2026.01.3 release](https://github.com/greatscottgadgets/hackrf/releases) of the upstream tools).

### Installation:
Make sure HackRF is using at least the minimum firmware version (see above) 

1. Windows 10 x64 or later.
2. Install from one of:
   - The MSI produced by `.\gradlew.bat jpackageWinMsi` (bundles a private JRE; no separate Java install needed), or
   - The app-image folder produced by `.\gradlew.bat jpackageWinApp`, or
   - The zip produced by `.\gradlew.bat installDist` (requires a system-installed JDK 21+).
3. Connect and install HackRF as a libusb device
    - [Download Zadig](https://zadig.akeo.ie/) (or use packed one) and install
    - Goto Options and check List All Devices
    - Find "HackRF One" and select Driver "WinUSB" and click install
4. Launch "HackRF Spectrum Analyzer".

### License:
GPL v3
