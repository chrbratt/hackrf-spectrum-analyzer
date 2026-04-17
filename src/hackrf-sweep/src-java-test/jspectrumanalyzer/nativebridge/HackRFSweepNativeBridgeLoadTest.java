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
 * Verifies that the bundled hackrf-sweep.dll (whichever build the
 * `jna.library.path` system property currently points at - MSVC or
 * the legacy MinGW one) loads under JNA and exports the two symbols
 * the rest of the app actually calls.
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
        assumeTrue(Files.exists(dll), "hackrf-sweep.dll missing at " + dll);

        NativeLibrary lib = NativeLibrary.getInstance(LIBRARY_NAME);
        // If either symbol is missing JNA throws UnsatisfiedLinkError, which
        // surfaces here as a test failure with a clear stack trace.
        assertNotNull(lib.getFunction("hackrf_sweep_lib_start"),
                "hackrf_sweep_lib_start export not found");
        assertNotNull(lib.getFunction("hackrf_sweep_lib_stop"),
                "hackrf_sweep_lib_stop export not found");
    }
}
