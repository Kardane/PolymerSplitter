package org.karn.polymersplitter.common.pack;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
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

public final class SplitCacheIndex {
    public static final String FILE_NAME = "index.json";
    private static final int LEGACY_FORMAT_WITHOUT_OUTPUT_COMPATIBILITY = 2;
    private static final int FORMAT = 3;
    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .disableHtmlEscaping()
            .create();

    private SplitCacheIndex() {
    }

    public static Optional<ReadResult> read(
            Path outputRoot,
            OutputCompatibility expectedCompatibility
    ) throws IOException {
        Objects.requireNonNull(expectedCompatibility, "expectedCompatibility");

        Path root = outputRoot.toAbsolutePath().normalize();
        Path index = root.resolve(FILE_NAME);

        if (!Files.isRegularFile(index)) {
            return Optional.empty();
        }

        final JsonObject object;
        try {
            JsonElement parsed = JsonParser.parseString(
                    Files.readString(index, StandardCharsets.UTF_8)
            );
            if (!parsed.isJsonObject()) {
                throw new IOException("Split cache index root must be a JSON object");
            }
            object = parsed.getAsJsonObject();
        } catch (RuntimeException e) {
            throw new IOException("Failed to parse split cache index", e);
        }

        int format = requireInt(object, "format");
        if (format != LEGACY_FORMAT_WITHOUT_OUTPUT_COMPATIBILITY
                && format != FORMAT) {
            throw new IOException("Unsupported split cache format: " + format);
        }

        String sourceHash = requireString(object, "sourceSha1");
        if (!Hashes.isSha1(sourceHash)) {
            throw new IOException("Invalid split cache source SHA-1: " + sourceHash);
        }

        OutputCompatibility storedCompatibility = format == FORMAT
                ? readOutputCompatibility(object)
                : null;

        JsonElement packsElement = object.get("packs");
        if (packsElement == null || !packsElement.isJsonArray()) {
            throw new IOException("Split cache index is missing packs array");
        }

        JsonArray array = packsElement.getAsJsonArray();
        if (array.isEmpty()) {
            throw new IOException("Split cache index contains no resource packs");
        }

        Set<String> namespaces = new HashSet<>();
        List<SplitPack> packs = new ArrayList<>(array.size());

        for (JsonElement element : array) {
            if (!element.isJsonObject()) {
                throw new IOException("Split cache pack entry must be a JSON object");
            }

            JsonObject packObject = element.getAsJsonObject();
            String namespace = requireString(packObject, "namespace");
            String fingerprint = requireString(packObject, "fingerprint");
            String sha1 = requireString(packObject, "sha1");
            String uuidText = requireString(packObject, "uuid");
            long size = requireLong(packObject, "size");

            if (!ResourceNamespaces.isValid(namespace)) {
                throw new IOException("Invalid cached namespace: " + namespace);
            }
            if (!namespaces.add(namespace)) {
                throw new IOException("Duplicate cached namespace: " + namespace);
            }
            if (!Hashes.isSha256(fingerprint)) {
                throw new IOException("Invalid cached fingerprint for namespace " + namespace);
            }
            if (!Hashes.isSha1(sha1)) {
                throw new IOException("Invalid cached SHA-1 for namespace " + namespace);
            }
            if (size < 0) {
                throw new IOException("Negative cached size for namespace " + namespace);
            }

            final UUID uuid;
            try {
                uuid = UUID.fromString(uuidText);
            } catch (IllegalArgumentException e) {
                throw new IOException("Invalid cached UUID for namespace " + namespace, e);
            }

            if (!PackIdUtil.uuidForNamespace(namespace).equals(uuid)) {
                throw new IOException("Cached UUID does not match namespace " + namespace);
            }

            packs.add(new SplitPack(
                    namespace,
                    HostedPackStore.pathFor(root, sha1),
                    fingerprint,
                    sha1,
                    uuid,
                    size
            ));
        }

        CacheCompatibility compatibility =
                storedCompatibility != null
                        && storedCompatibility.equals(expectedCompatibility)
                        ? CacheCompatibility.COMPATIBLE
                        : CacheCompatibility.INCOMPATIBLE;

        return Optional.of(new ReadResult(
                new Snapshot(
                        sourceHash,
                        List.copyOf(packs),
                        storedCompatibility
                ),
                compatibility
        ));
    }

    public static Snapshot verify(
            Path outputRoot,
            Snapshot snapshot
    ) throws IOException {
        Objects.requireNonNull(snapshot, "snapshot");

        for (SplitPack pack : snapshot.packs()) {
            Path hosted = HostedPackStore.findValidBlob(
                    outputRoot,
                    pack.sha1(),
                    pack.size()
            ).orElseThrow(() -> new IOException(
                    "Cached split blob is missing or corrupted for namespace "
                            + pack.namespace()
            ));

            if (!hosted.equals(pack.path().toAbsolutePath().normalize())) {
                throw new IOException(
                        "Cached split blob path is not canonical for namespace "
                                + pack.namespace()
                );
            }
        }

        return snapshot;
    }

