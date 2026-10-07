package org.karn.polymersplitter.common.pack;

import org.karn.polymersplitter.common.io.AtomicFiles;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public final class HostedPackStore {
    public static final String DIRECTORY_NAME = "hosted";

    private HostedPackStore() {
    }

    public static Path directory(Path outputRoot) throws IOException {
        Path directory = outputRoot.toAbsolutePath().normalize().resolve(DIRECTORY_NAME);
        Files.createDirectories(directory);
        return directory;
    }

    public static Path pathFor(Path outputRoot, String sha1) throws IOException {
        validateSha1(sha1);
        return outputRoot.toAbsolutePath()
                .normalize()
                .resolve(DIRECTORY_NAME)
                .resolve(sha1 + ".zip");
    }

    public static Path commitGeneratedBlob(
            Path outputRoot,
            Path generatedFile,
            String sha1,
            long size
    ) throws IOException {
        Objects.requireNonNull(generatedFile, "generatedFile");
        validateSha1(sha1);

        if (size < 0) {
            throw new IOException("Invalid generated pack size: " + size);
        }

        Path target = pathFor(outputRoot, sha1);
        Files.createDirectories(target.getParent());

        if (Files.isRegularFile(target)) {
            if (isValidBlob(target, sha1, size)) {
                return target;
            }

            throw new IOException("Existing hosted blob is corrupted: " + target);
        }

        if (!Files.isRegularFile(generatedFile) || Files.size(generatedFile) != size) {
            throw new IOException("Generated split pack changed before blob commit: " + generatedFile);
        }

        AtomicFiles.replace(generatedFile, target);
        return target;
    }

    public static Map<String, Path> resolvePublished(
            Path outputRoot,
            List<SplitPack> packs
    ) throws IOException {
        Objects.requireNonNull(outputRoot, "outputRoot");
        Objects.requireNonNull(packs, "packs");

        Map<String, Path> result = new LinkedHashMap<>();

        for (SplitPack pack : packs) {
            Path expected = pathFor(outputRoot, pack.sha1());
            Path actual = pack.path().toAbsolutePath().normalize();

            if (!actual.equals(expected)) {
                throw new IOException(
                        "Split pack is outside the content-addressed store: " + actual
                );
            }
            if (!Files.isRegularFile(actual) || Files.size(actual) != pack.size()) {
                throw new IOException("Published split blob is missing or changed: " + actual);
            }

            result.putIfAbsent(pack.sha1(), actual);
        }

        return Map.copyOf(result);
    }

    public static Optional<Path> findValidBlob(
            Path outputRoot,
            String sha1,
            long size
    ) throws IOException {
        Objects.requireNonNull(outputRoot, "outputRoot");
        validateSha1(sha1);

        if (size < 0) {
            throw new IOException("Invalid hosted pack size: " + size);
        }

        Path target = pathFor(outputRoot, sha1);
        return isValidBlob(target, sha1, size)
                ? Optional.of(target)
                : Optional.empty();
    }

    public static Path importLegacyBlob(
            Path outputRoot,
            Path source,
            String sha1,
            long size
    ) throws IOException {
        Path target = pathFor(outputRoot, sha1);

        if (isValidBlob(target, sha1, size)) {
            return target;
        }

        if (!isValidBlob(source, sha1, size)) {
            throw new IOException("Legacy split pack is missing or corrupted: " + source);
        }

        AtomicFiles.linkOrCopy(
                source,
                target,
                candidate -> requireValidBlob(
                        candidate,
                        sha1,
                        size,
                        "Migrated hosted blob verification failed: " + source
                )
        );

        return target;
    }

    private static boolean isValidBlob(
            Path path,
            String sha1,
            long size
    ) throws IOException {
        if (!Files.isRegularFile(path) || Files.size(path) != size) {
            return false;
        }

        return Hashes.sha1(path).equals(sha1);
    }

    private static void requireValidBlob(
            Path path,
            String sha1,
            long size,
            String error
    ) throws IOException {
        if (!isValidBlob(path, sha1, size)) {
            throw new IOException(error);
        }
    }

    private static void validateSha1(String sha1) throws IOException {
        if (!Hashes.isSha1(sha1)) {
            throw new IOException("Invalid split pack SHA-1: " + sha1);
        }
    }
}
