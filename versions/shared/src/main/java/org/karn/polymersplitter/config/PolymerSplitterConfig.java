package org.karn.polymersplitter.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.karn.polymersplitter.common.pack.SplitterConfig;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
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

    public static PolymerSplitterConfig load(Path path) throws IOException {
        Objects.requireNonNull(path, "path");

        if (!Files.exists(path)) {
            PolymerSplitterConfig defaults = new PolymerSplitterConfig();
            defaults.validate();
            defaults.save(path);
            return defaults;
        }

        PolymerSplitterConfig config;
        try (var reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            config = GSON.fromJson(reader, PolymerSplitterConfig.class);
        }

        if (config == null) {
            throw new IOException("PolymerSplitter config is empty: " + path);
        }

        config.validate();
        return config;
    }

    public void save(Path path) throws IOException {
        Path parent = path.toAbsolutePath().normalize().getParent();
        if (parent == null) {
            throw new IOException("Config path has no parent: " + path);
        }

        Files.createDirectories(parent);
        Path temp = Files.createTempFile(parent, ".polymersplitter-", ".json.tmp");

        try {
            Files.writeString(temp, GSON.toJson(this) + System.lineSeparator(), StandardCharsets.UTF_8);
            atomicReplace(temp, path);
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    public SplitterConfig toSplitterConfig() {
        return new SplitterConfig(
                enabled,
                copyPackIcon,
                deterministicZip,
                logPackSizes
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
        if (!"namespace".equals(splitMode)) {
            throw new IllegalArgumentException(
                    "Unsupported splitMode '" + splitMode + "'. Only 'namespace' is currently supported."
            );
        }
    }

    private static void atomicReplace(Path source, Path target) throws IOException {
        try {
            Files.move(
                    source,
                    target,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING
            );
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
