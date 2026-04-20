package jspectrumanalyzer.fx.ui;

import java.util.List;

import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.Spinner;
import javafx.scene.control.SpinnerValueFactory;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.util.Duration;
import jspectrumanalyzer.fx.util.FxControls;
import jspectrumanalyzer.wifi.capture.CaptureStats;
import jspectrumanalyzer.wifi.capture.MonitorAdapter;
import jspectrumanalyzer.wifi.capture.MonitorModeCapture;
import jspectrumanalyzer.wifi.capture.MonitorModeException;

/**
 * Experimental Phase-2 UI: live counters for an Npcap monitor-mode
 * capture session.
 *
 * <p>Sits at the bottom of the {@link WifiWindow} so it's clearly
 * separated from the read-only Wi-Fi survey above. When the configured
 * {@link MonitorModeCapture} backend reports {@code isAvailable=false}
 * the whole section degrades to a single explanatory label - the user
 * never sees a non-functional Start button.
 *
 * <h2>Lifecycle</h2>
 * <ul>
 *   <li>Capture handle is owned by the panel; created on Start, closed on
 *       Stop, and force-stopped from {@link #shutdown()} when the parent
 *       window goes away.</li>
 *   <li>UI refresh runs at 4 Hz via a JavaFX {@link Timeline} - cheap
 *       enough to not steal cycles from the capture poller, but fast
 *       enough that the counter looks responsive.</li>
 *   <li>{@link CaptureStats#snapshot()} synchronises around the polling
 *       thread's writes, so we read it without further coordination.</li>
 * </ul>
 *
 * <h2>Why not pcap4j's PacketListener API?</h2>
 * The PacketListener pumps decoded {@code Packet} objects onto a
 * background thread, but the decoder allocates per-frame, which is
 * wasteful when all we need is one byte from the 802.11 header. Our
 * {@link CaptureStats#accept} reads that byte directly, so we wire the
 * raw {@code getNextRawPacket} loop instead - same correctness, an
 * order of magnitude less GC pressure on a busy office channel.
 */
public final class MonitorCapturePanel {

    private static final int UI_REFRESH_MS = 250;

    private final MonitorModeCapture capture;
    private final CaptureStats stats = new CaptureStats();

    private final VBox root = new VBox(8);

    private final ComboBox<MonitorAdapter> adapterCombo = new ComboBox<>();
    private final Spinner<Integer> channelSpinner = new Spinner<>();
    private final Button startStopButton = new Button("Start capture");
    private final Label statusLabel = new Label();
    private final Label totalLabel = new Label();
    private final Label typeBreakdownLabel = new Label();
    private final Label mgmtBreakdownLabel = new Label();
    private final Label rssiLabel = new Label();

    private final Timeline refreshTimer;
    private boolean running = false;

    public MonitorCapturePanel(MonitorModeCapture capture) {
        this.capture = capture;

        if (!capture.isAvailable()) {
            // Backend not ready (no Npcap, or NoOp implementation).
            // Show a single explanatory label and bail - no controls,
            // so there's nothing to misclick.
            Label notAvailable = new Label(
                    "Monitor-mode capture is not available on this system. "
                    + "Install Npcap with \"Support raw 802.11 traffic\" "
                    + "enabled and run the application as Administrator. "
                    + "See TODO-WIFI-PHASE-2.md for adapter requirements.");
            notAvailable.setWrapText(true);
            notAvailable.getStyleClass().add("preset-caption");
            root.getChildren().add(notAvailable);
            refreshTimer = null;
            return;
        }

        buildAdapterCombo();
        buildChannelSpinner();
        buildStartStopButton();
        buildLabels();

        // Build the row inline (rather than via FxControls.labeled which
        // returns a vertical caption-on-top VBox) so the adapter combo and
        // channel spinner sit side by side in one compact line.
        HBox controlsRow = new HBox(8,
                new Label("Adapter"), adapterCombo,
                new Label("Channel MHz"), channelSpinner,
                startStopButton);
        controlsRow.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(adapterCombo, Priority.ALWAYS);

        Label hint = new Label(
                "Captures raw 802.11 frames from the selected adapter. "
                + "Channel selection is OS-managed on Windows: pre-tune "
                + "the adapter via 'netsh wlan' or rely on whatever it "
                + "is currently parked on. Counters reset on each Start.");
        hint.setWrapText(true);
        hint.getStyleClass().add("preset-caption");

        root.setPadding(new Insets(0));
        root.getChildren().addAll(
                hint,
                controlsRow,
                statusLabel,
                totalLabel,
                typeBreakdownLabel,
                mgmtBreakdownLabel,
                rssiLabel);

        refreshTimer = new Timeline(new KeyFrame(
                Duration.millis(UI_REFRESH_MS), e -> refreshLabels()));
        refreshTimer.setCycleCount(Timeline.INDEFINITE);

        statusLabel.setText("Ready. Pick an adapter and click Start.");
        refreshLabels();
    }

    /** Root node so callers can drop the panel into a {@link VBox} section. */
    public javafx.scene.Node node() {
        return root;
    }

    /** Stop any running capture and release the OS handle. Idempotent. */
    public void shutdown() {
        if (refreshTimer != null) refreshTimer.stop();
        if (running) {
            try {
                capture.stop();
            } catch (RuntimeException ignored) {
                /* shutting down anyway */
            }
            running = false;
        }
    }

