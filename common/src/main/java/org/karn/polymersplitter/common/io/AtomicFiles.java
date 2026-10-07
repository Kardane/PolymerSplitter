package org.karn.polymersplitter.common.io;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;

public final class AtomicFiles {
    private AtomicFiles() {
    }

    public static void replace(Path source, Path target) throws IOException {
        try {
            Files.move(
                    source,
                    target,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING
            );
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    public static void linkOrCopy(Path source, Path target) throws IOException {
        linkOrCopy(source, target, ignored -> {
        });
    }

    public static void linkOrCopy(
            Path source,
            Path target,
            Validator validator
    ) throws IOException {
        Path normalizedSource = source.toAbsolutePath().normalize();
        Path normalizedTarget = target.toAbsolutePath().normalize();

        if (normalizedSource.equals(normalizedTarget)) {
            validator.validate(normalizedTarget);
            return;
        }

        Path directory = normalizedTarget.getParent();
        if (directory == null) {
            throw new IOException("Target path has no parent: " + normalizedTarget);
        }

        Files.createDirectories(directory);
        Path temp = Files.createTempFile(
                directory,
                "." + normalizedTarget.getFileName(),
                ".tmp"
        );

        try {
            Files.deleteIfExists(temp);

            try {
                Files.createLink(temp, normalizedSource);
            } catch (IOException | UnsupportedOperationException | SecurityException ignored) {
                Files.copy(normalizedSource, temp, StandardCopyOption.REPLACE_EXISTING);
            }

            validator.validate(temp);
            replace(temp, normalizedTarget);
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    public static void deleteRecursively(Path directory) throws IOException {
        try (var paths = Files.walk(directory)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    @FunctionalInterface
    public interface Validator {
        void validate(Path path) throws IOException;
    }
}
