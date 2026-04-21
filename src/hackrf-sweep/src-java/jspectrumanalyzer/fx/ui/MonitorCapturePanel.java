package jspectrumanalyzer.fx.ui;

import java.util.List;
import java.util.Map;

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
import javafx.scene.control.TitledPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.util.Duration;
import jspectrumanalyzer.fx.util.FxControls;
import jspectrumanalyzer.wifi.capture.BeaconStore;
import jspectrumanalyzer.wifi.capture.CaptureStats;
import jspectrumanalyzer.wifi.capture.MonitorAdapter;
import jspectrumanalyzer.wifi.capture.MonitorModeCapture;
import jspectrumanalyzer.wifi.capture.MonitorModeException;

/**
 * Phase-2 monitor-mode capture UI. Restructured around live "insight"
 * widgets rather than a wall of counter labels: an insight card up top
 * (plain English summary), a frame-type breakdown bar, top-talkers
 * table with inline RSSI/share bars, and a BSS Load gauge table.
 *
 * <h2>Lifecycle</h2>
 * <ul>
 *   <li>Capture handle is owned by the panel; created on Start, closed on
 *       Stop, and force-stopped from {@link #shutdown()} when the parent
 *       window goes away.</li>
 *   <li>UI refresh runs at 4 Hz via a JavaFX {@link Timeline}.
 *       {@link CaptureStats#snapshot()} synchronises with the polling
 *       thread's writes, so we read it without further coordination.</li>
 *   <li>Backend availability is checked once at construction; an
 *       unavailable backend collapses the panel to a single explanatory
 *       label so there is nothing to misclick.</li>
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
    private final BeaconStore beaconStore;

    private final VBox root = new VBox(10);

    private final ComboBox<MonitorAdapter> adapterCombo = new ComboBox<>();
    private final Spinner<Integer> channelSpinner = new Spinner<>();
    private final Button startStopButton = new Button("Start capture");
    private final Label statusLabel = new Label();

    /** Live insight card and visual widgets - see their own javadoc. */
    private final CaptureInsightCard insightCard;
    private final FrameTypeBar frameTypeBar;
    private final TopTalkersTable topTalkersTable;
    private final ApHealthTable apHealthTable;
    private final BssLoadTable bssLoadTable;
    private final Label hiddenList = new Label();

    private final Timeline refreshTimer;
    private boolean running = false;

    public MonitorCapturePanel(MonitorModeCapture capture, BeaconStore beaconStore) {
        this.capture = capture;
        this.beaconStore = beaconStore;
        this.insightCard = new CaptureInsightCard(beaconStore);
        this.frameTypeBar = new FrameTypeBar();
        this.topTalkersTable = new TopTalkersTable(beaconStore);
        this.apHealthTable = new ApHealthTable(beaconStore);
        this.bssLoadTable = new BssLoadTable(beaconStore);

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
        statusLabel.getStyleClass().add("preset-caption");
        hiddenList.getStyleClass().add("preset-caption");
        hiddenList.setWrapText(true);

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
                + "Channel selection is OS-managed on Windows: pre-tune the "
                + "adapter via 'netsh wlan' or rely on whatever it is "
                + "currently parked on. Counters reset on each Start.");
        hint.setWrapText(true);
        hint.getStyleClass().add("preset-caption");

        // Frame-type bar: full-width strip with a short caption above
        // so first-time users know what the colours represent.
        VBox frameSection = new VBox(4,
                captionLabel("Frame composition (mgmt / ctrl / data / ext)"),
                frameTypeBar);
        frameTypeBar.setHeight(28);

        // Top talkers and BSS Load are heavyweight enough (each can
        // grow to MAX_ROWS rows) that we put each behind a TitledPane
        // so a user who only cares about one can collapse the other.
        TitledPane talkersPane = FxControls.collapsible(
                "Top talkers (by management frame count)", true,
                FxControls.sectionWithHowTo(
                        "",
                        "Sorted by frames captured. RSSI bar = signal strength relative to -90..-30 dBm; "
                        + "Frames bar = each row's frame count relative to the loudest talker. "
                        + "Retry % is bold green/amber/red so trouble jumps out without sorting; "
                        + "Avg rate shows '-' on modern HT/VHT/HE PHYs that omit the legacy radiotap rate field.",
                        topTalkersTable.view()));
        // Per-AP health is the "is the AP doing OK on its channel" view -
        // sorted by status (Bad first) to put trouble at the top.
        TitledPane healthPane = FxControls.collapsible(
                "Per-AP channel health", true,
                FxControls.sectionWithHowTo(
                        "",
                        "Composite health from beacon rate, beacon jitter, retry % and PHY rate. "
                        + "Beacon rate target is ~9.77/s (default 100 TU interval); jitter under 10 ms "
                        + "means clean CCA. Status badge: green = healthy, amber = stressed (one metric "
                        + "crossed its first threshold), red = bad (retry >= 25%, beacons < 5/s, or jitter > 30 ms).",
                        apHealthTable.view()));
        TitledPane bssLoadPane = FxControls.collapsible(
                "BSS Load advertised by APs", true,
                FxControls.sectionWithHowTo(
                        "",
                        "Self-reported channel utilization from Information Element 11. "
                        + "Bar colour: green < 40% (healthy), amber 40-70% (consider load balancing), "
                        + "red >= 70% (saturated).",
                        bssLoadTable.view()));
        TitledPane hiddenPane = FxControls.collapsible(
                "Resolved hidden SSIDs", false, hiddenList);

        root.setPadding(new Insets(0));
        root.getChildren().addAll(
                hint,
                controlsRow,
                statusLabel,
                insightCard,
                frameSection,
                talkersPane,
                healthPane,
                bssLoadPane,
                hiddenPane);

        refreshTimer = new Timeline(new KeyFrame(
                Duration.millis(UI_REFRESH_MS), e -> refreshAll()));
        refreshTimer.setCycleCount(Timeline.INDEFINITE);

        statusLabel.setText("Ready. Pick an adapter and click Start.");
        refreshAll();
    }

    /** Root node so callers can drop the panel into a {@link VBox} section. */
    public javafx.scene.Node node() { return root; }

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
            if (running) stopCapture(); else startCapture();
        });
        startStopButton.setDisable(adapterCombo.getItems().isEmpty());
    }

    private static Label captionLabel(String text) {
        Label l = new Label(text);
        l.setWrapText(true);
        l.getStyleClass().add("preset-caption");
        return l;
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
        beaconStore.reset();
        try {
            capture.start(adapter, channelMhz, frame -> {
                // Runs on the capture poll thread - keep it cheap.
                // Both consumers are synchronized internally and only
                // touch a small amount of state per frame; the UI tick
                // reads at 4 Hz so contention is negligible.
                stats.accept(frame);
                beaconStore.accept(frame);
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
        Platform.runLater(this::refreshAll);
        statusLabel.setText("Capture stopped.");
    }

    /** Pull a fresh snapshot once and broadcast it to every visual widget. */
    private void refreshAll() {
        CaptureStats.Snapshot snap = stats.snapshot();
        insightCard.update(snap, running);
        frameTypeBar.setSnapshot(snap);
        topTalkersTable.update(snap);
        apHealthTable.update(snap);
        bssLoadTable.update();
        refreshHiddenList();
    }

    /**
     * Render the resolved-hidden-SSID details. The insight card already
     * surfaces the count, so this is the place users look when they
     * want to see *which* names were learned.
     */
    private void refreshHiddenList() {
        Map<String, String> discovered = beaconStore.discoveredSsidSnapshot();
        if (discovered.isEmpty()) {
            hiddenList.setText("No hidden SSIDs resolved this session yet. "
                    + "Capture probe responses (auto-broadcast every few seconds by most APs) "
                    + "to fill this list.");
            return;
        }
        StringBuilder sb = new StringBuilder();
        discovered.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEachOrdered(e -> sb.append(e.getKey())
                        .append("  ->  ")
                        .append(e.getValue())
                        .append('\n'));
        hiddenList.setText(sb.toString().trim());
    }
}
