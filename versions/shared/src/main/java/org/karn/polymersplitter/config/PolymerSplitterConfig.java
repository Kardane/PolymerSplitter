package org.karn.polymersplitter.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import org.karn.polymersplitter.common.io.AtomicFiles;
import org.karn.polymersplitter.common.pack.SplitterConfig;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

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
        if (!stored.has("minSplitPackSizeMb")) {
            stored.addProperty("minSplitPackSizeMb", config.minSplitPackSizeMb);
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
                minSplitPackSizeMb
        );
    }

    public boolean enabled() {
        return enabled;
    }

    public String splitMode() {
        return splitMode;
    }

    public boolean logPackSizes() {
        return logPackSizes;
    }

    private void validate() {
        if (minSplitPackSizeMb < 0) {
            throw new IllegalArgumentException("minSplitPackSizeMb must be non-negative");
        }
        if (!"namespace".equals(splitMode)) {
            throw new IllegalArgumentException(
                    "Unsupported splitMode '" + splitMode + "'. Only 'namespace' is currently supported."
            );
        }
    }

}
