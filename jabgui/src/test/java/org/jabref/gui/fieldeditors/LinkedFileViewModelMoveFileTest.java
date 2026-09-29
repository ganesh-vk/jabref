package org.jabref.gui.fieldeditors;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import javafx.application.Platform;

import org.jabref.gui.DialogService;
import org.jabref.gui.frame.ExternalApplicationsPreferences;
import org.jabref.gui.preferences.GuiPreferences;
import org.jabref.gui.testutils.JavaFxExtension;
import org.jabref.logic.FilePreferences;
import org.jabref.logic.l10n.Localization;
import org.jabref.logic.util.BackgroundTask;
import org.jabref.logic.util.CurrentThreadTaskExecutor;
import org.jabref.logic.util.TaskExecutor;
import org.jabref.logic.util.io.FileUtil;
import org.jabref.model.database.BibDatabase;
import org.jabref.model.database.BibDatabaseContext;
import org.jabref.model.database.FileDirectories;
import org.jabref.model.entry.BibEntry;
import org.jabref.model.entry.LinkedFile;
import org.jabref.model.entry.types.StandardEntryType;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(JavaFxExtension.class)
@ResourceLock("Localization.lang")
class LinkedFileViewModelMoveFileTest {

    @TempDir Path tempDir;

    private BibDatabaseContext databaseContext;
    private GuiPreferences preferences;
    private FilePreferences filePreferences;
    private DialogService dialogService;
    private TaskExecutor taskExecutor;
    private BibEntry entry;

    private Path sourceDir;
    private Path destinationDir;

    @BeforeEach
    void setUp() throws IOException {
        databaseContext = mock(BibDatabaseContext.class);
        preferences = mock(GuiPreferences.class);
        filePreferences = mock(FilePreferences.class);
        dialogService = mock(DialogService.class);
        taskExecutor = new CurrentThreadTaskExecutor();

        when(preferences.getFilePreferences()).thenReturn(filePreferences);
        when(preferences.getExternalApplicationsPreferences()).thenReturn(mock(ExternalApplicationsPreferences.class));
        when(filePreferences.getFileDirectoryPattern()).thenReturn("");

        entry = new BibEntry(StandardEntryType.Article);
        when(databaseContext.getDatabase()).thenReturn(new BibDatabase());
        when(databaseContext.getAllFileDirectories(filePreferences)).thenReturn(new FileDirectories(null, null, null, null));

        sourceDir = tempDir.resolve("source");
        destinationDir = tempDir.resolve("destination");
        Files.createDirectories(sourceDir);
        Files.createDirectories(destinationDir);
    }

    @Test
    void moveToDirectoryMovesFileToChosenTarget() throws IOException {
        Path sourceFile = sourceDir.resolve("nested/sub/test.pdf");
        Files.createDirectories(sourceFile.getParent());
        Files.createFile(sourceFile);

        LinkedFile linkedFile = new LinkedFile("desc", sourceFile, "pdf");
        LinkedFileViewModel viewModel = new LinkedFileViewModel(linkedFile, entry, databaseContext, taskExecutor, dialogService, preferences);

        viewModel.moveToDirectory(destinationDir);

        assertTrue(Files.exists(destinationDir.resolve("test.pdf")));
        assertFalse(Files.exists(sourceFile));
    }

    @Test
    void moveToDirectoryUsesConfiguredDirectoryPattern() throws IOException {
        when(filePreferences.getFileDirectoryPattern()).thenReturn("[entrytype]");

        Path sourceFile = sourceDir.resolve("test.pdf");
        Files.createFile(sourceFile);

        LinkedFile linkedFile = new LinkedFile("desc", sourceFile, "pdf");

        LinkedFileViewModel viewModel = new LinkedFileViewModel(linkedFile, entry, databaseContext, taskExecutor, dialogService, preferences);

        viewModel.moveToDirectory(destinationDir);

        String targetDirectoryName = FileUtil.createDirNameFromPattern(databaseContext.getDatabase(), entry, "[entrytype]");
        Path movedFile = destinationDir.resolve(targetDirectoryName).resolve("test.pdf");
        assertTrue(Files.exists(movedFile));
        assertFalse(Files.exists(sourceFile));
    }

