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
    private static final String HOSTED_DIRECTORY = "hosted";

    private HostedPackStore() {
    }

    public static Map<String, Path> materialize(
            Path outputRoot,
            List<SplitPack> packs
    ) throws IOException {
        Objects.requireNonNull(outputRoot, "outputRoot");
        Objects.requireNonNull(packs, "packs");

        Path root = outputRoot.toAbsolutePath().normalize();
        Path hostedDirectory = root.resolve(HOSTED_DIRECTORY);
        Files.createDirectories(hostedDirectory);

        Map<String, Path> result = new LinkedHashMap<>();

        for (SplitPack pack : packs) {
            validateSha1(pack.sha1());

            Path target = hostedDirectory.resolve(pack.sha1() + ".zip");
            Path previous = result.putIfAbsent(pack.sha1(), target);

            if (previous != null) {
                continue;
            }

            ensureBlob(pack, target);
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

        Path target = outputRoot.toAbsolutePath()
                .normalize()
                .resolve(HOSTED_DIRECTORY)
                .resolve(sha1 + ".zip");

        if (!isValidBlob(target, sha1, size)) {
            return Optional.empty();
        }

        return Optional.of(target);
    }

    public static Path restoreGenerationFile(
            Path outputRoot,
            String sha1,
            long size,
            Path target
    ) throws IOException {
        Path hosted = findValidBlob(outputRoot, sha1, size)
                .orElseThrow(() -> new IOException(
                        "No valid hosted blob is available for SHA-1 " + sha1
                ));

        Path normalizedTarget = target.toAbsolutePath().normalize();
        AtomicFiles.linkOrCopy(
                hosted,
                normalizedTarget,
                candidate -> requireValidBlob(
                        candidate,
                        sha1,
                        size,
                        "Restored generation file verification failed: " + target
                )
        );

        return normalizedTarget;
    }

    private static void ensureBlob(SplitPack pack, Path target) throws IOException {
        if (isValidBlob(target, pack.sha1(), pack.size())) {
            return;
        }

        Path source = pack.path().toAbsolutePath().normalize();
        if (!Files.isRegularFile(source)) {
            throw new IOException("Split pack does not exist: " + source);
        }

        if (Files.size(source) != pack.size()) {
            throw new IOException("Split pack size changed before hosting: " + source);
        }

        if (!Hashes.sha1(source).equals(pack.sha1())) {
            throw new IOException("Split pack SHA-1 changed before hosting: " + source);
        }

        AtomicFiles.linkOrCopy(
                source,
                target,
                candidate -> requireValidBlob(
                        candidate,
                        pack.sha1(),
                        pack.size(),
                        "Hosted pack verification failed: " + pack.namespace()
                )
        );
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
