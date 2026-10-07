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

    private SplitCacheIndex() {
    }

    public static Optional<Snapshot> read(Path outputRoot) throws IOException {
        Path root = outputRoot.toAbsolutePath().normalize();
        Path index = root.resolve(FILE_NAME);

        if (!Files.isRegularFile(index)) {
            return Optional.empty();
        }

        String sourceHash = null;
        List<SplitPack> packs = new ArrayList<>();

        for (String line : Files.readAllLines(index, StandardCharsets.UTF_8)) {
            if (line.isBlank() || line.startsWith("#")) {
                continue;
            }

            String[] parts = line.split("\t", -1);
            if (parts.length == 2 && "format".equals(parts[0])) {
                if (!"1".equals(parts[1])) {
                    throw new IOException("Unsupported split cache format: " + parts[1]);
                }
                continue;
            }

            if (parts.length == 2 && "source".equals(parts[0])) {
                sourceHash = parts[1];
                continue;
            }

            if (parts.length == 8 && "pack".equals(parts[0])) {
                String namespace = parts[1];
                String fingerprint = parts[2];
                String sha1 = parts[3];
                UUID uuid = UUID.fromString(parts[4]);
                long size = Long.parseLong(parts[5]);
                Path path = resolveSafe(root, parts[6]);
                String fileName = parts[7];

                if (!path.getFileName().toString().equals(fileName)) {
                    throw new IOException("Invalid cached pack path metadata for namespace " + namespace);
                }

                packs.add(new SplitPack(
                        namespace,
                        path,
                        fingerprint,
                        sha1,
                        uuid,
                        size
                ));
                continue;
            }

            throw new IOException("Invalid split cache index line: " + line);
        }

        if (sourceHash == null || sourceHash.isBlank()) {
            throw new IOException("Split cache index is missing source hash");
        }

        return Optional.of(new Snapshot(sourceHash, List.copyOf(packs)));
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

    private static Path resolveSafe(Path root, String relativeText) throws IOException {
        Path relative = Path.of(relativeText);
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

    public record Snapshot(String sourceHash, List<SplitPack> packs) {
    }
}
