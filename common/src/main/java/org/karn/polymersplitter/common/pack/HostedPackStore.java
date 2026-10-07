package org.karn.polymersplitter.common.pack;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

public final class HostedPackStore {
    private static final String HOSTED_DIRECTORY = "hosted";
    private static final Pattern SHA1_PATTERN = Pattern.compile("[0-9a-f]{40}");

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
        Files.createDirectories(normalizedTarget.getParent());

        Path temp = Files.createTempFile(
                normalizedTarget.getParent(),
                "." + normalizedTarget.getFileName(),
                ".restore"
        );

        try {
            Files.deleteIfExists(temp);

            try {
                Files.createLink(temp, hosted);
            } catch (IOException | UnsupportedOperationException | SecurityException ignored) {
                Files.copy(hosted, temp, StandardCopyOption.REPLACE_EXISTING);
            }

            if (!isValidBlob(temp, sha1, size)) {
                throw new IOException("Restored generation file verification failed: " + target);
            }

            atomicReplace(temp, normalizedTarget);
        } finally {
            Files.deleteIfExists(temp);
        }

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

        if (!PackHashUtil.sha1(source).equals(pack.sha1())) {
            throw new IOException("Split pack SHA-1 changed before hosting: " + source);
        }

        Path directory = target.getParent();
        Path temp = Files.createTempFile(directory, "." + pack.sha1(), ".tmp");

        try {
            Files.deleteIfExists(temp);

            try {
                Files.createLink(temp, source);
            } catch (IOException | UnsupportedOperationException | SecurityException ignored) {
                Files.copy(source, temp, StandardCopyOption.REPLACE_EXISTING);
            }

            if (!isValidBlob(temp, pack.sha1(), pack.size())) {
                throw new IOException("Hosted pack verification failed: " + pack.namespace());
            }

            atomicReplace(temp, target);
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private static boolean isValidBlob(
            Path path,
            String sha1,
            long size
    ) throws IOException {
        if (!Files.isRegularFile(path) || Files.size(path) != size) {
            return false;
        }

        return PackHashUtil.sha1(path).equals(sha1);
    }

    private static void validateSha1(String sha1) throws IOException {
        if (sha1 == null || !SHA1_PATTERN.matcher(sha1).matches()) {
            throw new IOException("Invalid split pack SHA-1: " + sha1);
        }
    }

    private static void atomicReplace(Path source, Path target) throws IOException {
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
}
