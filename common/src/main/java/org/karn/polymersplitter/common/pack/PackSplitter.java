package org.karn.polymersplitter.common.pack;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.DigestOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

public final class PackSplitter {
    private static final System.Logger LOGGER = System.getLogger("PolymerSplitter");
    private static final String ASSETS_PREFIX = "assets/";
    private static final String PACK_META = "pack.mcmeta";
    private static final String PACK_ICON = "pack.png";
    private static final String MCASSETS_ROOT = "assets/.mcassetsroot";
    private static final LocalDateTime DETERMINISTIC_TIME = LocalDateTime.of(1980, 1, 1, 0, 0);
    private static final int IO_BUFFER_SIZE = 64 * 1024;
    private static final byte[] FINGERPRINT_SCHEMA =
            "polymersplitter:namespace-fingerprint:v4".getBytes(StandardCharsets.UTF_8);

    public List<SplitPack> split(
            Path sourcePack,
            Path outputRoot,
            SplitterConfig config,
            List<SplitPack> reusablePacks
    ) throws IOException {
        Objects.requireNonNull(sourcePack, "sourcePack");
        Objects.requireNonNull(outputRoot, "outputRoot");
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(reusablePacks, "reusablePacks");

        if (!Files.isRegularFile(sourcePack)) {
            throw new IOException("Source resource pack does not exist or is not a file: " + sourcePack);
        }

        Path hostedDirectory = HostedPackStore.directory(outputRoot);
        byte[] ioBuffer = new byte[IO_BUFFER_SIZE];

        Map<String, SplitPack> reusableByNamespace = new HashMap<>();
        for (SplitPack pack : reusablePacks) {
            reusableByNamespace.put(pack.namespace(), pack);
        }

        try (ZipFile zip = new ZipFile(sourcePack.toFile())) {
            ZipEntry packMeta = zip.getEntry(PACK_META);
            if (packMeta == null || packMeta.isDirectory()) {
                throw new IOException("Source resource pack is missing " + PACK_META);
            }

            PackMetadata metadata = PackMetadata.read(zip, packMeta);
            ZipEntry packIcon = config.copyPackIcon() ? zip.getEntry(PACK_ICON) : null;
            Map<String, List<ZipEntry>> byNamespace = collectPackLayout(zip, metadata);
            String primaryNamespace = byNamespace.containsKey("minecraft")
                    ? "minecraft" : byNamespace.keySet().stream().min(String::compareTo).orElseThrow();
            List<ZipEntry> primaryEntries = byNamespace.get(primaryNamespace);
            long minimumSize = config.minSplitPackSizeBytes();
            byte[] sharedFingerprint = fingerprintSharedFiles(
                    zip,
                    packMeta,
                    packIcon,
                    ioBuffer
            );

            List<SplitPack> packs = new ArrayList<>(byNamespace.size());
            // Write the primary pack last, after small namespaces have joined its entries.
            List<String> namespaces = byNamespace.keySet().stream()
                    .sorted(Comparator.comparingInt((String namespace) ->
                            namespace.equals(primaryNamespace) ? 1 : 0).thenComparing(String::compareTo))
                    .toList();
            for (String namespace : namespaces) {
                List<ZipEntry> entries = byNamespace.get(namespace);
                boolean primary = namespace.equals(primaryNamespace);
                if (primary) {
                    entries.sort(Comparator.comparing(ZipEntry::getName));
                } else if (!config.shouldSplitIndependently(namespace)) {
                    primaryEntries.addAll(entries);
                    logPolicyMergedNamespace(namespace, primaryNamespace);
                    continue;
                }

                String fingerprint = fingerprintNamespace(
                        zip,
                        sharedFingerprint,
                        entries,
                        ioBuffer
                );

                SplitPack reusable = reusableByNamespace.get(namespace);
                if (canReuse(reusable, fingerprint)) {
                    if (!primary && minimumSize > 0 && reusable.size() <= minimumSize) {
                        primaryEntries.addAll(entries);
                        logMergedNamespace(namespace, primaryNamespace, reusable.size());
                        continue;
                    }
                    packs.add(new SplitPack(
                            namespace,
                            reusable.path(),
                            fingerprint,
                            reusable.sha1(),
                            PackIdUtil.uuidForNamespace(namespace),
                            reusable.size()
                    ));
                    continue;
                }

                Path temp = Files.createTempFile(
                        hostedDirectory,
                        "." + namespace + ".zip-",
                        ".tmp"
                );

                try {
                    WrittenZip written = writeNamespacePack(
                            zip,
                            packMeta,
                            packIcon,
                            entries,
                            temp,
                            config.deterministicZip(),
                            config.compressionLevel(),
                            ioBuffer
                    );

                    if (!primary && minimumSize > 0 && written.size() <= minimumSize) {
                        primaryEntries.addAll(entries);
                        logMergedNamespace(namespace, primaryNamespace, written.size());
                        continue;
                    }

                    Path hosted = HostedPackStore.commitGeneratedBlob(
                            outputRoot,
                            temp,
                            written.sha1(),
                            written.size()
                    );

                    packs.add(new SplitPack(
                            namespace,
                            hosted,
                            fingerprint,
                            written.sha1(),
                            PackIdUtil.uuidForNamespace(namespace),
                            written.size()
                    ));
                } finally {
                    Files.deleteIfExists(temp);
                }
            }

            return List.copyOf(packs);
        }
    }

