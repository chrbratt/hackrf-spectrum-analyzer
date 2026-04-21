package jspectrumanalyzer.wifi.capture;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Thin wrapper around Npcap's {@code WlanHelper.exe} utility, which is
 * the only supported way to switch a Wi-Fi adapter's channel while
 * libpcap holds it in monitor mode on Windows.
 *
 * <h2>Why we need this</h2>
 * <p>libpcap on Windows opens the radio with {@code rfmon=true} and
 * sets the modulation, but it cannot tune the centre frequency - the
 * adapter stays parked on whatever channel the OS last associated on
 * (usually a 2.4 GHz primary). Without an active tune the user can
 * type "5590 MHz" into the panel and still see only 2.4 GHz traffic,
 * which is exactly the bug this class fixes.
 *
 * <h2>Contract</h2>
 * <ul>
 *   <li>The adapter must already be in monitor mode. Our capture path
 *       opens with {@code rfmon=true} before calling here, so by the
 *       time {@link #setFrequencyMhz} runs WlanHelper's
 *       "only works in monitor mode" precondition is satisfied.</li>
 *   <li>WlanHelper requires the same elevation as monitor capture
 *       itself (Administrator). If our process can open rfmon, it
 *       can also call WlanHelper.</li>
 *   <li>WlanHelper accepts both connection name and {@code {GUID}}.
 *       pcap4j hands us paths like {@code \Device\NPF_{GUID}}; we
 *       extract the brace-quoted GUID and pass that.</li>
 * </ul>
 *
 * <h2>Failure mode</h2>
 * <p>This class never throws to the caller - tuning is best-effort.
 * Returns a {@link TuneResult} the UI can surface verbatim. A missing
 * executable, a non-Wi-Fi adapter, or a frequency the driver does not
 * support all collapse to {@code success=false} with the underlying
 * message attached, so the user sees exactly what went wrong.
 */
public final class WlanHelper {

    private static final Logger LOG = LoggerFactory.getLogger(WlanHelper.class);

    /**
     * Common install locations for {@code WlanHelper.exe}. Standard
     * Npcap puts it under {@code System32\Npcap}; older / portable
     * installs sometimes drop it under Program Files. We probe in
     * order and use the first hit.
     */
    private static final List<Path> CANDIDATES = List.of(
            Paths.get(System.getenv("WINDIR") == null ? "C:\\Windows" : System.getenv("WINDIR"),
                    "System32", "Npcap", "WlanHelper.exe"),
            Paths.get("C:\\Program Files\\Npcap\\WlanHelper.exe"),
            Paths.get("C:\\Program Files (x86)\\Npcap\\WlanHelper.exe"));

    /** How long we wait for WlanHelper to finish before giving up. */
    private static final int PROCESS_TIMEOUT_SECONDS = 5;

    private WlanHelper() {}

    /**
     * Outcome of a tuning attempt. {@code success=true} means the
     * driver accepted the request; the {@code message} is whatever
     * WlanHelper printed on stdout (empty when it succeeded silently).
     * On failure {@code message} is the underlying error - missing
     * exe, timeout, non-zero exit, parsed driver error.
     */
    public record TuneResult(boolean success, String message) {
        public static TuneResult ok(String msg) { return new TuneResult(true, msg); }
        public static TuneResult fail(String msg) { return new TuneResult(false, msg); }
    }

    /** True when {@code WlanHelper.exe} is reachable on this machine. */
    public static boolean isAvailable() {
        return findExecutable().isPresent();
    }

    /** First {@code WlanHelper.exe} we find, or empty when Npcap is not installed. */
    public static Optional<Path> findExecutable() {
        for (Path p : CANDIDATES) {
            if (Files.isRegularFile(p)) return Optional.of(p);
        }
        return Optional.empty();
    }

    /**
     * Tune the adapter to {@code mhz}. Caller is expected to have
     * opened the adapter in monitor mode first (otherwise WlanHelper
     * refuses with "only works in monitor mode").
     *
     * @param adapterId pcap4j adapter identifier, e.g.
     *                  {@code \Device\NPF_{GUID}}; the GUID portion
     *                  is extracted automatically.
     * @param mhz       requested centre frequency in MHz.
     */
    public static TuneResult setFrequencyMhz(String adapterId, int mhz) {
        Optional<Path> exe = findExecutable();
        if (exe.isEmpty()) {
            return TuneResult.fail("WlanHelper.exe not found in any of: " + CANDIDATES);
        }
        String guid = extractGuid(adapterId);
        if (guid == null || guid.isBlank()) {
            return TuneResult.fail("Could not parse adapter GUID from id '" + adapterId + "'");
        }
        ProcessBuilder pb = new ProcessBuilder(
                exe.get().toString(), guid, "freq", String.valueOf(mhz));
        // Merge stderr into stdout: WlanHelper writes its 'success'
        // message to stdout but driver errors arrive via stderr, and
        // we want the user to see whichever one is more informative.
        pb.redirectErrorStream(true);
        try {
            Process p = pb.start();
            boolean finished = p.waitFor(PROCESS_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!finished) {
                p.destroyForcibly();
                return TuneResult.fail("WlanHelper timed out after "
                        + PROCESS_TIMEOUT_SECONDS + "s");
            }
            String output = new String(p.getInputStream().readAllBytes()).trim();
            int exit = p.exitValue();
            if (exit == 0) {
                LOG.info("WlanHelper tuned {} to {} MHz: {}", guid, mhz,
                        output.isEmpty() ? "(no output)" : output);
                return TuneResult.ok(output.isEmpty() ? "tuned to " + mhz + " MHz" : output);
            }
            LOG.warn("WlanHelper exit={} for {} freq {}: {}", exit, guid, mhz, output);
            return TuneResult.fail("WlanHelper exit " + exit
                    + (output.isEmpty() ? "" : " (" + output + ")"));
        } catch (IOException ex) {
            return TuneResult.fail("WlanHelper IO error: " + ex.getMessage());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return TuneResult.fail("WlanHelper interrupted");
        }
    }

    /**
     * Pull the {@code {GUID}} substring from a pcap4j adapter id.
     * Returns the input unchanged when no braces are present, so we
     * can also pass connection names (e.g. {@code "Wi-Fi"}) through.
     */
    static String extractGuid(String adapterId) {
        if (adapterId == null) return null;
        int open = adapterId.indexOf('{');
        int close = adapterId.lastIndexOf('}');
        if (open >= 0 && close > open) {
            return adapterId.substring(open, close + 1);
        }
        return adapterId;
    }
}
