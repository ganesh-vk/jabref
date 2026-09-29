package org.jabref.logic.externalfiles;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;

import org.jabref.logic.FilePreferences;
import org.jabref.logic.preferences.CliPreferences;
import org.jabref.logic.xmp.XmpPreferences;
import org.jabref.model.database.BibDatabaseContext;
import org.jabref.model.entry.BibEntry;
import org.jabref.model.entry.LinkedFile;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LinkedFileHandlerTest {
    @TempDir
    private Path tempFolder;

    private BibEntry entryWithCitationKeyAsdf;
    private BibEntry entryWithoutCitationKey;
    private BibDatabaseContext databaseContext;
    private final FilePreferences filePreferences = mock(FilePreferences.class);
    private final CliPreferences preferences = mock(CliPreferences.class);

    @BeforeEach
    void setUp() {
        entryWithCitationKeyAsdf = new BibEntry().withCitationKey("asdf");
        entryWithoutCitationKey = new BibEntry();
        databaseContext = new BibDatabaseContext();

        when(filePreferences.confirmDeleteLinkedFile()).thenReturn(true);
        when(preferences.getFilePreferences()).thenReturn(filePreferences);
        when(preferences.getXmpPreferences()).thenReturn(mock(XmpPreferences.class));
    }

    @ParameterizedTest(name = "{1} to {2} should be {0}")
    @CsvSource(textBlock = """
                newName.pdf, test.pdf, newName
                newName.txt, test.pdf, newName.txt
                newNameWithoutExtension, test, newNameWithoutExtension
                newName.pdf, testFile, newName.pdf
                newName..pdf, test.pdf, newName.
            """)
    void renameFile(String expectedFileName, String originalFileName, String newFileName) throws IOException {
        final Path tempFile = tempFolder.resolve(originalFileName);
        Files.createFile(tempFile);

        final LinkedFile linkedFile = new LinkedFile("", tempFile, "");
        LinkedFileHandler linkedFileHandler = new LinkedFileHandler(linkedFile, entryWithCitationKeyAsdf, databaseContext, filePreferences);

        linkedFileHandler.renameToName(newFileName, false);
        final String result = Path.of(linkedFile.getLink()).getFileName().toString();
        assertEquals(expectedFileName, result);
    }

    @Test
    void moveToRemoteDirectoryCopiesWithoutAttributesAndDeletesSource() throws IOException {
        when(filePreferences.getUserAndHost()).thenReturn("user-host");
        Path sourceFile = tempFolder.resolve("source.pdf");
        Path remoteDirectory = tempFolder.resolve("remote");
        Files.writeString(sourceFile, "content");
        FileTime oldModifiedTime = FileTime.fromMillis(946684800000L);
        Files.setLastModifiedTime(sourceFile, oldModifiedTime);
        databaseContext.getMetaData().setRemoteFileDirectory("user-host", remoteDirectory.toString());

        LinkedFile linkedFile = new LinkedFile("", sourceFile, "PDF");
        LinkedFileHandler linkedFileHandler = new LinkedFileHandler(linkedFile, entryWithoutCitationKey, databaseContext, filePreferences);

        linkedFileHandler.moveToExactDirectory(remoteDirectory);

        Path targetFile = remoteDirectory.resolve("source.pdf");
        assertEquals("content", Files.readString(targetFile));
        assertFalse(Files.exists(sourceFile));
        assertNotEquals(oldModifiedTime, Files.getLastModifiedTime(targetFile));
    }

    @Test
    void moveToRemoteDirectoryKeepsAbsoluteLinkWhenRelativeLinkResolvesToDifferentLocalFile() throws IOException {
        when(filePreferences.getUserAndHost()).thenReturn("user-host");
        Path sourceDirectory = tempFolder.resolve("source");
        Path otherLocalDirectory = tempFolder.resolve("other-local");
        Path remoteDirectory = tempFolder.resolve("remote");
        Files.createDirectories(sourceDirectory);
        Files.createDirectories(otherLocalDirectory);
        Files.createDirectories(remoteDirectory);
        Path sourceFile = sourceDirectory.resolve("report.pdf");
        Files.writeString(sourceFile, "intended document");
        Files.writeString(otherLocalDirectory.resolve("report.pdf"), "different document");
        databaseContext.getMetaData().setUserFileDirectory("user-host", sourceDirectory.toString());
        databaseContext.getMetaData().setLibrarySpecificFileDirectory(otherLocalDirectory.toString());
        databaseContext.getMetaData().setRemoteFileDirectory("user-host", remoteDirectory.toString());

        LinkedFile linkedFile = new LinkedFile("", "report.pdf", "PDF");
        LinkedFileHandler linkedFileHandler = new LinkedFileHandler(linkedFile, entryWithoutCitationKey, databaseContext, filePreferences);

        linkedFileHandler.moveToExactDirectory(remoteDirectory);

        Path targetFile = remoteDirectory.resolve("report.pdf");
        assertEquals(targetFile.toAbsolutePath().normalize().toString(), linkedFile.getLink());
        assertEquals("intended document", Files.readString(targetFile));
    }

    // [utest->req~logic.externalfiles.remote-mounted-directory~1]
    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void renameRemoteFileKeepsLinkWhenLocalFileHasSameName(boolean overwriteExistingFile) throws IOException {
        when(filePreferences.getUserAndHost()).thenReturn("user-host");
        Path localDirectory = Files.createDirectory(tempFolder.resolve("local"));
        Path remoteDirectory = Files.createDirectory(tempFolder.resolve("remote"));
        Path sourceFile = Files.writeString(remoteDirectory.resolve("original.pdf"), "remote document");
        Path localFile = Files.writeString(localDirectory.resolve("renamed.pdf"), "local document");
        if (overwriteExistingFile) {
            Files.writeString(remoteDirectory.resolve("renamed.pdf"), "old remote document");
        }
        databaseContext.getMetaData().setUserFileDirectory("user-host", localDirectory.toString());
        databaseContext.getMetaData().setRemoteFileDirectory("user-host", remoteDirectory.toString());
        LinkedFile linkedFile = new LinkedFile("", sourceFile, "PDF");
        LinkedFileHandler linkedFileHandler = new LinkedFileHandler(linkedFile, entryWithoutCitationKey, databaseContext, filePreferences);

        linkedFileHandler.renameToName("renamed.pdf", overwriteExistingFile);

        assertEquals(remoteDirectory.resolve("renamed.pdf").toString(), linkedFile.getLink());
        assertEquals("remote document", Files.readString(linkedFile.findIn(databaseContext, filePreferences).orElseThrow()));
        assertEquals("local document", Files.readString(localFile));
        assertFalse(Files.exists(sourceFile));
    }

    @ParameterizedTest(name = "{1} with {2} should be {0} for citation key 'asdf'")
    // Empty extensions do not need to be tested - all calls provide a non-empty extension
    @CsvSource(textBlock = """
                asdf.pdf, '', pdf
                asdf.pdf, file.pdf, pdf
                asdf.txt, file.pdf, txt
                asdf.pdf, file.txt, pdf
                asdf.pdf, https://example.com/file.pdf, pdf
                asdf.pdf, https://example.com/file.pdf?query=test, pdf
                asdf.pdf, https://example.com/file.doc, pdf
                asdf.pdf, https://example.com/file, pdf
                asdf.pdf, https://example.com/, pdf
                asdf.pdf, https://www.cncf.io/wp-content/uploads/2020/08/OAM-Webinar-V2.pdf, pdf
                asdf.pdf, path/to/file.pdf, pdf
            """)
    void getSuggestedFileName(String expectedFileName, String link, String extension) {
        when(filePreferences.getFileNamePattern()).thenReturn("[bibtexkey]");

        final LinkedFile linkedFile = new LinkedFile("", link, "");
        final LinkedFileHandler linkedFileHandler = new LinkedFileHandler(linkedFile, entryWithCitationKeyAsdf, databaseContext, filePreferences);

        assertEquals(expectedFileName, linkedFileHandler.getSuggestedFileName(extension));
    }

    @ParameterizedTest(name = "{1} with {2} should be {0} for empty citation key")
    @CsvSource(textBlock = """
                file.pdf, '', pdf
                file.txt, '', txt
                file.pdf, file.pdf, pdf
                file.txt, file.pdf, txt
                file.pdf, file.txt, pdf
                other.pdf, other.pdf, pdf
                other.txt, other.pdf, txt
                other.pdf, other.txt, pdf
                file.pdf, https://example.com/file.pdf, pdf
                file.pdf, https://example.com/file.txt, pdf
                file.txt, https://example.com/file.pdf, txt
                file.pdf, https://example.com/file.pdf?query=test, pdf
                file.pdf, https://example.com/file.doc, pdf
                file.pdf, https://example.com/file, pdf
                file.pdf, https://example.com/, pdf
                other.pdf, https://example.com/other.pdf, pdf
                other.pdf, https://example.com/other.pdf?query=test, pdf
                other.pdf, https://example.com/other.doc, pdf
                other.pdf, https://example.com/other, pdf
                other.pdf, path/to/other.pdf, pdf
                OAM-Webinar-V2.pdf, https://www.cncf.io/wp-content/uploads/2020/08/OAM-Webinar-V2.pdf, pdf
            """)
    void getSuggestedFileNameWithMissingKey(String expectedFileName, String link, String extension) {
        when(filePreferences.getFileNamePattern()).thenReturn("[bibtexkey]");

        final LinkedFile linkedFile = new LinkedFile("", link, "");
        final LinkedFileHandler linkedFileHandler = new LinkedFileHandler(linkedFile, entryWithoutCitationKey, databaseContext, filePreferences);

        assertEquals(expectedFileName, linkedFileHandler.getSuggestedFileName(extension));
    }

    @ParameterizedTest(name = "{1} should be {0} for citation key 'asdf'")
    @CsvSource(textBlock = """
                asdf, ''
                asdf.pdf, file.pdf
                asdf.txt, file.txt
                asdf.pdf, https://example.com/file.pdf
                asdf.pdf, https://example.com/file.pdf?query=test
                asdf.doc, https://example.com/file.doc
                asdf, https://example.com/file
                asdf, https://example.com/
                asdf.pdf, https://www.cncf.io/wp-content/uploads/2020/08/OAM-Webinar-V2.pdf
            """)
    void getSuggestedFileNameWithoutExtension(String expectedFileName, String link) {
        when(filePreferences.getFileNamePattern()).thenReturn("[bibtexkey]");

        final LinkedFile linkedFile = new LinkedFile("", link, "");
        final LinkedFileHandler linkedFileHandler = new LinkedFileHandler(linkedFile, entryWithCitationKeyAsdf, databaseContext, filePreferences);

        assertEquals(expectedFileName, linkedFileHandler.getSuggestedFileName());
    }

    @ParameterizedTest(name = "{1} should be {0} for empty citation key")
    @CsvSource(textBlock = """
                file, ''
                file.pdf, file.pdf
                file.txt, file.txt
                other.pdf, other.pdf
                other.txt, other.txt
                file.pdf, https://example.com/file.pdf
                file.txt, https://example.com/file.txt
                file.pdf, https://example.com/file.pdf?query=test
                file.doc, https://example.com/file.doc
                file, https://example.com/file
                file, https://example.com/
                other.pdf, https://example.com/other.pdf
                other.pdf, https://example.com/other.pdf?query=test
                other.doc, https://example.com/other.doc
                other, https://example.com/other
                OAM-Webinar-V2.pdf, https://www.cncf.io/wp-content/uploads/2020/08/OAM-Webinar-V2.pdf
            """)
    void getSuggestedFileNameWithoutExtensionWithMissingKey(String expectedFileName, String link) {
        when(filePreferences.getFileNamePattern()).thenReturn("[bibtexkey]");

        final LinkedFile linkedFile = new LinkedFile("", link, "");
        final LinkedFileHandler linkedFileHandler = new LinkedFileHandler(linkedFile, entryWithoutCitationKey, databaseContext, filePreferences);

        assertEquals(expectedFileName, linkedFileHandler.getSuggestedFileName());
    }

    @Test
    void getSuggestedFileNameInDirectory() {
        when(filePreferences.getFileNamePattern()).thenReturn("[bibtexkey]");
        final String link = "path/to/other.txt".replace('/', File.separatorChar);
        final LinkedFile linkedFile = new LinkedFile("", link, "");
        final LinkedFileHandler linkedFileHandler = new LinkedFileHandler(linkedFile, entryWithCitationKeyAsdf, databaseContext, filePreferences);
        assertEquals("asdf.pdf", linkedFileHandler.getSuggestedFileName("pdf"), "\"" + link + "\" with \"pdf\" should be \"asdf.pdf\" for citation key 'asdf'");
    }

    @Test
    void getSuggestedFileNameInDirectoryWithMissingKey() {
        when(filePreferences.getFileNamePattern()).thenReturn("[bibtexkey]");
        final String link = "path/to/other.txt".replace('/', File.separatorChar);
        final LinkedFile linkedFile = new LinkedFile("", link, "");
        final LinkedFileHandler linkedFileHandler = new LinkedFileHandler(linkedFile, entryWithoutCitationKey, databaseContext, filePreferences);
        assertEquals("other.pdf", linkedFileHandler.getSuggestedFileName("pdf"), "\"" + link + "\" with \"pdf\" should be \"other.pdf\" for empty citation key");
    }

    @Test
    void getSuggestedFileNameWithoutExtensionInDirectory() {
        when(filePreferences.getFileNamePattern()).thenReturn("[bibtexkey]");
        final String link = "path/to/other.txt".replace('/', File.separatorChar);
        final LinkedFile linkedFile = new LinkedFile("", link, "");
        final LinkedFileHandler linkedFileHandler = new LinkedFileHandler(linkedFile, entryWithCitationKeyAsdf, databaseContext, filePreferences);
        assertEquals("asdf.txt", linkedFileHandler.getSuggestedFileName(), "\"" + link + "\" should be \"asdf.txt\" for citation key 'asdf'");
    }

    @Test
    void getSuggestedFileNameWithoutExtensionWithMissingKeyInDirectory() {
        when(filePreferences.getFileNamePattern()).thenReturn("[bibtexkey]");
        final String link = "path/to/other.txt".replace('/', File.separatorChar);
        final LinkedFile linkedFile = new LinkedFile("", link, "");
        final LinkedFileHandler linkedFileHandler = new LinkedFileHandler(linkedFile, entryWithoutCitationKey, databaseContext, filePreferences);
        assertEquals("other.txt", linkedFileHandler.getSuggestedFileName(), "\"" + link + "\" should be \"other.txt\" for empty citation key");
    }
}