    @Test
    void moveToDirectoryShowsErrorIfFileCannotBeResolved() {
        Path missingFile = sourceDir.resolve("missing.pdf");
        LinkedFile linkedFile = new LinkedFile("desc", missingFile, "pdf");

        LinkedFileViewModel viewModel = new LinkedFileViewModel(linkedFile, entry, databaseContext, taskExecutor, dialogService, preferences);

        viewModel.moveToDirectory(destinationDir);

        verify(dialogService).showErrorDialogAndWait(
                eq(Localization.lang("File not found")),
                eq(Localization.lang("Could not find file '%0'.", linkedFile.getLink()))
        );
    }

    @Test
    void moveToDirectorySubmitsBackgroundTask() throws IOException {
        TaskExecutor backgroundTaskExecutor = mock(TaskExecutor.class);
        Path sourceFile = sourceDir.resolve("test.pdf");
        Files.createFile(sourceFile);
        LinkedFile linkedFile = new LinkedFile("desc", sourceFile, "pdf");
        LinkedFileViewModel viewModel = new LinkedFileViewModel(linkedFile, entry, databaseContext, backgroundTaskExecutor, dialogService, preferences);

        viewModel.moveToDirectory(destinationDir);

        verify(backgroundTaskExecutor).execute(any());
        assertTrue(Files.exists(sourceFile));
        assertFalse(Files.exists(destinationDir.resolve("test.pdf")));
    }

    // [utest->req~logic.externalfiles.remote-mounted-directory~1]
    @Test
    void moveToDirectoryUpdatesLinkOnlyInJavaFxSuccessCallback() throws Exception {
        TaskExecutor backgroundTaskExecutor = mock(TaskExecutor.class);
        Path sourceFile = sourceDir.resolve("test.pdf");
        Files.writeString(sourceFile, "content");
        LinkedFile linkedFile = new LinkedFile("desc", sourceFile, "pdf");
        List<Boolean> linkUpdatesOnJavaFxThread = new ArrayList<>();
        linkedFile.linkProperty().addListener((_, _, _) -> linkUpdatesOnJavaFxThread.add(Platform.isFxApplicationThread()));
        LinkedFileViewModel viewModel = new LinkedFileViewModel(linkedFile, entry, databaseContext, backgroundTaskExecutor, dialogService, preferences);

        JavaFxExtension.invokeAndWait(() -> viewModel.moveToDirectory(destinationDir));
        ArgumentCaptor<BackgroundTask<String>> taskCaptor = ArgumentCaptor.captor();
        verify(backgroundTaskExecutor).execute(taskCaptor.capture());
        BackgroundTask<String> moveTask = taskCaptor.getValue();
        String newLink = moveTask.call();

        assertEquals("content", Files.readString(destinationDir.resolve("test.pdf")));
        assertEquals(sourceFile.toString(), linkedFile.getLink());
        assertEquals(List.of(), linkUpdatesOnJavaFxThread);

        JavaFxExtension.invokeAndWait(() -> moveTask.getOnSuccess().accept(newLink));

        assertEquals(destinationDir.resolve("test.pdf").toString(), linkedFile.getLink());
        assertEquals(List.of(true), linkUpdatesOnJavaFxThread);
    }

    @Test
    void isInDirectoryReturnsTrueForCurrentCurrentDirectoryAndPattern() throws IOException {
        when(filePreferences.getFileDirectoryPattern()).thenReturn("[entrytype]");
        String targetDirectoryName = FileUtil.createDirNameFromPattern(databaseContext.getDatabase(), entry, "[entrytype]");

        Path existingFile = destinationDir.resolve(targetDirectoryName).resolve("test.pdf");
        Files.createDirectories(existingFile.getParent());
        Files.createFile(existingFile);

        LinkedFile linkedFile = new LinkedFile("desc", existingFile, "pdf");

        LinkedFileViewModel viewModel = new LinkedFileViewModel(linkedFile, entry, databaseContext, taskExecutor, dialogService, preferences);

        assertTrue(viewModel.isInCurrentDirectory(destinationDir));
    }

    @Test
    void isInDirectoryReturnsFalseForDifferentCurrentDirectory() throws IOException {
        Path existingFile = sourceDir.resolve("test.pdf");
        Files.createFile(existingFile);
        LinkedFile linkedFile = new LinkedFile("desc", existingFile, "pdf");

        LinkedFileViewModel viewModel = new LinkedFileViewModel(linkedFile, entry, databaseContext, taskExecutor, dialogService, preferences);

        assertFalse(viewModel.isInCurrentDirectory(destinationDir));
    }
}
