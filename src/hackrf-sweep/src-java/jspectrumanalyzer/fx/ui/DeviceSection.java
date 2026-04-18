package jspectrumanalyzer.fx.ui;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.css.PseudoClass;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import jspectrumanalyzer.core.HackRFSettings;
import jspectrumanalyzer.fx.model.SettingsStore;
import jspectrumanalyzer.fx.util.FxControls;
import jspectrumanalyzer.nativebridge.HackRFDeviceInfo;
import jspectrumanalyzer.nativebridge.HackRFSweepNativeBridge;

/**
 * Top-of-Scan-tab strip, laid out in two rows so the controls stay readable
 * even when the sidebar is narrow:
 * <pre>
 *   [ device combo (full width)                 ]
 *   [ Refresh ] [ Start / Stop ]
 *   "Streaming from Praline (HackRF Pro) - 0123abcd"
 * </pre>
 * The combo always carries one synthetic "First available" entry so the
 * user can keep the legacy behaviour. Picking a real device writes its
 * serial into {@code settings.getSelectedSerial()}; the engine restarts
 * the sweep if it's currently running.
 */
public final class DeviceSection extends VBox {

    private static final Logger LOG = LoggerFactory.getLogger(DeviceSection.class);

    private static final HackRFDeviceInfo FIRST_AVAILABLE =
            new HackRFDeviceInfo("", "First available HackRF",
                    0, HackRFDeviceInfo.BOARD_ID_UNKNOWN);

    private static final PseudoClass DANGER = PseudoClass.getPseudoClass("danger");

