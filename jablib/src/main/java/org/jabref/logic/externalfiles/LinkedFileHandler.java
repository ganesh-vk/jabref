package org.jabref.logic.externalfiles;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;
import java.util.stream.Stream;

import org.jabref.logic.FilePreferences;
import org.jabref.logic.util.io.FileNameUniqueness;
import org.jabref.logic.util.io.FileUtil;
import org.jabref.logic.util.strings.StringUtil;
import org.jabref.model.database.BibDatabaseContext;
import org.jabref.model.entry.BibEntry;
import org.jabref.model.entry.LinkedFile;

import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class LinkedFileHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(LinkedFileHandler.class);

    private final BibDatabaseContext databaseContext;
    private final FilePreferences filePreferences;
    private final BibEntry entry;

    private final LinkedFile linkedFile;

    public LinkedFileHandler(LinkedFile linkedFile,
                             BibEntry entry,
                             @NonNull BibDatabaseContext databaseContext,
                             @NonNull FilePreferences filePreferences) {
        this.linkedFile = linkedFile;
        this.entry = entry;
        this.databaseContext = databaseContext;
        this.filePreferences = filePreferences;
    }

    public boolean moveToDefaultDirectory() throws IOException {
        return copyOrMoveToDefaultDirectory(true, false);
    }

    public void moveToExactDirectory(Path targetDirectory) throws IOException {
        copyOrMoveToExactDirectory(targetDirectory, true, false);
    }

    /// @return true if the file was copied/moved or the same file exists in the target directory
    public boolean copyOrMoveToDefaultDirectory(boolean shouldMove, boolean shouldRenameToFilenamePattern) throws IOException {
        Optional<Path> databaseFileDirectoryOpt = databaseContext.getFirstExistingFileDir(filePreferences);
        if (databaseFileDirectoryOpt.isEmpty()) {
            LOGGER.warn("No existing file directory found");
            return false;
        }

        String targetDirectoryName = "";
        if (!filePreferences.getFileDirectoryPattern().isEmpty()) {
            targetDirectoryName = FileUtil.createDirNameFromPattern(
                    databaseContext.getDatabase(),
                    entry,
                    filePreferences.getFileDirectoryPattern());
        }

        Path targetDirectory = databaseFileDirectoryOpt.get().resolve(targetDirectoryName);
        return copyOrMoveToExactDirectory(targetDirectory, shouldMove, shouldRenameToFilenamePattern);
    }

    private boolean copyOrMoveToExactDirectory(Path targetDirectory, boolean shouldMove, boolean shouldRenameToFilenamePattern) throws IOException {
        Optional<Path> sourcePathOpt = linkedFile.findIn(databaseContext, filePreferences);
        if (sourcePathOpt.isEmpty()) {
            LOGGER.warn("Could not find file {}", linkedFile.getLink());
            return false;
        }
        Path sourcePath = sourcePathOpt.get();

        // Ensure that this directory exists
        Files.createDirectories(targetDirectory);

        GetTargetPathResult getTargetPathResult = null;
        if (shouldRenameToFilenamePattern) {
            getTargetPathResult = getTargetPath(sourcePath, targetDirectory, true);
            if (getTargetPathResult.exists) {
                if (shouldMove && !Files.isSameFile(sourcePath, getTargetPathResult.path)) {
                    Files.delete(sourcePath);
                }
                updateLink(getTargetPathResult.path());
                return true;
            }
        }
        if (!shouldRenameToFilenamePattern || (getTargetPathResult.renamed && !entry.getFiles().isEmpty())) {
            // Either we do not rename to pattern - or UX feature:
            // UX feature: If user adds a file to the entry and JabRef could only add it when renaming to the suggested pattern,
            //             JabRef should keep the original file name
            getTargetPathResult = getTargetPath(sourcePath, targetDirectory, false);
            if (getTargetPathResult.exists) {
                if (shouldMove && !Files.isSameFile(sourcePath, getTargetPathResult.path)) {
                    Files.delete(sourcePath);
                }
                updateLink(getTargetPathResult.path());
                return true;
            }
        }

        assert !Files.exists(getTargetPathResult.path);
        if (shouldMove) {
            moveFile(sourcePath, getTargetPathResult.path);
        } else {
            Files.copy(sourcePath, getTargetPathResult.path);
        }
        assert Files.exists(getTargetPathResult.path);

        updateLink(getTargetPathResult.path);
        return true;
    }

    // [impl->req~logic.externalfiles.remote-mounted-directory~1]
    private void updateLink(Path target) {
        Path relativeTarget = FileUtil.relativize(target, databaseContext, filePreferences);
        if (relativeTarget.isAbsolute()) {
            linkedFile.setLink(relativeTarget.toString());
            return;
        }

        boolean relativeLinkTargetsMovedFile = FileUtil.find(databaseContext, relativeTarget.toString(), filePreferences)
                                                       .map(resolvedTarget -> refersToSameFile(target, resolvedTarget))
                                                       .orElse(false);
        if (relativeLinkTargetsMovedFile) {
            linkedFile.setLink(relativeTarget.toString());
            return;
        }

        linkedFile.setLink(target.toAbsolutePath().normalize().toString());
    }

    private boolean refersToSameFile(Path target, Path resolvedTarget) {
        try {
            return Files.isSameFile(target, resolvedTarget);
        } catch (IOException exception) {
            LOGGER.debug("Could not verify resolved link {} for target {}", resolvedTarget, target, exception);
            return false;
        }
    }

    private void moveFile(Path source, Path target) throws IOException {
        Optional<Path> remoteDirectory = databaseContext.getAllFileDirectories(filePreferences).getRemoteDirectoryOpt();
        boolean sourceOrTargetIsRemote = remoteDirectory.filter(source::startsWith).isPresent()
                || remoteDirectory.filter(target::startsWith).isPresent();
        if (sourceOrTargetIsRemote) {
            copyAndDelete(source, target);
            return;
        }

        moveFileWithFallback(source, target);
    }

    static void moveFileWithFallback(Path source, Path target) throws IOException {
        if (!source.getFileSystem().provider().equals(target.getFileSystem().provider())) {
            copyAndDelete(source, target);
            return;
        }

        try {
            Files.move(source, target);
        } catch (FileSystemException moveException) {
            if (Files.exists(target) || !Files.exists(source)) {
                throw moveException;
            }

            LOGGER.debug("File system move from {} to {} failed. Retrying without copying file attributes.", source, target, moveException);
            try {
                copyAndDelete(source, target);
            } catch (IOException fallbackException) {
                fallbackException.addSuppressed(moveException);
                throw fallbackException;
            }
        }
    }

    private static void copyAndDelete(Path source, Path target) throws IOException {
        try {
            Files.copy(source, target);
        } catch (FileAlreadyExistsException exception) {
            throw exception;
        } catch (IOException copyException) {
            try {
                Files.deleteIfExists(target);
            } catch (IOException cleanupException) {
                copyException.addSuppressed(cleanupException);
            }
            throw copyException;
        }

        Files.delete(source);
    }

    /// If exists: the path already exists and has the same content as the given sourcePath
    ///
    /// @param renamed The original/suggested filename was adapted to fit it
    private record GetTargetPathResult(boolean exists, boolean renamed, Path path) {
    }

    private GetTargetPathResult getTargetPath(Path sourcePath, Path targetDirectory, boolean useSuggestedName) throws IOException {
        Path suggestedFileName;
        if (useSuggestedName) {
            suggestedFileName = Path.of(FileUtil.getFileExtension(sourcePath).map(this::getSuggestedFileName).orElseGet(this::getSuggestedFileName));
        } else {
            suggestedFileName = sourcePath.getFileName();
        }

        String fileNameWithoutExtension = FileUtil.getBaseName(suggestedFileName);
        String extensionSuffix = FileUtil.getFileExtension(suggestedFileName).map(ext -> "." + ext).orElse("");

        Path targetPath = targetDirectory.resolve(suggestedFileName);
        boolean renamed = false;
        if (Files.exists(targetPath)) {
            if (Files.mismatch(sourcePath, targetPath) == -1) {
                // In case of source == target, we pretend, we have success
                LOGGER.debug("The file {} would have been copied/moved to {}. However, there exists already a file with that name so we do nothing.", sourcePath, targetPath);
                return new GetTargetPathResult(true, false, targetPath);
            }
            int count = 1;
            boolean exists = false;
            // @formatter:off
            do {
                // @formatter:on
                targetPath = targetDirectory.resolve(fileNameWithoutExtension + " (" + count + ")" + extensionSuffix);
                exists = Files.exists(targetPath);
                if (exists && Files.mismatch(sourcePath, targetPath) == -1) {
                    // In case of source == target, we pretend, we have success
                    LOGGER.debug("The file {} would have been copied/moved to {}. However, there exists already a file with that name so we do nothing.", sourcePath, targetPath);
                    return new GetTargetPathResult(true, true, targetPath);
                }
                count++;
            } while (exists);
            LOGGER.debug("The file {} existed in the target path somehow (but with different content). Chose new name {}.", sourcePath, targetPath);
            renamed = true;
        }
        return new GetTargetPathResult(false, renamed, targetPath);
    }

    public boolean renameToSuggestedName() throws IOException {
        Optional<Path> oldFilePath = linkedFile.findIn(databaseContext, filePreferences);
        if (oldFilePath.isEmpty()) {
            return false;
        }

        Path targetDirectory = oldFilePath.get().getParent();
        String currentFileName = oldFilePath.get().getFileName().toString();
        String suggestedFileName = getSuggestedFileName();

        if (suggestedFileName.equals(currentFileName)) {
            return false;
        }

        if (suggestedFileName.equals(FileNameUniqueness.eraseDuplicateMarks(currentFileName))) {
            // The current file name ends with something like "(1)", "(2)", etc.
            // and the suggested file name is the same as the current file name without that suffix.
            // In this case, we do not rename the file, because "only" the suffix number would (maybe) change
            return false;
        }

        String uniqueFileName = FileNameUniqueness.generateUniqueFileName(targetDirectory, suggestedFileName);

        // If after ensuring uniqueness we got the same name, no need to rename
        if (uniqueFileName.equals(currentFileName)) {
            return false;
        }

        LOGGER.debug("Renaming file {} to {}", currentFileName, uniqueFileName);
        return renameToName(uniqueFileName, false);
    }

    public boolean renameToName(String targetFileName, boolean overwriteExistingFile) throws IOException {
        Optional<Path> oldFile = linkedFile.findIn(databaseContext, filePreferences);
        if (oldFile.isEmpty()) {
            LOGGER.debug("No file found for linked file {}", linkedFile);
            return false;
        }

        final Path oldPath = oldFile.get();
        Optional<String> oldExtension = FileUtil.getFileExtension(oldPath);
        Optional<String> newExtension = FileUtil.getFileExtension(targetFileName);

        Path newPath;
        if (newExtension.isPresent() || (oldExtension.isEmpty() && newExtension.isEmpty())) {
            newPath = oldPath.resolveSibling(targetFileName);
        } else {
            assert oldExtension.isPresent() && newExtension.isEmpty();
            newPath = oldPath.resolveSibling(targetFileName + "." + oldExtension.get());
        }

        String expandedOldFilePath = oldPath.toString();
        boolean pathsDifferOnlyByCase = newPath.toString().equalsIgnoreCase(expandedOldFilePath)
                && !newPath.toString().equals(expandedOldFilePath);

        // Since Files.exists is sometimes not case-sensitive, the check pathsDifferOnlyByCase ensures that we
        // nonetheless rename files to a new name which just differs by case.
        if (Files.exists(newPath) && !pathsDifferOnlyByCase && !overwriteExistingFile) {
            LOGGER.info("The file {} would have been moved to {}. However, there exists already a file with that name so we do nothing.", oldPath, newPath);
            return false;
        }

        LOGGER.debug("Renaming file {} to {}", oldPath, newPath);
        if (Files.exists(newPath) && !pathsDifferOnlyByCase && overwriteExistingFile) {
            Files.createDirectories(newPath.getParent());
            LOGGER.debug("Overwriting existing file {}", newPath);
            Files.move(oldPath, newPath, StandardCopyOption.REPLACE_EXISTING);
        } else {
            Files.createDirectories(newPath.getParent());
            Files.move(oldPath, newPath);
        }

        updateLink(newPath);

        return true;
    }

    /// Determines the suggested file name based on the pattern specified in the preferences and valid for the file system.
    /// Uses file extension from original file.
    ///
    /// @return the suggested filename, including extension
    public String getSuggestedFileName() {
        String filename = linkedFile.getFileName().orElse("file");
        final String targetFileName = FileUtil.createFileNameFromPattern(databaseContext.getDatabase(), entry, filePreferences.getFileNamePattern())
                                              .orElse(FileUtil.getBaseName(filename));

        return FileUtil.getValidFileName(FileUtil.getFileExtension(filename).map(ext -> targetFileName + "." + ext).orElse(targetFileName));
    }

    /// Determines the suggested file name based on the pattern specified in the preferences and valid for the file system.
    /// Uses the provided file extension.
    ///
    /// @param extension The extension of the file.
    /// @return the suggested filename, including extension
    public String getSuggestedFileName(@NonNull String extension) {
        assert !StringUtil.isBlank(extension);
        String filename = linkedFile.getFileName().orElse("file");
        final String targetFileName = FileUtil.createFileNameFromPattern(databaseContext.getDatabase(), entry, filePreferences.getFileNamePattern())
                                              .orElse(FileUtil.getBaseName(filename));

        return FileUtil.getValidFileName(targetFileName + "." + extension);
    }

    /// Check to see if a file already exists in the target directory.  Search is not case sensitive.
    ///
    /// @return First identified path that matches an existing file. This name can be used in subsequent calls to override the existing file.
    public Optional<Path> findExistingFile(LinkedFile linkedFile, BibEntry entry, String targetFileName) {
        // The .get() is legal without check because the method will always return a value.
        Path targetFilePath = linkedFile.findIn(databaseContext, filePreferences)
                                        .get().getParent().resolve(targetFileName);
        Path oldFilePath = linkedFile.findIn(databaseContext, filePreferences).get();
        // Check if file already exists in directory with different case.
        // This is necessary because other entries may have such a file.
        Optional<Path> matchedByDiffCase = Optional.empty();
        try (Stream<Path> stream = Files.list(oldFilePath.getParent())) {
            matchedByDiffCase = stream.filter(name -> name.toString().equalsIgnoreCase(targetFilePath.toString()))
                                      .findFirst();
        } catch (IOException e) {
            LOGGER.error("Could not get the list of files in target directory", e);
        }
        return matchedByDiffCase;
    }
}
