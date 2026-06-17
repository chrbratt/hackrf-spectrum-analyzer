package jspectrumanalyzer.fx.profile;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Persists named settings profiles as one {@code .properties} file each under
 * the per-user application data directory
 * ({@code %APPDATA%\HackRF Spectrum Analyzer\profiles} on Windows, or
 * {@code ~/.hackrf-spectrum-analyzer/profiles} elsewhere).
 *
 * <p>One file per profile keeps every operation a trivial filesystem action:
 * "delete" removes a file, "rename" moves it, "list" enumerates the folder.
 * The file stem (sanitised name) is the profile's identity; that same string
 * is shown to the user, so what they type is what they see.
 */
public final class SettingsProfileStore {

    private static final Logger LOG = LoggerFactory.getLogger(SettingsProfileStore.class);
    private static final String EXT = ".properties";

    private final Path profilesDir;

    public SettingsProfileStore() {
        this(defaultBaseDir().resolve("profiles"));
    }

    SettingsProfileStore(Path profilesDir) {
        this.profilesDir = profilesDir;
    }

    private static Path defaultBaseDir() {
        String appData = System.getenv("APPDATA");
        if (appData != null && !appData.isBlank()) {
            return Paths.get(appData, "HackRF Spectrum Analyzer");
        }
        return Paths.get(System.getProperty("user.home"), ".hackrf-spectrum-analyzer");
    }

    /**
     * Reduce an arbitrary user-typed name to a safe, human-readable file stem.
     * Keeps letters, digits, spaces, hyphens and underscores; collapses
     * everything else to a single underscore. Returns {@code null} when the
     * result is empty so callers can reject it.
     */
    public static String sanitize(String name) {
        if (name == null) return null;
        String cleaned = name.trim().replaceAll("[^a-zA-Z0-9 _-]+", "_").trim();
        return cleaned.isEmpty() ? null : cleaned;
    }

    /** Profile names (file stems), sorted case-insensitively. */
    public List<String> listProfiles() {
        List<String> names = new ArrayList<>();
        if (!Files.isDirectory(profilesDir)) return names;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(profilesDir, "*" + EXT)) {
            for (Path file : stream) {
                String fileName = file.getFileName().toString();
                names.add(fileName.substring(0, fileName.length() - EXT.length()));
            }
        } catch (IOException e) {
            LOG.warn("Could not list profiles in {}", profilesDir, e);
        }
        names.sort(String.CASE_INSENSITIVE_ORDER);
        return names;
    }

    public boolean exists(String name) {
        String stem = sanitize(name);
        return stem != null && Files.exists(fileFor(stem));
    }

    /** Write (creating or overwriting) the profile. Returns the stored name. */
    public String save(String name, Properties props) throws IOException {
        String stem = requireValid(name);
        Files.createDirectories(profilesDir);
        try (OutputStream out = Files.newOutputStream(fileFor(stem))) {
            props.store(out, "HackRF Spectrum Analyzer profile: " + stem);
        }
        return stem;
    }

    public Properties load(String name) throws IOException {
        String stem = requireValid(name);
        Properties props = new Properties();
        try (InputStream in = Files.newInputStream(fileFor(stem))) {
            props.load(in);
        }
        return props;
    }

    public void delete(String name) throws IOException {
        String stem = requireValid(name);
        Files.deleteIfExists(fileFor(stem));
    }

    /** Rename {@code from} to {@code to}, overwriting an existing {@code to}. */
    public String rename(String from, String to) throws IOException {
        String fromStem = requireValid(from);
        String toStem = requireValid(to);
        Files.move(fileFor(fromStem), fileFor(toStem), StandardCopyOption.REPLACE_EXISTING);
        return toStem;
    }

    public Path getProfilesDir() {
        return profilesDir;
    }

    private Path fileFor(String stem) {
        return profilesDir.resolve(stem + EXT);
    }

    private static String requireValid(String name) {
        String stem = sanitize(name);
        if (stem == null) {
            throw new IllegalArgumentException("Invalid profile name: '" + name + "'");
        }
        return stem;
    }
}