    // ---------------------------------------------------------------- UI build

    private void buildAdapterCombo() {
        List<MonitorAdapter> adapters = capture.listAdapters();
        adapterCombo.getItems().setAll(adapters);
        if (!adapters.isEmpty()) adapterCombo.getSelectionModel().select(0);
        adapterCombo.setMaxWidth(Double.MAX_VALUE);
        FxControls.withTooltip(adapterCombo,
                "Adapters reported by libpcap / Npcap. Not all of them "
                + "support monitor mode - if Start fails with 'rfmon mode "
                + "is not supported' try the other Wi-Fi NIC.");
        adapterCombo.setCellFactory(lv -> adapterCell());
        adapterCombo.setButtonCell(adapterCell());
    }

    private static javafx.scene.control.ListCell<MonitorAdapter> adapterCell() {
        return new javafx.scene.control.ListCell<>() {
            @Override
            protected void updateItem(MonitorAdapter item, boolean empty) {
                super.updateItem(item, empty);
                setText((empty || item == null) ? "" : item.description());
            }
        };
    }

    private void buildChannelSpinner() {
        // Wi-Fi 1 .. Wi-Fi 6E covers 2.412 - 7.125 GHz. The spinner is
        // informational only on Windows (libpcap can't tune the radio),
        // so we just clamp to the regulatory band edges to stop the
        // value from drifting into nonsense.
        channelSpinner.setValueFactory(
                new SpinnerValueFactory.IntegerSpinnerValueFactory(2400, 7125, 2412, 1));
        channelSpinner.setEditable(true);
        channelSpinner.setPrefWidth(110);
        FxControls.withTooltip(channelSpinner,
                "Reference channel for the capture session. On Windows "
                + "libpcap does not switch the radio; this value is "
                + "stamped into RadiotapFrame.channelMhz so downstream "
                + "consumers know which channel the capture matches.");
    }

    private void buildStartStopButton() {
        startStopButton.setOnAction(e -> {
            if (running) {
                stopCapture();
            } else {
                startCapture();
            }
        });
        startStopButton.setDisable(adapterCombo.getItems().isEmpty());
    }

    private void buildLabels() {
        statusLabel.getStyleClass().add("preset-caption");
        totalLabel.getStyleClass().add("preset-caption");
        typeBreakdownLabel.getStyleClass().add("preset-caption");
        mgmtBreakdownLabel.getStyleClass().add("preset-caption");
        rssiLabel.getStyleClass().add("preset-caption");
    }

    // ---------------------------------------------------------------- Lifecycle

    private void startCapture() {
        MonitorAdapter adapter = adapterCombo.getValue();
        if (adapter == null) {
            statusLabel.setText("Pick an adapter first.");
            return;
        }
        int channelMhz = channelSpinner.getValue();
        stats.reset();
        try {
            capture.start(adapter, channelMhz, frame -> {
                // Runs on the capture poll thread - keep it cheap.
                // CaptureStats.accept is synchronized; reads happen from
                // the FX timer at 4 Hz so the contention is negligible.
                stats.accept(frame);
            });
        } catch (MonitorModeException ex) {
            statusLabel.setText("Capture failed: " + ex.getMessage());
            return;
        }
        running = true;
        startStopButton.setText("Stop capture");
        statusLabel.setText(String.format(
                "Capturing on %s (channel %d MHz)...", adapter.description(), channelMhz));
        if (refreshTimer != null) refreshTimer.play();
    }

    private void stopCapture() {
        try {
            capture.stop();
        } catch (RuntimeException ex) {
            statusLabel.setText("Stop error: " + ex.getMessage());
        }
        running = false;
        startStopButton.setText("Start capture");
        if (refreshTimer != null) refreshTimer.stop();
        // Final tick so the user sees the last counter values without
        // having to wait for the next timer cycle that we just cancelled.
        Platform.runLater(this::refreshLabels);
        statusLabel.setText("Capture stopped.");
    }

    private void refreshLabels() {
        CaptureStats.Snapshot snap = stats.snapshot();
        totalLabel.setText(String.format(
                "Frames: %,d total", snap.total()));
        typeBreakdownLabel.setText(String.format(
                "Type: mgmt=%,d  ctrl=%,d  data=%,d  ext=%,d",
                snap.mgmt(), snap.ctrl(), snap.data(), snap.ext()));
        mgmtBreakdownLabel.setText(String.format(
                "Mgmt subtypes: beacon=%,d  probe-req=%,d  probe-resp=%,d  deauth=%,d",
                snap.beacons(), snap.probeReq(), snap.probeResp(), snap.deauth()));
        if (snap.rssiByBssid().isEmpty()) {
            rssiLabel.setText("Per-BSSID RSSI: (no management frames yet)");
        } else {
            // Show up to the 5 strongest BSSIDs we've seen so the label
            // stays one line. Sorting in the UI thread is fine here -
            // the map is bounded by CaptureStats.MAX_BSSIDS = 64.
            String top = snap.rssiByBssid().entrySet().stream()
                    .sorted((a, b) -> Long.compare(b.getValue(), a.getValue()))
                    .limit(5)
                    .map(e -> e.getKey() + " " + e.getValue() + " dBm")
                    .reduce((a, b) -> a + "  |  " + b)
                    .orElse("");
            rssiLabel.setText("Top BSSIDs: " + top);
        }
    }
}
