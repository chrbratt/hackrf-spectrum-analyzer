package jspectrumanalyzer.fx.ui;

import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.Properties;

import javafx.geometry.Insets;
import javafx.scene.control.Alert;
import javafx.scene.control.Alert.AlertType;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.TextInputDialog;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import jspectrumanalyzer.fx.model.SettingsStore;
import jspectrumanalyzer.fx.profile.SettingsCodec;
import jspectrumanalyzer.fx.profile.SettingsProfileStore;
import jspectrumanalyzer.fx.util.FxControls;

/**
 * Profiles tab: save the current settings under a name, switch between saved
 * profiles, rename or delete them, and reset everything back to the factory
 * defaults.
 *
 * <p>Designed for signal hunting where the same physical setup is revisited
 * for different purposes (e.g. "2.4 GHz Wi-Fi survey", "FM band", "ISM
 * 868 MHz"): tune once, save, and jump straight back later instead of
 * re-tweaking a dozen controls.
 */
public final class ProfilesTab extends ScrollPane {

    private final SettingsStore settings;
    private final SettingsProfileStore store;
    /** Captured from a pristine {@link SettingsStore} so "reset" always
     *  matches the constructor defaults without duplicating the value list. */
    private final Properties factoryDefaults;

    private final ListView<String> profileList = new ListView<>();
    private final Label status = new Label();

    public ProfilesTab(SettingsStore settings) {
        this(settings, new SettingsProfileStore());
    }

    ProfilesTab(SettingsStore settings, SettingsProfileStore store) {
        this.settings = settings;
        this.store = store;
        this.factoryDefaults = SettingsCodec.capture(new SettingsStore());

        setFitToWidth(true);
        setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);

        profileList.getSelectionModel().setSelectionMode(SelectionMode.SINGLE);
        profileList.setPrefHeight(180);
        VBox.setVgrow(profileList, Priority.ALWAYS);
        FxControls.withTooltip(profileList,
                "Saved profiles. Double-click a profile to load it.");
        profileList.setOnMouseClicked(e -> {
            if (e.getButton() == MouseButton.PRIMARY && e.getClickCount() == 2) {
                loadSelected();
            }
        });

        Button saveAs = wideButton("Save as new\u2026",
                "Capture the current settings as a new named profile.");
        saveAs.setOnAction(e -> saveAsNew());

        Button update = wideButton("Update selected",
                "Overwrite the selected profile with the current settings.");
        update.setOnAction(e -> updateSelected());

        Button load = wideButton("Load",
                "Apply the selected profile to all settings.");
        load.setOnAction(e -> loadSelected());

        Button rename = wideButton("Rename\u2026",
                "Give the selected profile a new name.");
        rename.setOnAction(e -> renameSelected());

        Button delete = wideButton("Delete",
                "Permanently remove the selected profile.");
        delete.setOnAction(e -> deleteSelected());

        Button reset = wideButton("Reset to defaults",
                "Restore every setting to the application's factory defaults. "
                + "This does not delete any saved profile.");
        reset.setOnAction(e -> resetToDefaults());

        HBox topRow = new HBox(6, saveAs, update);
        HBox.setHgrow(saveAs, Priority.ALWAYS);
        HBox.setHgrow(update, Priority.ALWAYS);
        HBox midRow = new HBox(6, load, rename, delete);
        HBox.setHgrow(load, Priority.ALWAYS);
        HBox.setHgrow(rename, Priority.ALWAYS);
        HBox.setHgrow(delete, Priority.ALWAYS);

        status.getStyleClass().add("recording-status");
        status.setWrapText(true);

        VBox content = new VBox(12);
        content.setPadding(new Insets(12));
        content.getChildren().addAll(
                FxControls.section("Saved profiles", profileList, topRow, midRow),
                FxControls.section("Defaults", reset),
                status);
        setContent(content);

