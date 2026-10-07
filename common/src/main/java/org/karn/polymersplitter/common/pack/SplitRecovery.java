package org.karn.polymersplitter.common.pack;

import org.karn.polymersplitter.common.io.AtomicFiles;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

public final class SplitRecovery {
    private static final Pattern GENERATION_DIRECTORY = Pattern.compile("generation-[0-9a-f]{40}");
    private static final Pattern LEGACY_CACHE_TEMP = Pattern.compile("^\\.cache-.*\\.tsv\\.tmp$");
    private static final Pattern INDEX_TEMP = Pattern.compile("^\\.index-.*\\.json\\.tmp$");
    private static final Pattern MANIFEST_TEMP = Pattern.compile("^\\.manifest-.*\\.json\\.tmp$");
    private static final Pattern PACK_TEMP = Pattern.compile("^\\.[a-z0-9_.-]+\\.zip.*\\.tmp$");
    private static final Pattern PACK_REUSE = Pattern.compile("^\\.[a-z0-9_.-]+\\.zip.*\\.reuse$");
    private static final Pattern HOSTED_BLOB = Pattern.compile("^[0-9a-f]{40}\\.zip$");

    private SplitRecovery() {
    }

    public static int cleanupTemporaryFiles(Path outputRoot) throws IOException {
        Path root = outputRoot.toAbsolutePath().normalize();

        if (!Files.isDirectory(root)) {
            return 0;
        }

        int deleted = 0;

        try (var paths = Files.walk(root)) {
            for (Path path : paths
                    .filter(Files::isRegularFile)
                    .filter(SplitRecovery::isOwnedTemporaryFile)
                    .toList()) {
                if (Files.deleteIfExists(path)) {
                    deleted++;
                }
            }
        }

        return deleted;
    }

    public static int cleanupLegacyArtifacts(Path outputRoot) throws IOException {
        Path root = outputRoot.toAbsolutePath().normalize();

        if (!SplitCacheIndex.exists(root)) {
            return 0;
        }

        int deleted = 0;

        if (Files.deleteIfExists(root.resolve("current-cache.tsv"))) {
            deleted++;
        }

        if (!Files.isDirectory(root)) {
            return deleted;
        }

        try (var entries = Files.list(root)) {
            for (Path entry : entries.toList()) {
                String name = entry.getFileName().toString();
                if (!Files.isDirectory(entry)
                        || !GENERATION_DIRECTORY.matcher(name).matches()) {
                    continue;
                }

                AtomicFiles.deleteRecursively(entry);
                deleted++;
            }
        }

        return deleted;
    }

    public static int cleanupUnreferencedHostedBlobs(
            Path outputRoot,
            List<SplitPack> activePacks,
            int retentionDays
    ) throws IOException {
        if (retentionDays < -1) {
            throw new IllegalArgumentException(
                    "retentionDays must be -1 or non-negative"
            );
        }
        if (retentionDays == -1) {
            return 0;
        }

        Path root = outputRoot.toAbsolutePath().normalize();
        Path hosted = root.resolve(HostedPackStore.DIRECTORY_NAME);

        if (!Files.isDirectory(hosted)) {
            return 0;
        }

        Set<String> keep = new HashSet<>();
        for (SplitPack pack : activePacks) {
            keep.add(pack.sha1() + ".zip");
        }

        Instant cutoff = retentionDays == 0
                ? null
                : Instant.now().minus(retentionDays, ChronoUnit.DAYS);

        int deleted = 0;
        try (var entries = Files.list(hosted)) {
            for (Path entry : entries.toList()) {
                String name = entry.getFileName().toString();
                if (!Files.isRegularFile(entry)
                        || !HOSTED_BLOB.matcher(name).matches()
                        || keep.contains(name)) {
                    continue;
                }

                if (cutoff != null
                        && Files.getLastModifiedTime(entry).toInstant().isAfter(cutoff)) {
                    continue;
                }

                if (Files.deleteIfExists(entry)) {
                    deleted++;
                }
            }
        }

        return deleted;
    }

    private static boolean isOwnedTemporaryFile(Path path) {
        String name = path.getFileName().toString();

        return LEGACY_CACHE_TEMP.matcher(name).matches()
                || INDEX_TEMP.matcher(name).matches()
                || MANIFEST_TEMP.matcher(name).matches()
                || PACK_TEMP.matcher(name).matches()
                || PACK_REUSE.matcher(name).matches();
    }
}