    public static void write(
            Path outputRoot,
            String sourceHash,
            List<SplitPack> packs,
            OutputCompatibility outputCompatibility
    ) throws IOException {
        Path root = outputRoot.toAbsolutePath().normalize();
        Files.createDirectories(root);
        Objects.requireNonNull(outputCompatibility, "outputCompatibility");

        if (!Hashes.isSha1(sourceHash)) {
            throw new IOException("Invalid split cache source SHA-1: " + sourceHash);
        }
        if (packs.isEmpty()) {
            throw new IOException("Cannot write an empty split cache index");
        }

        JsonObject rootObject = new JsonObject();
        rootObject.addProperty("format", FORMAT);
        rootObject.addProperty("sourceSha1", sourceHash);

        JsonObject outputObject = new JsonObject();
        outputObject.addProperty(
                "algorithmVersion",
                outputCompatibility.algorithmVersion()
        );
        outputObject.addProperty(
                "copyPackIcon",
                outputCompatibility.copyPackIcon()
        );
        outputObject.addProperty(
                "deterministicZip",
                outputCompatibility.deterministicZip()
        );
        rootObject.add("output", outputObject);
        outputObject.addProperty("minSplitPackSizeMb", outputCompatibility.minSplitPackSizeMb());

        JsonArray array = new JsonArray();
        Set<String> namespaces = new HashSet<>();

        for (SplitPack pack : packs.stream()
                .sorted(Comparator.comparing(SplitPack::namespace))
                .toList()) {
            if (!namespaces.add(pack.namespace())) {
                throw new IOException("Duplicate split namespace: " + pack.namespace());
            }

            Path expectedPath = HostedPackStore.pathFor(root, pack.sha1());
            if (!pack.path().toAbsolutePath().normalize().equals(expectedPath)) {
                throw new IOException(
                        "Split pack is outside the content-addressed store: " + pack.path()
                );
            }

            JsonObject packObject = new JsonObject();
            packObject.addProperty("namespace", pack.namespace());
            packObject.addProperty("fingerprint", pack.fingerprint());
            packObject.addProperty("sha1", pack.sha1());
            packObject.addProperty("uuid", pack.uuid().toString());
            packObject.addProperty("size", pack.size());
            array.add(packObject);
        }

        rootObject.add("packs", array);

        Path target = root.resolve(FILE_NAME);
        Path temp = AtomicFiles.createReadableJsonTemp(root, ".index-");

        try {
            Files.writeString(
                    temp,
                    GSON.toJson(rootObject) + System.lineSeparator(),
                    StandardCharsets.UTF_8
            );
            AtomicFiles.replace(temp, target);
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    public static boolean exists(Path outputRoot) {
        return Files.isRegularFile(
                outputRoot.toAbsolutePath().normalize().resolve(FILE_NAME)
        );
    }

    private static OutputCompatibility readOutputCompatibility(
            JsonObject object
    ) throws IOException {
        JsonElement outputElement = object.get("output");
        if (outputElement == null || !outputElement.isJsonObject()) {
            throw new IOException("Split cache index is missing output compatibility metadata");
        }

        JsonObject output = outputElement.getAsJsonObject();
        int algorithmVersion = requireInt(output, "algorithmVersion");
        boolean copyPackIcon = requireBoolean(output, "copyPackIcon");
        boolean deterministicZip = requireBoolean(output, "deterministicZip");
        int minSplitPackSizeMb = algorithmVersion < 3
                ? 0 : requireInt(output, "minSplitPackSizeMb");

        try {
            return new OutputCompatibility(
                    algorithmVersion,
                    copyPackIcon,
                    deterministicZip,
                    minSplitPackSizeMb
            );
        } catch (IllegalArgumentException e) {
            throw new IOException("Invalid output compatibility metadata", e);
        }
    }

    private static String requireString(JsonObject object, String name) throws IOException {
        JsonElement element = object.get(name);
        if (element == null || !element.isJsonPrimitive()
                || !element.getAsJsonPrimitive().isString()) {
            throw new IOException("Split cache index field '" + name + "' must be a string");
        }
        return element.getAsString();
    }

    private static boolean requireBoolean(JsonObject object, String name) throws IOException {
        JsonElement element = object.get(name);
        if (element == null || !element.isJsonPrimitive()
                || !element.getAsJsonPrimitive().isBoolean()) {
            throw new IOException("Split cache index field '" + name + "' must be a boolean");
        }
        return element.getAsBoolean();
    }

    private static int requireInt(JsonObject object, String name) throws IOException {
        long value = requireLong(object, name);
        if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
            throw new IOException("Split cache index field '" + name + "' is out of range");
        }
        return (int) value;
    }

    private static long requireLong(JsonObject object, String name) throws IOException {
        JsonElement element = object.get(name);
        if (element == null || !element.isJsonPrimitive()
                || !element.getAsJsonPrimitive().isNumber()) {
            throw new IOException("Split cache index field '" + name + "' must be a number");
        }

        try {
            return element.getAsLong();
        } catch (RuntimeException e) {
            throw new IOException("Invalid numeric split cache field '" + name + "'", e);
        }
    }

    public enum CacheCompatibility {
        COMPATIBLE,
        INCOMPATIBLE
    }

    public record ReadResult(
            Snapshot snapshot,
            CacheCompatibility compatibility
    ) {
        public ReadResult {
            Objects.requireNonNull(snapshot, "snapshot");
            Objects.requireNonNull(compatibility, "compatibility");
        }

        public boolean isCompatible() {
            return compatibility == CacheCompatibility.COMPATIBLE;
        }
    }

    public record Snapshot(
            String sourceHash,
            List<SplitPack> packs,
            OutputCompatibility outputCompatibility
    ) {
        public Snapshot {
            Objects.requireNonNull(sourceHash, "sourceHash");
            packs = List.copyOf(packs);
        }
    }
}
