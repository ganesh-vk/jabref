package org.jabref.gui.fieldeditors.contextmenu;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import javafx.collections.ObservableList;
import javafx.scene.control.Menu;

import org.jabref.gui.actions.ActionFactory;
import org.jabref.gui.actions.SimpleCommand;
import org.jabref.gui.actions.StandardActions;
import org.jabref.gui.fieldeditors.LinkedFileViewModel;
import org.jabref.gui.preferences.GuiPreferences;
import org.jabref.logic.l10n.Localization;
import org.jabref.model.database.BibDatabaseContext;
import org.jabref.model.database.FileDirectories;

import org.jspecify.annotations.NullMarked;

@NullMarked
class MoveFileSubmenuFactory {

    private final ActionFactory actionFactory;
    private final BibDatabaseContext databaseContext;
    private final GuiPreferences preferences;

    MoveFileSubmenuFactory(ActionFactory actionFactory,
                           BibDatabaseContext databaseContext,
                           GuiPreferences preferences) {
        this.actionFactory = actionFactory;
        this.databaseContext = databaseContext;
        this.preferences = preferences;
    }

    public Menu createSingle(LinkedFileViewModel linkedFileViewModel) {
        return createMenuItem(List.of(linkedFileViewModel), MoveFileCommand.Operation.SINGLE_MOVE);
    }

    public Menu createSingleAndRename(LinkedFileViewModel linkedFileViewModel) {
        return createMenuItem(List.of(linkedFileViewModel), MoveFileCommand.Operation.SINGLE_MOVE_AND_RENAME);
    }

    public Menu createForMulti(ObservableList<LinkedFileViewModel> selectedFiles) {
        return createMenuItem(selectedFiles, MoveFileCommand.Operation.MULTI_MOVE);
    }

    private Menu createMenuItem(List<LinkedFileViewModel> linkedFileViewModels, MoveFileCommand.Operation operation) {
        FileDirectories fileDirectories = databaseContext.getAllFileDirectories(preferences.getFilePreferences());
        Optional<Path> mainFileDirectory = preferences.getFilePreferences()
                                                      .getMainFileDirectory()
                                                      .map(path -> path.toAbsolutePath().normalize());

        Map<String, Optional<Path>> directories = new LinkedHashMap<>();
        directories.put(Localization.lang("Main file directory: %0"), mainFileDirectory);
        directories.put(Localization.lang("Library-specific file directory: %0"), fileDirectories.getLibraryDirectoryOpt());
        directories.put(Localization.lang("User-specific file directory: %0"), fileDirectories.getUserDirectoryOpt());
        directories.put(Localization.lang("Remote file directory: %0"), fileDirectories.getRemoteDirectoryOpt());
        directories.put(Localization.lang("Next to library file: %0"), databaseContext.getDatabaseDirectory());

        Menu menu = actionFactory.createMenu(operation.getAction());
        for (Map.Entry<String, Optional<Path>> entry : directories.entrySet()) {
            entry.getValue().ifPresent(targetDirectory -> {
                // Dirty hack: String formatted expects %s, Localization.lang %0 as placeholders.
                // Since localization strings are reused, we replace here
                String label = entry.getKey().replace("%0", "%s").formatted(targetDirectory);
                menu.getItems().add(actionFactory.createCustomMenuItem(
                        operation.getAction(),
                        new MoveFileCommand(targetDirectory, linkedFileViewModels, operation),
                        label
                ));
            });
        }
        return menu;
    }

    private class MoveFileCommand extends SimpleCommand {

        private enum Operation {
            SINGLE_MOVE(StandardActions.MOVE_FILE_TO_FOLDER),
            SINGLE_MOVE_AND_RENAME(StandardActions.MOVE_FILE_TO_FOLDER_AND_RENAME),
            MULTI_MOVE(StandardActions.MOVE_FILE_TO_FOLDER);

            private final StandardActions action;

            Operation(StandardActions action) {
                this.action = action;
            }

            private StandardActions getAction() {
                return action;
            }
        }

        private final Path targetDirectory;
        private final List<LinkedFileViewModel> linkedFileViewModels;
        private final Operation operation;

        private MoveFileCommand(Path targetDirectory,
                                List<LinkedFileViewModel> linkedFileViewModels,
                                Operation operation) {
            this.targetDirectory = targetDirectory;
            this.linkedFileViewModels = linkedFileViewModels;
            this.operation = operation;

            setExecutable(isMenuItemExecutable(targetDirectory, linkedFileViewModels, operation));
        }

        private boolean isMenuItemExecutable(Path targetDirectory,
                                              List<LinkedFileViewModel> linkedFileViewModels,
                                              Operation operation) {
            return switch (operation) {
                case SINGLE_MOVE -> {
                    LinkedFileViewModel linkedFileViewModel = linkedFileViewModels.getFirst();
                    yield isMovable(linkedFileViewModel)
                            && !linkedFileViewModel.isInCurrentDirectory(targetDirectory);
                }
                case SINGLE_MOVE_AND_RENAME -> {
                    LinkedFileViewModel linkedFileViewModel = linkedFileViewModels.getFirst();
                    yield isMovable(linkedFileViewModel)
                            && (!linkedFileViewModel.isInCurrentDirectory(targetDirectory)
                            || !linkedFileViewModel.isGeneratedNameSameAsOriginal());
                }
                case MULTI_MOVE ->
                        linkedFileViewModels.stream().anyMatch(this::isMovable);
            };
        }

        @Override
        public void execute() {
            switch (operation) {
                case SINGLE_MOVE ->
                        linkedFileViewModels.getFirst().moveToDirectory(targetDirectory);
                case SINGLE_MOVE_AND_RENAME ->
                        linkedFileViewModels.getFirst().moveToDirectoryAndRename(targetDirectory);
                case MULTI_MOVE ->
                        linkedFileViewModels.forEach(linkedFileViewModel -> {
                            if (isMovable(linkedFileViewModel) && !linkedFileViewModel.isInCurrentDirectory(targetDirectory)) {
                                linkedFileViewModel.moveToDirectory(targetDirectory);
                            }
                        });
            }
        }

        private boolean isMovable(LinkedFileViewModel linkedFileViewModel) {
            return !linkedFileViewModel.getFile().isOnlineLink()
                    && linkedFileViewModel.getFile()
                                          .findIn(databaseContext, preferences.getFilePreferences())
                                          .isPresent();
        }
    }
}