    private static boolean canReuse(SplitPack pack, String fingerprint) {
        if (pack == null || !fingerprint.equals(pack.fingerprint()) || !Files.isRegularFile(pack.path())) {
            return false;
        }

        try {
            return Files.size(pack.path()) == pack.size();
        } catch (IOException ignored) {
            return false;
        }
    }

    private static void logPolicyMergedNamespace(
            String namespace,
            String primaryNamespace
    ) {
        LOGGER.log(System.Logger.Level.INFO, "Merged namespace '" + namespace
                + "' into primary pack '" + primaryNamespace + "' by namespace policy");
    }

    private static void logMergedNamespace(String namespace, String primaryNamespace, long size) {
        LOGGER.log(System.Logger.Level.INFO, "Merged small namespace '" + namespace
                + "' into primary pack '" + primaryNamespace + "': zipBytes=" + size);
    }

    private static Map<String, List<ZipEntry>> collectPackLayout(
            ZipFile zip,
            PackMetadata metadata
    ) throws IOException {
        Map<String, List<ZipEntry>> grouped = new LinkedHashMap<>();
        List<ZipEntry> primaryOnlyEntries = new ArrayList<>();
        Set<String> overlayDirectories = new LinkedHashSet<>(metadata.overlayDirectories());

        List<? extends ZipEntry> entries = zip.stream()
                .filter(entry -> !entry.isDirectory())
                .sorted(Comparator.comparing(ZipEntry::getName))
                .toList();

        int skippedUnnamedEntries = 0;
        long skippedUnnamedBytes = 0;
        for (ZipEntry entry : entries) {
            // goinmul: Unnamed resources have no recoverable path; restore them only when the producer supplies one.
            if (entry.getName().isEmpty()) {
                skippedUnnamedEntries++;
                skippedUnnamedBytes += entry.getSize();
                continue;
            }
            String name = validateEntryName(entry.getName());

            if (PACK_META.equals(name) || PACK_ICON.equals(name)) {
                continue;
            }

            if (name.startsWith(ASSETS_PREFIX)) {
                if (!MCASSETS_ROOT.equals(name)
                        && name.indexOf('/', ASSETS_PREFIX.length()) < 0) {
                    primaryOnlyEntries.add(entry);
                } else {
                    addAssetEntry(grouped, entry, name);
                }
                continue;
            }

            int separator = name.indexOf('/');
            if (separator < 0) {
                primaryOnlyEntries.add(entry);
                continue;
            }

            String rootDirectory = name.substring(0, separator);
            if (!overlayDirectories.contains(rootDirectory)) {
                primaryOnlyEntries.add(entry);
                continue;
            }

            String overlayRelativePath = name.substring(separator + 1);

            if (PACK_META.equals(overlayRelativePath) || PACK_ICON.equals(overlayRelativePath)) {
                continue;
            }

            if (!overlayRelativePath.startsWith(ASSETS_PREFIX)
                    || (!MCASSETS_ROOT.equals(overlayRelativePath)
                    && overlayRelativePath.indexOf('/', ASSETS_PREFIX.length()) < 0)) {
                primaryOnlyEntries.add(entry);
                continue;
            }

            addAssetEntry(grouped, entry, overlayRelativePath);
        }

        if (skippedUnnamedEntries > 0) {
            LOGGER.log(System.Logger.Level.WARNING,
                    "Excluded ZIP entries with empty names from split packs: count="
                            + skippedUnnamedEntries + ", uncompressedBytes=" + skippedUnnamedBytes
                            + ". Their content is omitted; fix the resource producer to supply valid paths.");
        }

        if (grouped.isEmpty()) {
            throw new IOException("Source resource pack contains no resource namespaces");
        }
        String primaryNamespace = grouped.containsKey("minecraft")
                ? "minecraft"
                : grouped.keySet().stream().min(String::compareTo).orElseThrow();
        List<ZipEntry> primaryEntries = grouped.get(primaryNamespace);
        primaryEntries.addAll(primaryOnlyEntries);
        primaryEntries.sort(Comparator.comparing(ZipEntry::getName));
        return grouped;
    }

