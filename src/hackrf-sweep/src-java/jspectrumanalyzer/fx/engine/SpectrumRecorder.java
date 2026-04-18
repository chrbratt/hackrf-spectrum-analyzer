package jspectrumanalyzer.fx.engine;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jspectrumanalyzer.core.DatasetSpectrum;
import jspectrumanalyzer.fx.model.SettingsStore;

/**
 * Persists spectrum sweeps to a wide-format CSV while
 * {@link SettingsStore#isRecordedData()} is {@code true}. Each row is one
 * full sweep:
 *
 * <pre>
 * # HackRF Spectrum Analyzer recording
 * # started: 2026-04-18T12:34:56.789
 * # bins: 1660  rbw_hz: 50000  freq_shift_mhz: 0
 * timestamp_iso,2400.025,2400.075,...
 * 2026-04-18T12:34:57.123,-78.4,-77.9,...
 * </pre>
 *
 * <p>Design choices (kept deliberately small):
 * <ul>
 *   <li>Wide format: matches what {@code pandas.read_csv} / Excel /
 *       gnuplot expect; one row per sweep is far smaller on disk than
 *       the long {@code (ts, freq, dbm)} alternative.</li>
 *   <li>Bin layout is captured in the header. If a subsequent frame has a
 *       different bin count (user changed RBW or switched preset
 *       mid-recording) we rotate the file: close the current one and start
 *       a fresh one with the new header. This keeps every output file
 *       parseable as a single rectangular table.</li>
 *   <li>I/O happens on the engine's processing thread. {@link BufferedWriter}
 *       absorbs the per-sweep cost so this stays O(n) in bins, not bytes
 *       written-through.</li>
 *   <li>Any {@link IOException} stops the recording and clears the model
 *       flag so the UI button flips back to "Record data" - no silent
 *       failures.</li>
 * </ul>
 */
public final class SpectrumRecorder {

    private static final Logger LOG = LoggerFactory.getLogger(SpectrumRecorder.class);

    private static final DateTimeFormatter TS_FILE =
            DateTimeFormatter.ofPattern("yyyy-MM-dd-HH-mm-ss", Locale.ROOT);
    private static final DateTimeFormatter TS_ROW =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS", Locale.ROOT);

    private final SettingsStore settings;
    private final AtomicReference<ActiveRecording> active = new AtomicReference<>();

    public SpectrumRecorder(SettingsStore settings, SpectrumEngine engine) {
        this.settings = settings;
        engine.addFrameConsumer(this::onFrame);
        settings.isRecordedData().addListener(this::onToggle);
    }

    private void onToggle() {
        if (!settings.isRecordedData().getValue()) {
            closeActive("user stopped");
        }
    }

    private void onFrame(SpectrumFrame frame) {
        if (!settings.isRecordedData().getValue()) {
            return;
        }
        DatasetSpectrum ds = frame.dataset;
        if (ds == null) return;
        int bins = ds.spectrumLength();
        if (bins <= 0) return;

        ActiveRecording rec = active.get();
        if (rec == null || rec.bins != bins) {
            // Either first frame or the layout changed (RBW / plan switch).
            // In both cases we want a fresh file with a header that matches
            // the data rows that follow it.
            closeActive("layout changed");
            rec = openNew(ds);
            if (rec == null) return;
            active.set(rec);
        }

        try {
            rec.writeRow(ds);
        } catch (IOException ex) {
            LOG.error("SpectrumRecorder: write failed, stopping recording", ex);
            closeActive("write failed: " + ex.getMessage());
            // Flip the model so the UI button returns to "Record data" and
            // the user notices something broke.
            settings.isRecordedData().setValue(false);
        }
    }

    private ActiveRecording openNew(DatasetSpectrum ds) {
        try {
            LocalDateTime now = LocalDateTime.now();
            Path file = Paths.get(System.getProperty("user.dir"),
                    "hackrf-spectrum-" + TS_FILE.format(now) + ".csv");
            BufferedWriter w = Files.newBufferedWriter(file, StandardCharsets.UTF_8);
            ActiveRecording rec = new ActiveRecording(file, w, ds.spectrumLength());
            rec.writeHeader(ds, now);
            LOG.info("SpectrumRecorder: started -> {}", file.toAbsolutePath());
            return rec;
        } catch (IOException ex) {
            LOG.error("SpectrumRecorder: cannot open output file", ex);
            settings.isRecordedData().setValue(false);
            return null;
        }
    }

    private void closeActive(String reason) {
        ActiveRecording rec = active.getAndSet(null);
        if (rec == null) return;
        try {
            rec.close();
            LOG.info("SpectrumRecorder: closed ({}) -> {}", reason,
                    rec.file.toAbsolutePath());
        } catch (IOException ex) {
            LOG.warn("SpectrumRecorder: close failed", ex);
        }
    }

    private static final class ActiveRecording {
        final Path file;
        final BufferedWriter writer;
        final int bins;

        ActiveRecording(Path file, BufferedWriter writer, int bins) {
            this.file = file;
            this.writer = writer;
            this.bins = bins;
        }

        void writeHeader(DatasetSpectrum ds, LocalDateTime started) throws IOException {
            writer.write("# HackRF Spectrum Analyzer recording");
            writer.newLine();
            writer.write("# started: " + TS_ROW.format(started));
            writer.newLine();
            writer.write("# bins: " + bins
                    + "  rbw_hz: " + (long) ds.getFFTBinSizeHz()
                    + "  freq_shift_mhz: " + ds.getFreqShift());
            writer.newLine();

            StringBuilder sb = new StringBuilder(bins * 10);
            sb.append("timestamp_iso");
            for (int i = 0; i < bins; i++) {
                sb.append(',');
                appendFreq(sb, ds.rfFrequencyMHzAt(i));
            }
            writer.write(sb.toString());
            writer.newLine();
        }

        void writeRow(DatasetSpectrum ds) throws IOException {
            float[] values = ds.getSpectrumArray();
            int n = Math.min(values.length, bins);
            StringBuilder sb = new StringBuilder(bins * 8);
            sb.append(TS_ROW.format(LocalDateTime.ofInstant(
                    Instant.now(), ZoneId.systemDefault())));
            for (int i = 0; i < n; i++) {
                sb.append(',');
                appendDbm(sb, values[i]);
            }
            writer.write(sb.toString());
            writer.newLine();
        }

        void close() throws IOException {
            writer.flush();
            writer.close();
        }

        private static void appendFreq(StringBuilder sb, double mhz) {
            sb.append(String.format(Locale.ROOT, "%.4f", mhz));
        }

        private static void appendDbm(StringBuilder sb, float v) {
            sb.append(String.format(Locale.ROOT, "%.2f", v));
        }
    }
}
