package jspectrumanalyzer.fx.ui;

import javafx.application.Platform;
import javafx.css.PseudoClass;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import jspectrumanalyzer.fx.model.SettingsStore;
import jspectrumanalyzer.fx.util.FxControls;

/**
 * Recording tab: capture spectrum samples to disk, plus the (currently
 * disabled) video capture controls.
 * <p>
 * Split out of the previous "Waterfall / REC" tab because mixing display
 * settings with a running recording is confusing — they're different mental
 * tasks and the recording controls deserve their own focused space.
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
        settings.isRecordedData().addListener(() -> Platform.runLater(() -> {
            boolean rec = settings.isRecordedData().getValue();
            recData.setText(rec ? "Stop data recording" : "Record data");
            recData.pseudoClassStateChanged(DANGER, rec);
        }));

        Button recVideo = new Button("Record video");
        recVideo.setMaxWidth(Double.MAX_VALUE);
        recVideo.setDisable(true);
        FxControls.withTooltip(recVideo,
                "Disabled: video / GIF capture is being rewritten for JavaFX. "
                + "Track the progress in the project's open task list.");

        Node videoRes = FxControls.intSpinner(settings.getVideoResolution(), 360, 1080, 180);
        videoRes.setDisable(true);
        FxControls.withTooltip(videoRes,
                "Output video height in pixels. Width is computed from the "
                + "current window aspect ratio. Disabled until video capture returns.");

        Node videoFps = FxControls.intSpinner(settings.getVideoFrameRate(), 1, 60, 1);
        videoFps.setDisable(true);
        FxControls.withTooltip(videoFps,
                "Frames per second written to the video. Higher values produce "
                + "smoother but larger files. Disabled until video capture returns.");

        HBox dataRow = new HBox(6, recData);
        HBox.setHgrow(recData, Priority.ALWAYS);

        HBox videoRow = new HBox(6, recVideo);
        HBox.setHgrow(recVideo, Priority.ALWAYS);

        VBox content = new VBox(12);
        content.setPadding(new Insets(12));
        content.getChildren().addAll(
                FxControls.section("Data recording (CSV)", dataRow),
                FxControls.section("Video recording", videoRow,
                        labeled("Video resolution (px height)", videoRes),
                        labeled("Video frame rate (fps)", videoFps)));
        setContent(content);
    }

    private static VBox labeled(String caption, Node control) {
        return new VBox(2, new Label(caption), control);
    }
}
