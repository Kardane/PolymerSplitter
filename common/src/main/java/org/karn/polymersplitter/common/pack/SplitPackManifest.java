package org.karn.polymersplitter.common.pack;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.List;

public final class SplitPackManifest {
    public static final String FILE_NAME = "manifest.json";

    private SplitPackManifest() {
    }

    public static void write(Path outputDirectory, List<SplitPack> packs) throws IOException {
        Files.createDirectories(outputDirectory);

        Path target = outputDirectory.resolve(FILE_NAME);
        Path temp = Files.createTempFile(outputDirectory, ".manifest-", ".json.tmp");

        try {
            Files.writeString(temp, toJson(packs), StandardCharsets.UTF_8);
            atomicReplace(temp, target);
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private static String toJson(List<SplitPack> packs) {
        List<SplitPack> sorted = packs.stream()
                .sorted(Comparator.comparing(SplitPack::namespace))
                .toList();

        StringBuilder json = new StringBuilder(256 + sorted.size() * 240);
        json.append("{\n");
        json.append("  \"formatVersion\": 2,\n");
        json.append("  \"packs\": [");

        if (!sorted.isEmpty()) {
            json.append('\n');
        }

        for (int i = 0; i < sorted.size(); i++) {
            SplitPack pack = sorted.get(i);
            json.append("    {\n");
            json.append("      \"namespace\": \"").append(escape(pack.namespace())).append("\",\n");
            json.append("      \"file\": \"").append(escape(pack.path().getFileName().toString())).append("\",\n");
            json.append("      \"fingerprint\": \"").append(pack.fingerprint()).append("\",\n");
            json.append("      \"sha1\": \"").append(pack.sha1()).append("\",\n");
            json.append("      \"uuid\": \"").append(pack.uuid()).append("\",\n");
            json.append("      \"size\": ").append(pack.size()).append('\n');
            json.append("    }");

            if (i + 1 < sorted.size()) {
                json.append(',');
            }
            json.append('\n');
        }

        json.append("  ]\n");
        json.append("}\n");
        return json.toString();
    }

    private static String escape(String value) {
        StringBuilder out = new StringBuilder(value.length() + 8);
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            switch (ch) {
                case '\\' -> out.append("\\\\");
                case '\"' -> out.append("\\\"");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (ch < 0x20) {
                        out.append(String.format("\\u%04x", (int) ch));
                    } else {
                        out.append(ch);
                    }
                }
            }
        }
        return out.toString();
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
