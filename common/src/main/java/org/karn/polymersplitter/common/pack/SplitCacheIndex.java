package org.karn.polymersplitter.common.pack;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

public final class SplitCacheIndex {
    private static final String FILE_NAME = "current-cache.tsv";
    private static final Pattern GENERATION_DIRECTORY = Pattern.compile("generation-[0-9a-f]{40}");
    private static final Pattern SHA1_PATTERN = Pattern.compile("[0-9a-f]{40}");
    private static final Pattern SHA256_PATTERN = Pattern.compile("[0-9a-f]{64}");
    private static final Pattern NAMESPACE_PATTERN = Pattern.compile("[a-z0-9_.-]+");

    private SplitCacheIndex() {
    }

    public static Optional<Snapshot> read(Path outputRoot) throws IOException {
        Path root = outputRoot.toAbsolutePath().normalize();
        Path index = root.resolve(FILE_NAME);

        if (!Files.isRegularFile(index)) {
            return Optional.empty();
        }

        String sourceHash = null;
        boolean formatSeen = false;
        boolean sourceSeen = false;
        List<RawPack> rawPacks = new ArrayList<>();

        for (String line : Files.readAllLines(index, StandardCharsets.UTF_8)) {
            if (line.isBlank() || line.startsWith("#")) {
                continue;
            }

            String[] parts = line.split("\t", -1);

            if (parts.length == 2 && "format".equals(parts[0])) {
                if (formatSeen) {
                    throw new IOException("Split cache index contains duplicate format metadata");
                }
                if (!"1".equals(parts[1])) {
                    throw new IOException("Unsupported split cache format: " + parts[1]);
                }

                formatSeen = true;
                continue;
            }

            if (parts.length == 2 && "source".equals(parts[0])) {
                if (sourceSeen) {
                    throw new IOException("Split cache index contains duplicate source metadata");
                }
                if (!SHA1_PATTERN.matcher(parts[1]).matches()) {
                    throw new IOException("Invalid split cache source SHA-1: " + parts[1]);
                }

                sourceHash = parts[1];
                sourceSeen = true;
                continue;
            }

            if (parts.length == 8 && "pack".equals(parts[0])) {
                rawPacks.add(parseRawPack(parts));
                continue;
            }

            throw new IOException("Invalid split cache index line: " + line);
        }

        if (!formatSeen) {
            throw new IOException("Split cache index is missing format metadata");
        }
        if (!sourceSeen || sourceHash == null) {
            throw new IOException("Split cache index is missing source hash");
        }
        if (rawPacks.isEmpty()) {
            throw new IOException("Split cache index contains no resource packs");
        }

        Set<String> namespaces = new HashSet<>();
        List<SplitPack> packs = new ArrayList<>(rawPacks.size());

        for (RawPack raw : rawPacks) {
            if (!namespaces.add(raw.namespace())) {
                throw new IOException("Duplicate cached namespace: " + raw.namespace());
            }

            Path indexedPath = resolveSafe(root, raw.relativePath());
            Path expectedDirectory = root.resolve("generation-" + sourceHash).normalize();
            Path expectedPath = expectedDirectory.resolve(raw.namespace() + ".zip").normalize();

            if (!indexedPath.equals(expectedPath)) {
                throw new IOException(
                        "Cached pack path does not match generation metadata for namespace "
                                + raw.namespace()
                );
            }

            Path verifiedPath = verifyCachedPack(root, indexedPath, raw);

            packs.add(new SplitPack(
                    raw.namespace(),
                    verifiedPath,
                    raw.fingerprint(),
                    raw.sha1(),
                    raw.uuid(),
                    raw.size()
            ));
        }

        reconcileGeneration(outputRoot, sourceHash, packs);
        return Optional.of(new Snapshot(sourceHash, List.copyOf(packs)));
    }

    public static void reconcileGeneration(
            Path outputRoot,
            String sourceHash,
            List<SplitPack> packs
    ) throws IOException {
        Path root = outputRoot.toAbsolutePath().normalize();
        Path generationDirectory = root.resolve("generation-" + sourceHash).normalize();
        Files.createDirectories(generationDirectory);

        Set<String> expectedFiles = new HashSet<>();
        for (SplitPack pack : packs) {
            Path expectedPath = generationDirectory.resolve(pack.namespace() + ".zip").normalize();

            if (!pack.path().toAbsolutePath().normalize().equals(expectedPath)) {
                throw new IOException(
                        "Split pack path is not part of the active generation: " + pack.path()
                );
            }

            expectedFiles.add(pack.namespace() + ".zip");
        }

        try (var entries = Files.list(generationDirectory)) {
            for (Path entry : entries.toList()) {
                String name = entry.getFileName().toString();

                if (Files.isRegularFile(entry)
                        && name.endsWith(".zip")
                        && !expectedFiles.contains(name)) {
                    Files.deleteIfExists(entry);
                }
            }
        }

        SplitPackManifest.write(generationDirectory, packs);
    }