    private static void addAssetEntry(
            Map<String, List<ZipEntry>> grouped,
            ZipEntry entry,
            String assetPath
    ) throws IOException {
        if (MCASSETS_ROOT.equals(assetPath)) {
            grouped.computeIfAbsent("minecraft", ignored -> new ArrayList<>()).add(entry);
            return;
        }

        if (!assetPath.startsWith(ASSETS_PREFIX)) {
            throw new IOException("Invalid asset path: " + assetPath);
        }

        String remainder = assetPath.substring(ASSETS_PREFIX.length());
        int separator = remainder.indexOf('/');

        if (separator <= 0 || separator == remainder.length() - 1) {
            throw new IOException("Unsupported resource-pack asset entry: " + assetPath);
        }

        String namespace = remainder.substring(0, separator);
        validateNamespace(namespace);
        grouped.computeIfAbsent(namespace, ignored -> new ArrayList<>()).add(entry);
    }

    private static byte[] fingerprintSharedFiles(
            ZipFile zip,
            ZipEntry packMeta,
            ZipEntry packIcon,
            byte[] ioBuffer
    ) throws IOException {
        MessageDigest digest = Hashes.sha256();
        digest.update(FINGERPRINT_SCHEMA);
        digest.update((byte) 0);

        updateDigest(zip, packMeta, PACK_META, digest, ioBuffer);

        if (packIcon != null && !packIcon.isDirectory()) {
            updateDigest(zip, packIcon, PACK_ICON, digest, ioBuffer);
        }

        return digest.digest();
    }

    private static String fingerprintNamespace(
            ZipFile zip,
            byte[] sharedFingerprint,
            List<ZipEntry> entries,
            byte[] ioBuffer
    ) throws IOException {
        MessageDigest digest = Hashes.sha256();
        digest.update(sharedFingerprint);

        for (ZipEntry entry : entries) {
            updateDigest(zip, entry, validateEntryName(entry.getName()), digest, ioBuffer);
        }

        return Hashes.hex(digest.digest());
    }

    private static void updateDigest(
            ZipFile zip,
            ZipEntry entry,
            String outputName,
            MessageDigest digest,
            byte[] ioBuffer
    ) throws IOException {
        digest.update(outputName.getBytes(StandardCharsets.UTF_8));
        digest.update((byte) 0);

        try (InputStream input = zip.getInputStream(entry)) {
            int read;
            while ((read = input.read(ioBuffer)) != -1) {
                digest.update(ioBuffer, 0, read);
            }
        }

        digest.update((byte) 0);
    }

    private static WrittenZip writeNamespacePack(
            ZipFile source,
            ZipEntry packMeta,
            ZipEntry packIcon,
            List<ZipEntry> assetEntries,
            Path target,
            boolean deterministic,
            int compressionLevel,
            byte[] ioBuffer
    ) throws IOException {
        MessageDigest sha1 = Hashes.sha1();

        try (OutputStream rawOutput = Files.newOutputStream(target);
             DigestOutputStream digestOutput = new DigestOutputStream(rawOutput, sha1);
             ZipOutputStream output = new ZipOutputStream(digestOutput)) {

            output.setLevel(compressionLevel);
            copyEntry(source, packMeta, PACK_META, output, deterministic, ioBuffer);

            if (packIcon != null && !packIcon.isDirectory()) {
                copyEntry(source, packIcon, PACK_ICON, output, deterministic, ioBuffer);
            }

            for (ZipEntry entry : assetEntries) {
                copyEntry(
                        source,
                        entry,
                        validateEntryName(entry.getName()),
                        output,
                        deterministic,
                        ioBuffer
                );
            }
        }

        return new WrittenZip(
                Hashes.hex(sha1.digest()),
                Files.size(target)
        );
    }

    private static void copyEntry(
            ZipFile source,
            ZipEntry sourceEntry,
            String outputName,
            ZipOutputStream output,
            boolean deterministic,
            byte[] ioBuffer
    ) throws IOException {
        ZipEntry outputEntry = new ZipEntry(outputName);
        if (deterministic) {
            outputEntry.setTimeLocal(DETERMINISTIC_TIME);
        } else {
            outputEntry.setTime(Math.max(sourceEntry.getTime(), 0L));
        }

        output.putNextEntry(outputEntry);
        try (InputStream input = source.getInputStream(sourceEntry)) {
            int read;
            while ((read = input.read(ioBuffer)) != -1) {
                output.write(ioBuffer, 0, read);
            }
        }
        output.closeEntry();
    }

    private static String validateEntryName(String name) throws IOException {
        if (name.isEmpty()
                || name.startsWith("/")
                || name.startsWith("\\")
                || name.indexOf('\\') >= 0
                || name.indexOf('\0') >= 0) {
            throw new IOException("Unsafe ZIP entry: " + name);
        }

        String[] parts = name.split("/");
        for (String part : parts) {
            if ("..".equals(part)) {
                throw new IOException("Unsafe ZIP entry: " + name);
            }
        }

        return name;
    }

    private static void validateNamespace(String namespace) throws IOException {
        if (!ResourceNamespaces.isValid(namespace)) {
            throw new IOException("Invalid resource namespace: " + namespace);
        }
    }

    private record WrittenZip(String sha1, long size) {
    }

}
