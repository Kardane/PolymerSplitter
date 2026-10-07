package org.karn.polymersplitter.common.pack;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
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
    private static final String ASSETS_PREFIX = "assets/";
    private static final String PACK_META = "pack.mcmeta";
    private static final String PACK_ICON = "pack.png";
    private static final String MCASSETS_ROOT = "assets/.mcassetsroot";
    private static final LocalDateTime DETERMINISTIC_TIME = LocalDateTime.of(1980, 1, 1, 0, 0);
    private static final byte[] FINGERPRINT_SCHEMA =
            "polymersplitter:namespace-fingerprint:v2".getBytes(StandardCharsets.UTF_8);

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
            PackLayout layout = collectPackLayout(zip, metadata);
            byte[] sharedFingerprint = fingerprintSharedFiles(
                    zip,
                    packMeta,
                    packIcon,
                    layout.sharedRootEntries()
            );

            List<SplitPack> packs = new ArrayList<>(layout.byNamespace().size());
            for (Map.Entry<String, List<ZipEntry>> namespaceEntry : layout.byNamespace().entrySet()) {
                String namespace = namespaceEntry.getKey();
                String fingerprint = fingerprintNamespace(
                        zip,
                        sharedFingerprint,
                        namespaceEntry.getValue()
                );

                SplitPack reusable = reusableByNamespace.get(namespace);
                if (canReuse(reusable, fingerprint)) {
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
                    writeNamespacePack(
                            zip,
                            packMeta,
                            packIcon,
                            layout.sharedRootEntries(),
                            namespaceEntry.getValue(),
                            temp,
                            config.deterministicZip()
                    );

                    String sha1 = Hashes.sha1(temp);
                    long size = Files.size(temp);
                    Path hosted = HostedPackStore.commitGeneratedBlob(
                            outputRoot,
                            temp,
                            sha1,
                            size
                    );

                    packs.add(new SplitPack(
                            namespace,
                            hosted,
                            fingerprint,
                            sha1,
                            PackIdUtil.uuidForNamespace(namespace),
                            size
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

    private static PackLayout collectPackLayout(
            ZipFile zip,
            PackMetadata metadata
    ) throws IOException {
        Map<String, List<ZipEntry>> grouped = new LinkedHashMap<>();
        List<ZipEntry> sharedRootEntries = new ArrayList<>();
        Set<String> overlayDirectories = new LinkedHashSet<>(metadata.overlayDirectories());

        List<? extends ZipEntry> entries = zip.stream()
                .filter(entry -> !entry.isDirectory())
                .sorted(Comparator.comparing(ZipEntry::getName))
                .toList();

        for (ZipEntry entry : entries) {
            String name = validateEntryName(entry.getName());

            if (PACK_META.equals(name) || PACK_ICON.equals(name)) {
                continue;
            }

            if (name.startsWith(ASSETS_PREFIX)) {
                addAssetEntry(grouped, entry, name);
                continue;
            }

            int separator = name.indexOf('/');
            if (separator < 0) {
                sharedRootEntries.add(entry);
                continue;
            }

            String rootDirectory = name.substring(0, separator);
            if (!overlayDirectories.contains(rootDirectory)) {
                throw new IOException(
                        "Unsupported resource-pack root directory entry: " + name
                );
            }

            String overlayRelativePath = name.substring(separator + 1);

            if (PACK_META.equals(overlayRelativePath) || PACK_ICON.equals(overlayRelativePath)) {
                continue;
            }

            if (!overlayRelativePath.startsWith(ASSETS_PREFIX)) {
                throw new IOException(
                        "Unsupported entry inside overlay '" + rootDirectory + "': " + name
                );
            }

            addAssetEntry(grouped, entry, overlayRelativePath);
        }

        return new PackLayout(
                grouped,
                List.copyOf(sharedRootEntries)
        );
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
            List<ZipEntry> sharedRootEntries
    ) throws IOException {
        MessageDigest digest = Hashes.sha256();
        digest.update(FINGERPRINT_SCHEMA);
        digest.update((byte) 0);

        updateDigest(zip, packMeta, PACK_META, digest);

        if (packIcon != null && !packIcon.isDirectory()) {
            updateDigest(zip, packIcon, PACK_ICON, digest);
        }

        for (ZipEntry entry : sharedRootEntries) {
            updateDigest(zip, entry, validateEntryName(entry.getName()), digest);
        }

        return digest.digest();
    }

    private static String fingerprintNamespace(
            ZipFile zip,
            byte[] sharedFingerprint,
            List<ZipEntry> entries
    ) throws IOException {
        MessageDigest digest = Hashes.sha256();
        digest.update(sharedFingerprint);

        for (ZipEntry entry : entries) {
            updateDigest(zip, entry, validateEntryName(entry.getName()), digest);
        }

        return Hashes.hex(digest.digest());
    }

    private static void updateDigest(
            ZipFile zip,
            ZipEntry entry,
            String outputName,
            MessageDigest digest
    ) throws IOException {
        digest.update(outputName.getBytes(StandardCharsets.UTF_8));
        digest.update((byte) 0);

        byte[] buffer = new byte[64 * 1024];
        try (InputStream input = zip.getInputStream(entry)) {
            int read;
            while ((read = input.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
        }

        digest.update((byte) 0);
    }

    private static void writeNamespacePack(
            ZipFile source,
            ZipEntry packMeta,
            ZipEntry packIcon,
            List<ZipEntry> sharedRootEntries,
            List<ZipEntry> assetEntries,
            Path target,
            boolean deterministic
    ) throws IOException {
        try (OutputStream rawOutput = Files.newOutputStream(target);
             ZipOutputStream output = new ZipOutputStream(rawOutput)) {

            copyEntry(source, packMeta, PACK_META, output, deterministic);

            if (packIcon != null && !packIcon.isDirectory()) {
                copyEntry(source, packIcon, PACK_ICON, output, deterministic);
            }

            for (ZipEntry entry : sharedRootEntries) {
                copyEntry(
                        source,
                        entry,
                        validateEntryName(entry.getName()),
                        output,
                        deterministic
                );
            }

            for (ZipEntry entry : assetEntries) {
                copyEntry(
                        source,
                        entry,
                        validateEntryName(entry.getName()),
                        output,
                        deterministic
                );
            }
        }
    }

    private static void copyEntry(
            ZipFile source,
            ZipEntry sourceEntry,
            String outputName,
            ZipOutputStream output,
            boolean deterministic
    ) throws IOException {
        ZipEntry outputEntry = new ZipEntry(outputName);
        if (deterministic) {
            outputEntry.setTimeLocal(DETERMINISTIC_TIME);
        } else {
            outputEntry.setTime(Math.max(sourceEntry.getTime(), 0L));
        }

        output.putNextEntry(outputEntry);
        try (InputStream input = source.getInputStream(sourceEntry)) {
            input.transferTo(output);
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

    private record PackLayout(
            Map<String, List<ZipEntry>> byNamespace,
            List<ZipEntry> sharedRootEntries
    ) {
    }
}
