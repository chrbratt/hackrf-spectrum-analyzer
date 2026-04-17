# Building `hackrf-sweep.dll` from source (MSVC + vcpkg)

The Java/JavaFX side of this project talks to the HackRF through a JNA
binding to a small C library called `hackrf-sweep.dll`. That DLL is a
library-mode build of the upstream `hackrf_sweep` tool, statically
linked against `libhackrf` and dynamically linked against `fftw3f`,
`libusb-1.0` and `pthreadVC3`.

The DLL that ships in `src/hackrf-sweep/lib/win32-x86-64/` was built
with MinGW years ago. This document describes the modern, reproducible
**MSVC** build used by the `buildHackrfSweepDll` Gradle task.

Source under `src/hackrf-sweep/native/`:

| File                  | What it is                                           |
| --------------------- | ---------------------------------------------------- |
| `hackrf_sweep.h`      | Public C ABI exported by the DLL.                    |
| `hackrf_sweep_lib.c`  | Library-mode port of upstream `hackrf_sweep.c`.      |
| `CMakeLists.txt`      | Build script (libhackrf static + our DLL).           |

## 1. One-time toolchain setup

Tested with the versions in parentheses; newer should also work.

1. **Visual Studio 2022 Build Tools** with the *Desktop development
   with C++* workload (MSVC 19.44, Windows SDK 10.0.26100).
   Smaller alternative: install just the C++ Build Tools, the
   Windows 10 SDK and CMake (CMake is bundled with the C++ workload).

2. **vcpkg** for managing the native deps.

   ```powershell
   git clone https://github.com/microsoft/vcpkg C:\vcpkg
   C:\vcpkg\bootstrap-vcpkg.bat
   ```

   Set `VCPKG_ROOT` if you put it elsewhere; the Gradle task defaults
   to `C:\vcpkg`.

3. **Native libraries from vcpkg** (x64-windows triplet):

   ```powershell
   C:\vcpkg\vcpkg.exe install pthreads:x64-windows
   C:\vcpkg\vcpkg.exe install libusb:x64-windows
   C:\vcpkg\vcpkg.exe install fftw3:x64-windows
   ```

   Total install time is ~1-2 minutes on a warm machine.

4. **HackRF release source** - extract the official 2026.01.3 release
   into the repo so its `host/libhackrf/src/hackrf.c` can be compiled
   into the DLL. The expected layout is:

   ```
   <repo>/
       hackrf-2026.01.3/hackrf-2026.01.3/host/libhackrf/src/hackrf.c
   ```

   Override with `-PhackrfSourceDir=<absolute path>` if you keep the
   source somewhere else.

## 2. Building the DLL

Single command from the repo root:

```powershell
.\gradlew.bat buildHackrfSweepDll
```

The task

1. runs `cmake -G "Visual Studio 17 2022" -A x64` against
   `src/hackrf-sweep/native/` with the vcpkg toolchain file,
2. builds `Release/hackrf_static.lib` (libhackrf compiled in-tree),
3. links `dist/hackrf-sweep.dll` against it + the vcpkg deps,
4. copies the four runtime DLLs that the new DLL imports next to it.

Output:

```
build/native/dist/
    hackrf-sweep.dll      <- our wrapper, exports hackrf_sweep_lib_*
    fftw3f.dll            <- single-precision FFTW (vcpkg)
    libusb-1.0.dll        <- USB transport (vcpkg)
    pthreadVC3.dll        <- POSIX threads for Windows (vcpkg)
```

## 3. How the rest of the build picks up the new DLL

`build.gradle.kts` checks for `build/native/dist/hackrf-sweep.dll` at
configuration time:

* if present, both `run` and `stageJpackageInput` use that directory
  as `jna.library.path` and copy it into the MSI / app-image,
* otherwise it falls back to the legacy `src/hackrf-sweep/lib/win32-x86-64/`
  DLLs that ship in the repo.

So a typical "ship a new release" cycle is:

```powershell
.\gradlew.bat buildHackrfSweepDll
.\gradlew.bat jpackageWinMsi
```

## 4. Verifying the DLL loads under JNA

Without HackRF hardware:

```powershell
.\gradlew.bat test --tests *HackRFSweepNativeBridgeLoadTest*
```

The test resolves `hackrf-sweep.dll` against the active
`jna.library.path`, asks JNA to look up both exported entry points, and
fails if either symbol is missing or if any sibling DLL (fftw3f, libusb,
pthread) fails to load.

With HackRF hardware: see `HARDWARE_SMOKE_TEST.md`.

## 5. What the port changed vs upstream `hackrf_sweep.c`

* `main()` and the `getopt` loop are compiled out; parameters come
  through `hackrf_sweep_lib_start(...)`.
* The `binary_output` block delivers FFT bins to a user-supplied
  callback instead of writing to `outfile`.
* `outfile` is reused as a non-NULL sentinel because upstream's
  `rx_callback` rejects the transfer when it's `NULL`.
* `usleep_ms` shim added so the polling loop compiles under MSVC
  (which has no POSIX `usleep`).
* On entry we call `fftwf_import_system_wisdom()` so any installed
  wisdom file is picked up automatically (no `-W` argument needed).
* All file-scope state is `static` so the DLL doesn't leak globals
  into clients that link against it.

The full diff against `hackrf-2026.01.3/host/hackrf-tools/src/hackrf_sweep.c`
is the file `hackrf_sweep_lib.c` itself - it was rewritten rather than
maintained as a patch because the upstream layout changed enough that
a `.patch` would be harder to read than the result.
