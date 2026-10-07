package org.karn.polymersplitter.common.pack;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

record PackMetadata(List<String> overlayDirectories) {
    private static final String ASSETS_DIRECTORY = "assets";
    private static final String PACK_META = "pack.mcmeta";
    private static final String PACK_ICON = "pack.png";

    PackMetadata {
        overlayDirectories = List.copyOf(overlayDirectories);
    }

    static PackMetadata read(ZipFile zip, ZipEntry packMeta) throws IOException {
        final JsonElement rootElement;

        try (var reader = new InputStreamReader(
                zip.getInputStream(packMeta),
                StandardCharsets.UTF_8
        )) {
            rootElement = JsonParser.parseReader(reader);
        } catch (JsonParseException | IllegalStateException e) {
            throw new IOException("Invalid pack.mcmeta JSON", e);
        }

        if (rootElement == null || !rootElement.isJsonObject()) {
            throw new IOException("pack.mcmeta root must be a JSON object");
        }

        JsonObject root = rootElement.getAsJsonObject();
        JsonElement overlaysElement = root.get("overlays");

        if (overlaysElement == null || overlaysElement.isJsonNull()) {
            return new PackMetadata(List.of());
        }

        if (!overlaysElement.isJsonObject()) {
            throw new IOException("pack.mcmeta overlays must be a JSON object");
        }

        JsonElement entriesElement = overlaysElement.getAsJsonObject().get("entries");
        if (entriesElement == null || !entriesElement.isJsonArray()) {
            throw new IOException("pack.mcmeta overlays.entries must be a JSON array");
        }

        Set<String> directories = new LinkedHashSet<>();

        for (JsonElement entryElement : entriesElement.getAsJsonArray()) {
            if (!entryElement.isJsonObject()) {
                throw new IOException("pack.mcmeta overlay entry must be a JSON object");
            }

            JsonElement directoryElement = entryElement.getAsJsonObject().get("directory");
            if (directoryElement == null
                    || !directoryElement.isJsonPrimitive()
                    || !directoryElement.getAsJsonPrimitive().isString()) {
                throw new IOException("pack.mcmeta overlay entry is missing a string directory");
            }

            String directory = directoryElement.getAsString();
            validateDirectory(directory);
            directories.add(directory);
        }

        return new PackMetadata(List.copyOf(directories));
    }

    private static void validateDirectory(String directory) throws IOException {
        if (directory.isBlank()
                || ".".equals(directory)
                || "..".equals(directory)
                || directory.indexOf('/') >= 0
                || directory.indexOf('\\') >= 0
                || directory.indexOf('\0') >= 0) {
            throw new IOException("Unsafe overlay directory in pack.mcmeta: " + directory);
        }

        if (ASSETS_DIRECTORY.equals(directory)
                || PACK_META.equals(directory)
                || PACK_ICON.equals(directory)) {
            throw new IOException("Overlay directory conflicts with pack root: " + directory);
        }
    }
}
