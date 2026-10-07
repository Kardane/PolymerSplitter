package org.karn.polymersplitter.common.pack;

import org.karn.polymersplitter.common.io.AtomicFiles;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

public final class SplitCacheIndex {
    private static final String FILE_NAME = "current-cache.tsv";
    private static final Pattern GENERATION_DIRECTORY = Pattern.compile("generation-[0-9a-f]{40}");

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
                if (!Hashes.isSha1(parts[1])) {
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

            packs.add(new SplitPack(
                    raw.namespace(),
                    indexedPath,
                    raw.fingerprint(),
                    raw.sha1(),
                    raw.uuid(),
                    raw.size()
            ));
        }

        return Optional.of(new Snapshot(sourceHash, List.copyOf(packs)));
    }

    public static Verification verify(
            Path outputRoot,
            Snapshot snapshot
    ) throws IOException {
        Path root = outputRoot.toAbsolutePath().normalize();
        Objects.requireNonNull(snapshot, "snapshot");

        List<String> restoreNamespaces = new ArrayList<>();

        for (SplitPack pack : snapshot.packs()) {
            if (isValidPackFile(pack.path(), pack.sha1(), pack.size())) {
                continue;
            }

            if (HostedPackStore.findValidBlob(root, pack.sha1(), pack.size()).isPresent()) {
                restoreNamespaces.add(pack.namespace());
                continue;
            }

            throw new IOException(
                    "Cached split pack is missing or corrupted for namespace " + pack.namespace()
            );
        }

        return new Verification(snapshot, List.copyOf(restoreNamespaces));
    }

    public static Snapshot repair(
            Path outputRoot,
            Verification verification
    ) throws IOException {
        Path root = outputRoot.toAbsolutePath().normalize();
        Objects.requireNonNull(verification, "verification");

        Snapshot snapshot = verification.snapshot();

        for (String namespace : verification.restoreNamespaces()) {
            SplitPack pack = snapshot.packs().stream()
                    .filter(candidate -> candidate.namespace().equals(namespace))
                    .findFirst()
                    .orElseThrow(() -> new IOException(
                            "Cache repair references unknown namespace " + namespace
                    ));

            HostedPackStore.restoreGenerationFile(
                    root,
                    pack.sha1(),
                    pack.size(),
                    pack.path()
            );
        }

        reconcileGeneration(root, snapshot.sourceHash(), snapshot.packs());
        return snapshot;
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
            AtomicFiles.replace(temp, target);
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

                AtomicFiles.deleteRecursively(entry);
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

        if (!ResourceNamespaces.isValid(namespace)) {
            throw new IOException("Invalid cached namespace: " + namespace);
        }
        if (!Hashes.isSha256(fingerprint)) {
            throw new IOException("Invalid cached fingerprint for namespace " + namespace);
        }
        if (!Hashes.isSha1(sha1)) {
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

    private static boolean isValidPackFile(
            Path path,
            String sha1,
            long size
    ) throws IOException {
        if (!Files.isRegularFile(path) || Files.size(path) != size) {
            return false;
        }

        return Hashes.sha1(path).equals(sha1);
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
        public Snapshot {
            Objects.requireNonNull(sourceHash, "sourceHash");
            packs = List.copyOf(packs);
        }
    }

    public record Verification(
            Snapshot snapshot,
            List<String> restoreNamespaces
    ) {
        public Verification {
            Objects.requireNonNull(snapshot, "snapshot");
            restoreNamespaces = List.copyOf(restoreNamespaces);
        }
    }
}