    public static void write(Path outputRoot, String sourceHash, List<SplitPack> packs) throws IOException {
        Path root = outputRoot.toAbsolutePath().normalize();
        Files.createDirectories(root);

        StringBuilder content = new StringBuilder(256 + packs.size() * 256);
        content.append("format\t1\n");
        content.append("source\t").append(sourceHash).append('\n');

        for (SplitPack pack : packs.stream()
                .sorted(Comparator.comparing(SplitPack::namespace))
                .toList()) {
            Path absolutePackPath = pack.path().toAbsolutePath().normalize();
            if (!absolutePackPath.startsWith(root)) {
                throw new IOException("Cached pack is outside output root: " + absolutePackPath);
            }

            String relative = root.relativize(absolutePackPath).toString().replace('\\', '/');

            content.append("pack\t")
                    .append(pack.namespace()).append('\t')
                    .append(pack.fingerprint()).append('\t')
                    .append(pack.sha1()).append('\t')
                    .append(pack.uuid()).append('\t')
                    .append(pack.size()).append('\t')
                    .append(relative).append('\t')
                    .append(pack.path().getFileName())
                    .append('\n');
        }

        Path target = root.resolve(FILE_NAME);
        Path temp = Files.createTempFile(root, ".cache-", ".tsv.tmp");

        try {
            Files.writeString(temp, content.toString(), StandardCharsets.UTF_8);
            atomicReplace(temp, target);
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    public static void cleanupOldGenerations(
            Path outputRoot,
            String currentSourceHash,
            String previousSourceHash
    ) throws IOException {
        Path root = outputRoot.toAbsolutePath().normalize();
        if (!Files.isDirectory(root)) {
            return;
        }

        Set<String> keep = new HashSet<>();
        keep.add("generation-" + currentSourceHash);
        if (previousSourceHash != null && !previousSourceHash.isBlank()) {
            keep.add("generation-" + previousSourceHash);
        }

        try (var entries = Files.list(root)) {
            for (Path entry : entries.toList()) {
                String fileName = entry.getFileName().toString();
                if (!Files.isDirectory(entry)
                        || !GENERATION_DIRECTORY.matcher(fileName).matches()
                        || keep.contains(fileName)) {
                    continue;
                }

                deleteRecursively(entry);
            }
        }
    }

    private static RawPack parseRawPack(String[] parts) throws IOException {
        String namespace = parts[1];
        String fingerprint = parts[2];
        String sha1 = parts[3];
        String uuidText = parts[4];
        String sizeText = parts[5];
        String relativePath = parts[6];
        String fileName = parts[7];

        if (!NAMESPACE_PATTERN.matcher(namespace).matches()) {
            throw new IOException("Invalid cached namespace: " + namespace);
        }
        if (!SHA256_PATTERN.matcher(fingerprint).matches()) {
            throw new IOException("Invalid cached fingerprint for namespace " + namespace);
        }
        if (!SHA1_PATTERN.matcher(sha1).matches()) {
            throw new IOException("Invalid cached SHA-1 for namespace " + namespace);
        }
        if (!(namespace + ".zip").equals(fileName)) {
            throw new IOException("Invalid cached file name for namespace " + namespace);
        }

        final UUID uuid;
        final long size;

        try {
            uuid = UUID.fromString(uuidText);
        } catch (IllegalArgumentException e) {
            throw new IOException("Invalid cached UUID for namespace " + namespace, e);
        }

        if (!PackIdUtil.uuidForNamespace(namespace).equals(uuid)) {
            throw new IOException("Cached UUID does not match namespace " + namespace);
        }

        try {
            size = Long.parseLong(sizeText);
        } catch (NumberFormatException e) {
            throw new IOException("Invalid cached size for namespace " + namespace, e);
        }

        if (size < 0) {
            throw new IOException("Negative cached size for namespace " + namespace);
        }

        return new RawPack(
                namespace,
                fingerprint,
                sha1,
                uuid,
                size,
                relativePath
        );
    }

    private static Path verifyCachedPack(
            Path root,
            Path indexedPath,
            RawPack raw
    ) throws IOException {
        if (isValidPackFile(indexedPath, raw.sha1(), raw.size())) {
            return indexedPath;
        }

        Optional<Path> hosted = HostedPackStore.findValidBlob(
                root,
                raw.sha1(),
                raw.size()
        );

        if (hosted.isPresent()) {
            return HostedPackStore.restoreGenerationFile(
                    root,
                    raw.sha1(),
                    raw.size(),
                    indexedPath
            );
        }

        throw new IOException(
                "Cached split pack is missing or corrupted for namespace " + raw.namespace()
        );
    }

    private static boolean isValidPackFile(
            Path path,
            String sha1,
            long size
    ) throws IOException {
        if (!Files.isRegularFile(path) || Files.size(path) != size) {
            return false;
        }

        return PackHashUtil.sha1(path).equals(sha1);
    }

    private static Path resolveSafe(Path root, String relativeText) throws IOException {
        final Path relative;

        try {
            relative = Path.of(relativeText);
        } catch (RuntimeException e) {
            throw new IOException("Invalid path in split cache index: " + relativeText, e);
        }

        if (relative.isAbsolute()) {
            throw new IOException("Absolute path in split cache index: " + relativeText);
        }

        Path resolved = root.resolve(relative).normalize();
        if (!resolved.startsWith(root)) {
            throw new IOException("Unsafe path in split cache index: " + relativeText);
        }

        return resolved;
    }

    private static void deleteRecursively(Path directory) throws IOException {
        try (var paths = Files.walk(directory)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    private static void atomicReplace(Path source, Path target) throws IOException {
        try {
            Files.move(source, target,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private record RawPack(
            String namespace,
            String fingerprint,
            String sha1,
            UUID uuid,
            long size,
            String relativePath
    ) {
    }

    public record Snapshot(String sourceHash, List<SplitPack> packs) {
    }
}
