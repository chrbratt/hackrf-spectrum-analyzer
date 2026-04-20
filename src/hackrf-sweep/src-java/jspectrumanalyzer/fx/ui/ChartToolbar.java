package jspectrumanalyzer.fx.ui;

import javafx.application.Platform;
import javafx.css.PseudoClass;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.util.Duration;
import jspectrumanalyzer.fx.engine.SpectrumEngine;
import jspectrumanalyzer.fx.model.SettingsStore;

/**
 * Toolbar shown directly above the spectrum chart.
 * <p>
 * Surfaces the controls a user reaches for most often (freeze, clear, toggle
 * waterfall / persistent overlay) so they don't have to switch settings tabs
 * while keeping an eye on a signal.
 */
public final class ChartToolbar extends HBox {

    private static final PseudoClass DANGER = PseudoClass.getPseudoClass("danger");

    private final SettingsStore settings;
    private final SpectrumEngine engine;
    private final Runnable clearWaterfallAndPersistent;
    private final Runnable openWifiWindow;

    private final Button freezeBtn = new Button("Freeze display");
    private final Button clearBtn = new Button("Clear traces");
    private final CheckBox waterfallToggle = new CheckBox("Waterfall");
    private final CheckBox persistentToggle = new CheckBox("Persistent");
    private final Button wifiBtn = new Button("Wi-Fi\u2026");

    /**
     * @param openWifiWindow invoked when the user presses the "Wi-Fi..."
     *                       button. Owns the lazy creation + show/focus of
     *                       the Wi-Fi window so this toolbar stays unaware
     *                       of the wifi package. May be {@code null} to
     *                       hide the button entirely (useful when AP
     *                       discovery is not supported on the host).
     */
    public ChartToolbar(SettingsStore settings,
                        SpectrumEngine engine,
                        Runnable clearWaterfallAndPersistent,
                        Runnable openWifiWindow) {
        this.settings = settings;
        this.engine = engine;
        this.clearWaterfallAndPersistent = clearWaterfallAndPersistent;
        this.openWifiWindow = openWifiWindow;

        setSpacing(8);
        setAlignment(Pos.CENTER_LEFT);
        setPadding(new Insets(4, 8, 4, 8));
        getStyleClass().add("chart-toolbar");

        wireFreeze();
        wireClear();
        wireWaterfallToggle();
        wirePersistentToggle();
        wireWifiButton();

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        // Wi-Fi button sits on the right side of the toolbar so it visually
        // anchors as the "open another window" affordance, separate from
        // the chart-state toggles (freeze / clear / waterfall / persistent).
        getChildren().addAll(freezeBtn, clearBtn, separator(),
                waterfallToggle, persistentToggle, spacer,
                separator(), wifiBtn);
    }

    /** Programmatic access to keyboard shortcuts. */
    public void toggleFreeze() { freezeBtn.fire(); }
    public void clearTraces() { clearBtn.fire(); }
    public void toggleWaterfall() { waterfallToggle.fire(); }

    private void wireFreeze() {
        freezeBtn.setTooltip(tip(
                "Freeze the on-screen plot but keep the HackRF streaming. "
                + "Press again (or Space) to resume updates. "
                + "Hardware is never paused so you don't get a 'cold start' delay on resume.\n"
                + "Shortcut: Space    Start/Stop sweep: F5    Esc: reset zoom"));
        freezeBtn.setOnAction(e -> settings.isCapturingPaused().setValue(
                !settings.isCapturingPaused().getValue()));
        settings.isCapturingPaused().addListener(() -> Platform.runLater(() -> {
            boolean frozen = settings.isCapturingPaused().getValue();
            freezeBtn.setText(frozen ? "Resume" : "Freeze display");
            freezeBtn.pseudoClassStateChanged(DANGER, frozen);
        }));
    }

    private void wireClear() {
        clearBtn.setTooltip(tip(
                "Reset peak hold, max hold and average traces, plus clear the "
                + "waterfall scrollback and persistent overlay. Useful after "
                + "changing frequency, gain or antenna so old peaks don't bleed in. "
                + "Shortcut: C"));
        clearBtn.setOnAction(e -> {
            engine.resetTraces();
            if (clearWaterfallAndPersistent != null) clearWaterfallAndPersistent.run();
        });
    }

    private void wireWaterfallToggle() {
        waterfallToggle.setTooltip(tip(
                "Show or hide the waterfall plot below the spectrum. "
                + "Hiding it gives the spectrum more vertical space. Shortcut: W"));
        waterfallToggle.setSelected(settings.isWaterfallVisible().getValue());
        waterfallToggle.selectedProperty().addListener((obs, o, n) -> {
            if (n != null && n != settings.isWaterfallVisible().getValue()) {
                settings.isWaterfallVisible().setValue(n);
            }
        });
        settings.isWaterfallVisible().addListener(() -> Platform.runLater(() -> {
            boolean v = settings.isWaterfallVisible().getValue();
            if (waterfallToggle.isSelected() != v) waterfallToggle.setSelected(v);
        }));
    }

    private void wireWifiButton() {
        wifiBtn.setTooltip(tip(
                "Open the Wi-Fi window: lists every visible access point with "
                + "SSID, BSSID, RSSI and channel, and lets you retune the "
                + "spectrum to a single Wi-Fi band. Both windows stay "
                + "interactive so you can keep changing scan / display "
                + "settings while looking at the AP list."));
        if (openWifiWindow == null) {
            wifiBtn.setVisible(false);
            wifiBtn.setManaged(false);
            return;
        }
        wifiBtn.setOnAction(e -> openWifiWindow.run());
    }

    private void wirePersistentToggle() {
        persistentToggle.setTooltip(tip(
                "Overlay a heat-map of how often each frequency / amplitude "
                + "combination was hit recently. Helps spot bursty or "
                + "low-duty-cycle signals."));
        persistentToggle.setSelected(settings.isPersistentDisplayVisible().getValue());
        persistentToggle.selectedProperty().addListener((obs, o, n) -> {
            if (n != null && n != settings.isPersistentDisplayVisible().getValue()) {
                settings.isPersistentDisplayVisible().setValue(n);
            }
        });
        settings.isPersistentDisplayVisible().addListener(() -> Platform.runLater(() -> {
            boolean v = settings.isPersistentDisplayVisible().getValue();
            if (persistentToggle.isSelected() != v) persistentToggle.setSelected(v);
        }));
    }

    private static Region separator() {
        Region sep = new Region();
        sep.getStyleClass().add("toolbar-separator");
        sep.setMinWidth(1);
        sep.setPrefWidth(1);
        sep.setMaxHeight(20);
        return sep;
    }

    private static Tooltip tip(String text) {
        Tooltip t = new Tooltip(text);
        t.setShowDelay(Duration.millis(400));
        t.setWrapText(true);
        t.setMaxWidth(360);
        return t;
    }
}
