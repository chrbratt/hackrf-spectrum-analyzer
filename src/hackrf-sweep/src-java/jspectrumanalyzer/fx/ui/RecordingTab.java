package jspectrumanalyzer.fx.ui;

import javafx.application.Platform;
import javafx.css.PseudoClass;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import jspectrumanalyzer.fx.model.SettingsStore;
import jspectrumanalyzer.fx.util.FxControls;

/**
 * Recording tab: capture spectrum sweeps to a wide-format CSV next to the
 * working directory. The actual file I/O lives in
 * {@code SpectrumRecorder}; this tab is just the on/off switch and a tiny
 * status hint.
 *
 * <p>The video / GIF capture controls that lived here in the legacy fork
 * have been removed: the underlying Xuggler pipeline was deleted long ago,
 * and a permanently-disabled control just adds noise. When a JavaFX-native
 * replacement lands the section can come back.
 */
public final class RecordingTab extends ScrollPane {

    private static final PseudoClass DANGER = PseudoClass.getPseudoClass("danger");

    public RecordingTab(SettingsStore settings) {
        setFitToWidth(true);
        setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);

        Button recData = new Button("Record data");
        recData.setMaxWidth(Double.MAX_VALUE);
        FxControls.withTooltip(recData,
                "Append every sweep's bin powers to a CSV file in the working "
                + "directory. Click again to stop. The file name includes a "
                + "timestamp so consecutive recordings don't overwrite each other.");
        recData.setOnAction(e -> settings.isRecordedData().setValue(
                !settings.isRecordedData().getValue()));

        Label status = new Label("Idle");
        status.getStyleClass().add("recording-status");

        settings.isRecordedData().addListener(() -> Platform.runLater(() -> {
            boolean rec = settings.isRecordedData().getValue();
            recData.setText(rec ? "Stop data recording" : "Record data");
            recData.pseudoClassStateChanged(DANGER, rec);
            status.setText(rec
                    ? "Recording to hackrf-spectrum-*.csv in " + System.getProperty("user.dir")
                    : "Idle");
        }));

        HBox dataRow = new HBox(6, recData);
        HBox.setHgrow(recData, Priority.ALWAYS);

        VBox content = new VBox(12);
        content.setPadding(new Insets(12));
        content.getChildren().addAll(
                FxControls.section("Data recording (CSV)", dataRow, status));
        setContent(content);
    }
}
