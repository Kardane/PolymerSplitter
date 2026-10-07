package org.karn.polymersplitter.common.pack;

import org.karn.polymersplitter.common.io.AtomicFiles;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

public final class SplitRecovery {
    private static final Pattern GENERATION_DIRECTORY = Pattern.compile("generation-[0-9a-f]{40}");
    private static final Pattern CACHE_TEMP = Pattern.compile("^\\.cache-.*\\.tsv\\.tmp$");
    private static final Pattern MANIFEST_TEMP = Pattern.compile("^\\.manifest-.*\\.json\\.tmp$");
    private static final Pattern PACK_TEMP = Pattern.compile("^\\.[a-z0-9_.-]+\\.zip.*\\.tmp$");
    private static final Pattern PACK_REUSE = Pattern.compile("^\\.[a-z0-9_.-]+\\.zip.*\\.reuse$");
    private static final Pattern HOSTED_TEMP = Pattern.compile("^\\.[0-9a-f]{40}.*\\.tmp$");

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

    public static int cleanupIncompleteGenerations(
            Path outputRoot,
            String currentSourceHash
    ) throws IOException {
        Path root = outputRoot.toAbsolutePath().normalize();

        if (!Files.isDirectory(root)) {
            return 0;
        }

        String currentDirectory = currentSourceHash == null
                ? null
                : "generation-" + currentSourceHash;

        int deleted = 0;

        try (var entries = Files.list(root)) {
            for (Path entry : entries.toList()) {
                String name = entry.getFileName().toString();

                if (!Files.isDirectory(entry)
                        || !GENERATION_DIRECTORY.matcher(name).matches()
                        || name.equals(currentDirectory)
                        || Files.isRegularFile(entry.resolve(SplitPackManifest.FILE_NAME))) {
                    continue;
                }

                AtomicFiles.deleteRecursively(entry);
                deleted++;
            }
        }

        return deleted;
    }

    private static boolean isOwnedTemporaryFile(Path path) {
        String name = path.getFileName().toString();

        return CACHE_TEMP.matcher(name).matches()
                || MANIFEST_TEMP.matcher(name).matches()
                || PACK_TEMP.matcher(name).matches()
                || PACK_REUSE.matcher(name).matches()
                || HOSTED_TEMP.matcher(name).matches();
    }

}
