package org.jabref.model.database;

import java.nio.file.Path;
import java.util.Optional;

import org.jspecify.annotations.NullMarked;

/// Holds the configured file directories in a fixed order.
@NullMarked
public record FileDirectories(Optional<Path> userDirectory,
                              Optional<Path> libraryDirectory,
                              Optional<Path> fallbackDirectory) {
}