        refreshList();
    }

    private static Button wideButton(String text, String tooltip) {
        Button b = new Button(text);
        b.setMaxWidth(Double.MAX_VALUE);
        FxControls.withTooltip(b, tooltip);
        return b;
    }

    private void refreshList() {
        String previouslySelected = selectedName();
        List<String> names = store.listProfiles();
        profileList.getItems().setAll(names);
        if (previouslySelected != null && names.contains(previouslySelected)) {
            profileList.getSelectionModel().select(previouslySelected);
        }
    }

    private String selectedName() {
        return profileList.getSelectionModel().getSelectedItem();
    }

    private void saveAsNew() {
        Optional<String> name = promptForName("Save profile",
                "Name for the new profile:", "");
        if (name.isEmpty()) return;
        String stem = SettingsProfileStore.sanitize(name.get());
        if (stem == null) {
            showError("Please enter a valid profile name.");
            return;
        }
        if (store.exists(stem) && !confirm("Overwrite profile",
                "A profile named \"" + stem + "\" already exists. Overwrite it?")) {
            return;
        }
        try {
            String saved = store.save(stem, SettingsCodec.capture(settings));
            refreshList();
            profileList.getSelectionModel().select(saved);
            status.setText("Saved profile \"" + saved + "\".");
        } catch (IOException | RuntimeException e) {
            showError("Could not save profile: " + e.getMessage());
        }
    }

    private void updateSelected() {
        String name = selectedName();
        if (name == null) {
            status.setText("Select a profile to update.");
            return;
        }
        if (!confirm("Update profile",
                "Overwrite \"" + name + "\" with the current settings?")) {
            return;
        }
        try {
            store.save(name, SettingsCodec.capture(settings));
            status.setText("Updated profile \"" + name + "\".");
        } catch (IOException | RuntimeException e) {
            showError("Could not update profile: " + e.getMessage());
        }
    }

    private void loadSelected() {
        String name = selectedName();
        if (name == null) {
            status.setText("Select a profile to load.");
            return;
        }
        try {
            Properties props = store.load(name);
            SettingsCodec.apply(props, settings);
            status.setText("Loaded profile \"" + name + "\".");
        } catch (IOException | RuntimeException e) {
            showError("Could not load profile: " + e.getMessage());
        }
    }

    private void renameSelected() {
        String name = selectedName();
        if (name == null) {
            status.setText("Select a profile to rename.");
            return;
        }
        Optional<String> newName = promptForName("Rename profile",
                "New name for \"" + name + "\":", name);
        if (newName.isEmpty()) return;
        String stem = SettingsProfileStore.sanitize(newName.get());
        if (stem == null) {
            showError("Please enter a valid profile name.");
            return;
        }
        if (stem.equals(name)) return;
        if (store.exists(stem) && !confirm("Overwrite profile",
                "A profile named \"" + stem + "\" already exists. Overwrite it?")) {
            return;
        }
        try {
            String renamed = store.rename(name, stem);
            refreshList();
            profileList.getSelectionModel().select(renamed);
            status.setText("Renamed to \"" + renamed + "\".");
        } catch (IOException | RuntimeException e) {
            showError("Could not rename profile: " + e.getMessage());
        }
    }

    private void deleteSelected() {
        String name = selectedName();
        if (name == null) {
            status.setText("Select a profile to delete.");
            return;
        }
        if (!confirm("Delete profile",
                "Permanently delete profile \"" + name + "\"?")) {
            return;
        }
        try {
            store.delete(name);
            refreshList();
            status.setText("Deleted profile \"" + name + "\".");
        } catch (IOException | RuntimeException e) {
            showError("Could not delete profile: " + e.getMessage());
        }
    }

    private void resetToDefaults() {
        if (!confirm("Reset to defaults",
                "Restore every setting to the factory defaults? "
                + "Saved profiles are not affected.")) {
            return;
        }
        SettingsCodec.apply(factoryDefaults, settings);
        status.setText("Settings reset to defaults.");
    }

    private Optional<String> promptForName(String title, String header, String initial) {
        TextInputDialog dialog = new TextInputDialog(initial);
        dialog.setTitle(title);
        dialog.setHeaderText(header);
        dialog.setContentText("Name:");
        return dialog.showAndWait().map(String::trim).filter(s -> !s.isEmpty());
    }

    private boolean confirm(String title, String message) {
        Alert alert = new Alert(AlertType.CONFIRMATION, message, ButtonType.OK, ButtonType.CANCEL);
        alert.setTitle(title);
        alert.setHeaderText(null);
        return alert.showAndWait().filter(b -> b == ButtonType.OK).isPresent();
    }

    private void showError(String message) {
        status.setText(message);
        Alert alert = new Alert(AlertType.ERROR, message, ButtonType.OK);
        alert.setHeaderText(null);
        alert.showAndWait();
    }
}
