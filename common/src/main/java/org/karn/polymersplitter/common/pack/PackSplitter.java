package org.karn.polymersplitter.common.pack;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

public final class PackSplitter {
    private static final String ASSETS_PREFIX = "assets/";
    private static final String PACK_META = "pack.mcmeta";
    private static final String PACK_ICON = "pack.png";
    private static final String MCASSETS_ROOT = "assets/.mcassetsroot";
    private static final Pattern NAMESPACE_PATTERN = Pattern.compile("[a-z0-9_.-]+");
    private static final LocalDateTime DETERMINISTIC_TIME = LocalDateTime.of(1980, 1, 1, 0, 0);

    public List<SplitPack> split(
            Path sourcePack,
            Path outputDirectory,
            SplitterConfig config
    ) throws IOException {
        return split(sourcePack, outputDirectory, config, List.of());
    }

    public List<SplitPack> split(
            Path sourcePack,
            Path outputDirectory,
            SplitterConfig config,
            List<SplitPack> reusablePacks
    ) throws IOException {
        Objects.requireNonNull(sourcePack, "sourcePack");
        Objects.requireNonNull(outputDirectory, "outputDirectory");
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(reusablePacks, "reusablePacks");

        if (!Files.isRegularFile(sourcePack)) {
            throw new IOException("Source resource pack does not exist or is not a file: " + sourcePack);
        }

        Files.createDirectories(outputDirectory);

        Map<String, SplitPack> reusableByNamespace = new HashMap<>();
        for (SplitPack pack : reusablePacks) {
            reusableByNamespace.put(pack.namespace(), pack);
        }

        try (ZipFile zip = new ZipFile(sourcePack.toFile())) {
            ZipEntry packMeta = zip.getEntry(PACK_META);
            if (packMeta == null || packMeta.isDirectory()) {
                throw new IOException("Source resource pack is missing " + PACK_META);
            }

            ZipEntry packIcon = config.copyPackIcon() ? zip.getEntry(PACK_ICON) : null;
            Map<String, List<ZipEntry>> byNamespace = collectNamespaceEntries(zip);
            byte[] sharedFingerprint = fingerprintSharedFiles(zip, packMeta, packIcon);

            List<SplitPack> packs = new ArrayList<>(byNamespace.size());
            for (Map.Entry<String, List<ZipEntry>> namespaceEntry : byNamespace.entrySet()) {
                String namespace = namespaceEntry.getKey();
                Path target = outputDirectory.resolve(namespace + ".zip");
                String fingerprint = fingerprintNamespace(
                        zip,
                        sharedFingerprint,
                        namespaceEntry.getValue()
                );

                SplitPack reusable = reusableByNamespace.get(namespace);
                if (canReuse(reusable, fingerprint)) {
                    materializeReuse(reusable.path(), target);

                    packs.add(new SplitPack(
                            namespace,
                            target,
                            fingerprint,
                            reusable.sha1(),
                            PackIdUtil.uuidForNamespace(namespace),
                            reusable.size()
                    ));
                    continue;
                }

                writeNamespacePack(
                        zip,
                        packMeta,
                        packIcon,
                        namespaceEntry.getValue(),
                        target,
                        config.deterministicZip()
                );

                packs.add(new SplitPack(
                        namespace,
                        target,
                        fingerprint,
                        PackHashUtil.sha1(target),
                        PackIdUtil.uuidForNamespace(namespace),
                        Files.size(target)
                ));
            }

            List<SplitPack> result = List.copyOf(packs);
            SplitPackManifest.write(outputDirectory, result);
            return result;
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

    private static void materializeReuse(Path source, Path target) throws IOException {
        Path normalizedSource = source.toAbsolutePath().normalize();
        Path normalizedTarget = target.toAbsolutePath().normalize();

        if (normalizedSource.equals(normalizedTarget)) {
            return;
        }

        Files.createDirectories(normalizedTarget.getParent());

        Path temp = Files.createTempFile(
                normalizedTarget.getParent(),
                "." + normalizedTarget.getFileName(),
                ".reuse"
        );

        try {
            Files.deleteIfExists(temp);

            try {
                Files.createLink(temp, normalizedSource);
            } catch (IOException | UnsupportedOperationException | SecurityException ignored) {
                Files.copy(normalizedSource, temp, StandardCopyOption.REPLACE_EXISTING);
            }

            atomicReplace(temp, normalizedTarget);
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private static Map<String, List<ZipEntry>> collectNamespaceEntries(ZipFile zip) throws IOException {
        Map<String, List<ZipEntry>> grouped = new LinkedHashMap<>();

        List<? extends ZipEntry> entries = zip.stream()
                .filter(entry -> !entry.isDirectory())
                .sorted(Comparator.comparing(ZipEntry::getName))
                .toList();

        for (ZipEntry entry : entries) {
            String name = validateEntryName(entry.getName());

            if (MCASSETS_ROOT.equals(name)) {
                grouped.computeIfAbsent("minecraft", ignored -> new ArrayList<>()).add(entry);
                continue;
            }

            if (!name.startsWith(ASSETS_PREFIX)) {
                continue;
            }

            String remainder = name.substring(ASSETS_PREFIX.length());
            int separator = remainder.indexOf('/');
            if (separator <= 0 || separator == remainder.length() - 1) {
                continue;
            }

            String namespace = remainder.substring(0, separator);
            validateNamespace(namespace);
            grouped.computeIfAbsent(namespace, ignored -> new ArrayList<>()).add(entry);
        }

        return grouped;
    }

    private static byte[] fingerprintSharedFiles(
            ZipFile zip,
            ZipEntry packMeta,
            ZipEntry packIcon
    ) throws IOException {
        MessageDigest digest = sha256Digest();
        updateDigest(zip, packMeta, PACK_META, digest);

        if (packIcon != null && !packIcon.isDirectory()) {
            updateDigest(zip, packIcon, PACK_ICON, digest);
        }

        return digest.digest();
    }

    private static String fingerprintNamespace(
            ZipFile zip,
            byte[] sharedFingerprint,
            List<ZipEntry> entries
    ) throws IOException {
        MessageDigest digest = sha256Digest();
        digest.update(sharedFingerprint);

        for (ZipEntry entry : entries) {
            updateDigest(zip, entry, validateEntryName(entry.getName()), digest);
        }

        return toHex(digest.digest());
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

    private static MessageDigest sha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    private static String toHex(byte[] bytes) {
        char[] out = new char[bytes.length * 2];
        char[] hex = "0123456789abcdef".toCharArray();

        for (int i = 0; i < bytes.length; i++) {
            int value = bytes[i] & 0xff;
            out[i * 2] = hex[value >>> 4];
            out[i * 2 + 1] = hex[value & 0x0f];
        }

        return new String(out);
    }

    private static void writeNamespacePack(
            ZipFile source,
            ZipEntry packMeta,
            ZipEntry packIcon,
            List<ZipEntry> assetEntries,
            Path target,
            boolean deterministic
    ) throws IOException {
        Path directory = target.getParent();
        Files.createDirectories(directory);

        Path temp = Files.createTempFile(directory, "." + target.getFileName(), ".tmp");

        try {
            try (OutputStream rawOutput = Files.newOutputStream(temp);
                 ZipOutputStream output = new ZipOutputStream(rawOutput)) {

                copyEntry(source, packMeta, PACK_META, output, deterministic);

                if (packIcon != null && !packIcon.isDirectory()) {
                    copyEntry(source, packIcon, PACK_ICON, output, deterministic);
                }

                for (ZipEntry entry : assetEntries) {
                    copyEntry(source, entry, validateEntryName(entry.getName()), output, deterministic);
                }
            }

            atomicReplace(temp, target);
        } finally {
            Files.deleteIfExists(temp);
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
        if (!NAMESPACE_PATTERN.matcher(namespace).matches()) {
            throw new IOException("Invalid resource namespace: " + namespace);
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
}
