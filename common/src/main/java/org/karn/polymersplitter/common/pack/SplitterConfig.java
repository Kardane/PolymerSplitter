package org.karn.polymersplitter.common.pack;

import java.util.List;
import java.util.TreeSet;

public record SplitterConfig(
        boolean copyPackIcon,
        boolean deterministicZip,
        boolean logPackSizes,
        int minSplitPackSizeMb,
        int compressionLevel,
        List<String> includeNamespaces,
        List<String> excludeNamespaces
) {
    public SplitterConfig {
        if (minSplitPackSizeMb < 0) {
            throw new IllegalArgumentException("minSplitPackSizeMb must be non-negative");
        }
        if (compressionLevel < 0 || compressionLevel > 9) {
            throw new IllegalArgumentException("compressionLevel must be between 0 and 9");
        }

        includeNamespaces = normalizeNamespaces(includeNamespaces, "includeNamespaces");
        excludeNamespaces = normalizeNamespaces(excludeNamespaces, "excludeNamespaces");
    }

    public long minSplitPackSizeBytes() {
        return minSplitPackSizeMb * 1024L * 1024L;
    }

    public boolean shouldSplitIndependently(String namespace) {
        return !excludeNamespaces.contains(namespace)
                && (includeNamespaces.isEmpty() || includeNamespaces.contains(namespace));
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