    /**
     * Single-threaded executor for libusb enumeration. Keeping a single
     * worker (instead of {@code new Thread} per refresh click) means
     * a flurry of fast clicks queues sequentially against libusb instead
     * of racing with overlapping {@code listDevices()} calls. Daemon so
     * the JVM still exits even if libusb_init hangs.
     */
    private static final ExecutorService DEVICE_IO = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "DeviceSection-io");
        t.setDaemon(true);
        return t;
    });

    private final SettingsStore settings;

    private final ComboBox<HackRFDeviceInfo> deviceCombo = new ComboBox<>();
    private final Button refreshBtn = new Button("Refresh");
    private final Button startStopBtn = new Button("Start");
    private final Label statusLabel = new Label("Stopped");

    /** Guards against re-entrant updates when the combo is rebuilt. */
    private final AtomicBoolean updatingCombo = new AtomicBoolean(false);

    public DeviceSection(SettingsStore settings) {
        this.settings = settings;
        setSpacing(6);

        configureCombo();
        wireRefresh();
        wireStartStop();
        wireRunStateMirror();
        wireSerialMirror();
        wireHardwareStatusMirror();

        // Row 1: device picker takes the full sidebar width so the serial
        // never gets truncated mid-character.
        deviceCombo.setMaxWidth(Double.MAX_VALUE);

        // Row 2: Refresh + Start/Stop share the row equally. Both grow with
        // the sidebar so the user always sees the full button labels even at
        // the narrowest sidebar width supported by the SplitPane.
        refreshBtn.setMaxWidth(Double.MAX_VALUE);
        startStopBtn.setMaxWidth(Double.MAX_VALUE);
        HBox buttonRow = new HBox(6, refreshBtn, startStopBtn);
        buttonRow.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(refreshBtn, Priority.ALWAYS);
        HBox.setHgrow(startStopBtn, Priority.ALWAYS);

        statusLabel.getStyleClass().add("device-status");
        statusLabel.setWrapText(true);

        getChildren().addAll(deviceCombo, buttonRow, statusLabel);

        // Initial population so the combo isn't empty on first paint.
        // Done async-via-runLater so it doesn't block the FX startup pulse
        // if libusb is slow to enumerate.
        Platform.runLater(this::reloadDevices);
    }

    private void configureCombo() {
        deviceCombo.getItems().add(FIRST_AVAILABLE);
        deviceCombo.getSelectionModel().select(FIRST_AVAILABLE);
        deviceCombo.setButtonCell(deviceCell());
        deviceCombo.setCellFactory(view -> deviceCell());
        FxControls.withTooltip(deviceCombo,
                "Pick which HackRF the sweep should open. \"First available\" "
                + "lets libhackrf choose - useful if you only have one device.");

        deviceCombo.getSelectionModel().selectedItemProperty().addListener((obs, oldVal, newVal) -> {
            if (updatingCombo.get() || newVal == null) return;
            String serial = newVal.serial();
            if (!Objects.equals(serial, settings.getSelectedSerial().getValue())) {
                settings.getSelectedSerial().setValue(serial);
            }
        });
    }

    private static ListCell<HackRFDeviceInfo> deviceCell() {
        return new ListCell<>() {
            @Override protected void updateItem(HackRFDeviceInfo item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : item.displayLabel());
            }
        };
    }

    private void wireRefresh() {
        FxControls.withTooltip(refreshBtn,
                "Rescan USB for HackRF devices. Run this after plugging in or "
                + "swapping units.");
        refreshBtn.setOnAction(e -> reloadDevices());
    }

    private void wireStartStop() {
        FxControls.withTooltip(startStopBtn,
                "Start or stop streaming from the selected HackRF. The app no "
                + "longer auto-starts on launch so you can pick the device "
                + "first. Settings changes apply immediately while running.");
        startStopBtn.setOnAction(e -> settings.isRunningRequested().setValue(
                !settings.isRunningRequested().getValue()));
    }

    private void wireRunStateMirror() {
        Runnable apply = () -> {
            boolean running = settings.isRunningRequested().getValue();
            startStopBtn.setText(running ? "Stop" : "Start");
            startStopBtn.pseudoClassStateChanged(DANGER, running);
            // Lock both combo and refresh while running: combo because
            // switching mid-sweep would just thrash the engine, refresh
            // because libhackrf's enumeration cycle (init/list/exit) races
            // with the open device of an active sweep.
            deviceCombo.setDisable(running);
            refreshBtn.setDisable(running);
            refreshStatus();
        };
        apply.run();
        settings.isRunningRequested().addListener(() -> Platform.runLater(apply));
    }

    private void wireSerialMirror() {
        // External writes to the model (e.g. settings restore) should keep the
        // combo in sync. We don't cause feedback because the listener early-exits
        // when the selection already matches.
        settings.getSelectedSerial().addListener(() -> Platform.runLater(this::syncSelectionFromModel));
    }

    private void syncSelectionFromModel() {
        String wanted = settings.getSelectedSerial().getValue();
        if (wanted == null) wanted = "";
        for (HackRFDeviceInfo info : deviceCombo.getItems()) {
            if (Objects.equals(info.serial(), wanted)) {
                if (deviceCombo.getSelectionModel().getSelectedItem() != info) {
                    updatingCombo.set(true);
                    try { deviceCombo.getSelectionModel().select(info); }
                    finally { updatingCombo.set(false); }
                }
                return;
            }
        }
    }

    /**
     * Re-enumerate connected devices on a background thread so the FX pulse
     * isn't blocked by libusb_init/exit (which can take tens of ms).
     */
    private void reloadDevices() {
        refreshBtn.setDisable(true);
        DEVICE_IO.execute(() -> {
            List<HackRFDeviceInfo> devices;
            try {
                devices = HackRFSweepNativeBridge.listDevices();
            } catch (Throwable ex) {
                LOG.warn("listDevices failed", ex);
                devices = List.of();
            }
            List<HackRFDeviceInfo> snapshot = devices;
            Platform.runLater(() -> {
                applyDeviceList(snapshot);
                // If the user kicked off a sweep while we were enumerating,
                // the run-state mirror will keep the button disabled; only
                // re-enable when we're still stopped.
                refreshBtn.setDisable(settings.isRunningRequested().getValue());
            });
        });
    }

    private void applyDeviceList(List<HackRFDeviceInfo> devices) {
        ObservableList<HackRFDeviceInfo> items = FXCollections.observableArrayList();
        items.add(FIRST_AVAILABLE);
        items.addAll(devices);

        updatingCombo.set(true);
        try {
            deviceCombo.setItems(items);
            // Restore the selection that matches the current model serial.
            String wanted = settings.getSelectedSerial().getValue();
            if (wanted == null) wanted = "";
            HackRFDeviceInfo match = FIRST_AVAILABLE;
            for (HackRFDeviceInfo info : items) {
                if (Objects.equals(info.serial(), wanted)) {
                    match = info;
                    break;
                }
            }
            deviceCombo.getSelectionModel().select(match);
        } finally {
            updatingCombo.set(false);
        }
        refreshStatus();
    }

    /**
     * Updates the secondary label. While running we ask the native bridge
     * what was actually opened (board id is only known post-open, which is
     * the whole reason for the PRALINE / HackRF Pro detection).
     *
     * <p>The opened-info read returns null briefly between "user clicked
     * Start" and "device finished opening". We don't poll - the
     * {@link HackRFSettings.HackRFEventListener} hook below repaints the
     * label once data starts flowing, which is the next observable event.
     */
    private void refreshStatus() {
        boolean running = settings.isRunningRequested().getValue();
        if (!running) {
            statusLabel.setText("Stopped");
            return;
        }
        HackRFDeviceInfo info = safeGetOpenedInfo();
        statusLabel.setText(info != null
                ? formatStatus(info)
                : "Opening device...");
    }

    /**
     * Once the hardware actually starts streaming, libhackrf has populated
     * the opened-info struct (including the firmware board id that
     * distinguishes Pro from One). Rebuild the label so the user sees the
     * specific model rather than the placeholder.
     */
    private void wireHardwareStatusMirror() {
        settings.registerListener(new HackRFSettings.HackRFEventAdapter() {
            @Override
            public void hardwareStatusChanged(boolean sending) {
                Platform.runLater(() -> {
                    if (sending && settings.isRunningRequested().getValue()) {
                        HackRFDeviceInfo info = safeGetOpenedInfo();
                        if (info != null) {
                            statusLabel.setText(formatStatus(info));
                        }
                    }
                });
            }
        });
    }

    private static HackRFDeviceInfo safeGetOpenedInfo() {
        try {
            return HackRFSweepNativeBridge.getOpenedInfo();
        } catch (Throwable t) {
            LOG.warn("getOpenedInfo failed", t);
            return null;
        }
    }

    private static String formatStatus(HackRFDeviceInfo info) {
        if (info.serial().isEmpty()) {
            return "Streaming from " + info.boardName();
        }
        return "Streaming from " + info.boardName() + "  -  " + info.serial();
    }
}
