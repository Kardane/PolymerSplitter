package org.karn.polymersplitter.common.pack;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public final class LegacyCacheMigration {
    private static final String LEGACY_INDEX = "current-cache.tsv";

    private LegacyCacheMigration() {
    }

    public static boolean importBlobsIfNeeded(Path outputRoot) throws IOException {
        Path root = outputRoot.toAbsolutePath().normalize();

        if (SplitCacheIndex.exists(root)) {
            return false;
        }

        Path legacyIndex = root.resolve(LEGACY_INDEX);
        if (!Files.isRegularFile(legacyIndex)) {
            return false;
        }

        LegacySnapshot legacy = readLegacy(root, legacyIndex);

        for (LegacyPack pack : legacy.packs()) {
            Optional<Path> existing = HostedPackStore.findValidBlob(
                    root,
                    pack.sha1(),
                    pack.size()
            );

            if (existing.isEmpty()) {
                HostedPackStore.importLegacyBlob(
                        root,
                        pack.generationPath(),
                        pack.sha1(),
                        pack.size()
                );
            }
        }

        // Legacy metadata has no output-compatibility key. Importing its immutable
        // blobs is safe, but it must not be relabeled as a current compatible index.
        return true;
    }

    private static LegacySnapshot readLegacy(
            Path root,
            Path legacyIndex
    ) throws IOException {
        String sourceHash = null;
        boolean formatSeen = false;
        boolean sourceSeen = false;
        List<LegacyPack> packs = new ArrayList<>();
        Set<String> namespaces = new HashSet<>();

        for (String line : Files.readAllLines(legacyIndex, StandardCharsets.UTF_8)) {
            if (line.isBlank() || line.startsWith("#")) {
                continue;
            }

            String[] parts = line.split("\t", -1);

            if (parts.length == 2 && "format".equals(parts[0])) {
                if (formatSeen || !"1".equals(parts[1])) {
                    throw new IOException("Unsupported or duplicate legacy cache format");
                }
                formatSeen = true;
                continue;
            }

            if (parts.length == 2 && "source".equals(parts[0])) {
                if (sourceSeen || !Hashes.isSha1(parts[1])) {
                    throw new IOException("Invalid or duplicate legacy source SHA-1");
                }
                sourceHash = parts[1];
                sourceSeen = true;
                continue;
            }

            if (parts.length != 8 || !"pack".equals(parts[0])) {
                throw new IOException("Invalid legacy split cache line: " + line);
            }

            String namespace = parts[1];
            String fingerprint = parts[2];
            String sha1 = parts[3];
            String uuidText = parts[4];
            String sizeText = parts[5];
            String relativePath = parts[6];
            String fileName = parts[7];

            if (!ResourceNamespaces.isValid(namespace)
                    || !namespaces.add(namespace)) {
                throw new IOException("Invalid or duplicate legacy namespace: " + namespace);
            }
            if (!Hashes.isSha256(fingerprint) || !Hashes.isSha1(sha1)) {
                throw new IOException("Invalid legacy hash metadata for namespace " + namespace);
            }
            if (!(namespace + ".zip").equals(fileName)) {
                throw new IOException("Invalid legacy file name for namespace " + namespace);
            }

            final UUID uuid;
            final long size;

            try {
                uuid = UUID.fromString(uuidText);
            } catch (IllegalArgumentException e) {
                throw new IOException("Invalid legacy UUID for namespace " + namespace, e);
            }

            if (!PackIdUtil.uuidForNamespace(namespace).equals(uuid)) {
                throw new IOException("Legacy UUID does not match namespace " + namespace);
            }

            try {
                size = Long.parseLong(sizeText);
            } catch (NumberFormatException e) {
                throw new IOException("Invalid legacy size for namespace " + namespace, e);
            }

            if (size < 0) {
                throw new IOException("Negative legacy size for namespace " + namespace);
            }

            if (sourceHash == null) {
                throw new IOException("Legacy source metadata must appear before pack entries");
            }

            Path expected = root.resolve("generation-" + sourceHash)
                    .resolve(namespace + ".zip")
                    .normalize();
            Path indexed = resolveSafe(root, relativePath);

            if (!indexed.equals(expected)) {
                throw new IOException(
                        "Legacy cache path does not match generation metadata for namespace "
                                + namespace
                );
            }

            packs.add(new LegacyPack(
                    namespace,
                    fingerprint,
                    sha1,
                    uuid,
                    size,
                    indexed
            ));
        }

        if (!formatSeen || !sourceSeen || sourceHash == null) {
            throw new IOException("Legacy split cache metadata is incomplete");
        }
        if (packs.isEmpty()) {
            throw new IOException("Legacy split cache contains no packs");
        }

        return new LegacySnapshot(sourceHash, List.copyOf(packs));
    }

    private static Path resolveSafe(Path root, String relativeText) throws IOException {
        final Path relative;

        try {
            relative = Path.of(relativeText);
        } catch (RuntimeException e) {
            throw new IOException("Invalid legacy cache path: " + relativeText, e);
        }

        if (relative.isAbsolute()) {
            throw new IOException("Absolute path in legacy split cache: " + relativeText);
        }

        Path resolved = root.resolve(relative).normalize();
        if (!resolved.startsWith(root)) {
            throw new IOException("Unsafe path in legacy split cache: " + relativeText);
        }

        return resolved;
    }

    private record LegacySnapshot(String sourceHash, List<LegacyPack> packs) {
    }

    private record LegacyPack(
            String namespace,
            String fingerprint,
            String sha1,
            UUID uuid,
            long size,
            Path generationPath
    ) {
    }
}
