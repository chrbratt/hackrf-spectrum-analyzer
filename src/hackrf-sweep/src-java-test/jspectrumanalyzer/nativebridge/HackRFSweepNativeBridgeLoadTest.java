package jspectrumanalyzer.nativebridge;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.jupiter.api.Test;

import com.sun.jna.NativeLibrary;
import com.sun.jna.Platform;

/**
 * Verifies that the freshly built hackrf-sweep.dll exports every symbol
 * the JNA bridge expects, including the entry points added for HackRF Pro
 * support (device enumeration + serial-based open + post-open board info).
 *
 * No HackRF hardware is required. The test is silently skipped if the
 * native lib path doesn't exist (e.g. on a non-Windows CI runner).
 */
class HackRFSweepNativeBridgeLoadTest {

    private static final String LIBRARY_NAME = "hackrf-sweep";

    @Test
    void exportsExpectedEntryPoints() {
        assumeTrue(Platform.isWindows(), "hackrf-sweep.dll is Windows-only");

        String libPath = System.getProperty("jna.library.path");
        assumeTrue(libPath != null, "jna.library.path not set");
        Path dll = Paths.get(libPath, "hackrf-sweep.dll");
        assumeTrue(Files.exists(dll), "hackrf-sweep.dll missing at " + dll
                + " - run `gradlew buildHackrfSweepDll` first");

        NativeLibrary lib = NativeLibrary.getInstance(LIBRARY_NAME);
        // Missing exports surface as UnsatisfiedLinkError with a clear trace.
        assertNotNull(lib.getFunction("hackrf_sweep_lib_start"),
                "hackrf_sweep_lib_start export not found");
        assertNotNull(lib.getFunction("hackrf_sweep_lib_start_multi"),
                "hackrf_sweep_lib_start_multi export not found "
                        + "- DLL was built before stitched-axis multi-range "
                        + "support landed; rebuild via `gradlew buildHackrfSweepDll`.");
        assertNotNull(lib.getFunction("hackrf_sweep_lib_stop"),
                "hackrf_sweep_lib_stop export not found");
        assertNotNull(lib.getFunction("hackrf_sweep_lib_list_devices"),
                "hackrf_sweep_lib_list_devices export not found "
                        + "- DLL was built before HackRF Pro support landed; "
                        + "rebuild via `gradlew buildHackrfSweepDll`.");
        assertNotNull(lib.getFunction("hackrf_sweep_lib_get_opened_info"),
                "hackrf_sweep_lib_get_opened_info export not found "
                        + "- DLL was built before HackRF Pro support landed.");
    }
}
