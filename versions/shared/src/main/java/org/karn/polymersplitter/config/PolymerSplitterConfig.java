package org.karn.polymersplitter.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import org.karn.polymersplitter.common.io.AtomicFiles;
import org.karn.polymersplitter.common.pack.ResourceNamespaces;
import org.karn.polymersplitter.common.pack.SplitterConfig;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.TreeSet;

public final class PolymerSplitterConfig {
    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .disableHtmlEscaping()
            .create();

    private boolean enabled = true;
    private String splitMode = "namespace";
    private boolean copyPackIcon = true;
    private boolean deterministicZip = true;
    private boolean logPackSizes = true;
    private int minSplitPackSizeMb = 30;
    private int compressionLevel = 6;
    private List<String> includeNamespaces = List.of();
    private List<String> excludeNamespaces = List.of();
    private int unreferencedBlobRetentionDays = 0;

    public static PolymerSplitterConfig load(Path path) throws IOException {
        Objects.requireNonNull(path, "path");

        if (!Files.exists(path)) {
            PolymerSplitterConfig defaults = new PolymerSplitterConfig();
            defaults.validate();
            defaults.save(path);
            return defaults;
        }

        PolymerSplitterConfig config;
        JsonObject stored;
        try (var reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            stored = GSON.fromJson(reader, JsonObject.class);
            config = GSON.fromJson(stored, PolymerSplitterConfig.class);
        }

        if (config == null) {
            throw new IOException("PolymerSplitter config is empty: " + path);
        }

        config.validate();

        boolean migrated = false;
        if (!stored.has("minSplitPackSizeMb")) {
            stored.addProperty("minSplitPackSizeMb", config.minSplitPackSizeMb);
            migrated = true;
        }
        if (!stored.has("compressionLevel")) {
            stored.addProperty("compressionLevel", config.compressionLevel);
            migrated = true;
        }
        if (!stored.has("includeNamespaces")) {
            stored.add("includeNamespaces", GSON.toJsonTree(config.includeNamespaces));
            migrated = true;
        }
        if (!stored.has("excludeNamespaces")) {
            stored.add("excludeNamespaces", GSON.toJsonTree(config.excludeNamespaces));
            migrated = true;
        }
        if (!stored.has("unreferencedBlobRetentionDays")) {
            stored.addProperty(
                    "unreferencedBlobRetentionDays",
                    config.unreferencedBlobRetentionDays
            );
            migrated = true;
        }
        if (migrated) {
            saveJson(path, GSON.toJson(stored));
        }

        return config;
    }

    public void save(Path path) throws IOException {
        saveJson(path, GSON.toJson(this));
    }

    private static void saveJson(Path path, String json) throws IOException {
        Path parent = path.toAbsolutePath().normalize().getParent();
        if (parent == null) {
            throw new IOException("Config path has no parent: " + path);
        }

        Files.createDirectories(parent);
        Path temp = AtomicFiles.createReadableJsonTemp(parent, ".polymersplitter-");

        try {
            Files.writeString(temp, json + System.lineSeparator(), StandardCharsets.UTF_8);
            AtomicFiles.replace(temp, path);
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    public SplitterConfig toSplitterConfig() {
        return new SplitterConfig(
                copyPackIcon,
                deterministicZip,
                logPackSizes,
                minSplitPackSizeMb,
                compressionLevel,
                includeNamespaces,
                excludeNamespaces
        );
    }

    public boolean enabled() {
        return enabled;
    }

    public String splitMode() {
        return splitMode;
    }

    public boolean copyPackIcon() {
        return copyPackIcon;
    }

    public boolean deterministicZip() {
        return deterministicZip;
    }

    public boolean logPackSizes() {
        return logPackSizes;
    }

    public int minSplitPackSizeMb() {
        return minSplitPackSizeMb;
    }

    public int compressionLevel() {
        return compressionLevel;
    }

    public List<String> includeNamespaces() {
        return includeNamespaces;
    }

    public List<String> excludeNamespaces() {
        return excludeNamespaces;
    }

    public int unreferencedBlobRetentionDays() {
        return unreferencedBlobRetentionDays;
    }

    private void validate() {
        if (minSplitPackSizeMb < 0) {
            throw new IllegalArgumentException("minSplitPackSizeMb must be non-negative");
        }
        if (compressionLevel < 0 || compressionLevel > 9) {
            throw new IllegalArgumentException("compressionLevel must be between 0 and 9");
        }
        if (unreferencedBlobRetentionDays < -1) {
            throw new IllegalArgumentException(
                    "unreferencedBlobRetentionDays must be -1 or non-negative"
            );
        }

        includeNamespaces = normalizeNamespaces(includeNamespaces, "includeNamespaces");
        excludeNamespaces = normalizeNamespaces(excludeNamespaces, "excludeNamespaces");

        if (!"namespace".equals(splitMode)) {
            throw new IllegalArgumentException(
                    "Unsupported splitMode '" + splitMode + "'. Only 'namespace' is currently supported."
            );
        }
    }
    private static List<String> normalizeNamespaces(
            List<String> namespaces,
            String field
    ) {
        if (namespaces == null) {
            throw new IllegalArgumentException(field + " must not be null");
        }

        TreeSet<String> normalized = new TreeSet<>();
        for (String namespace : namespaces) {
            if (!ResourceNamespaces.isValid(namespace)) {
                throw new IllegalArgumentException(
                        "Invalid resource namespace in " + field + ": " + namespace
                );
            }
            normalized.add(namespace);
        }

        return List.copyOf(normalized);
    }


}
