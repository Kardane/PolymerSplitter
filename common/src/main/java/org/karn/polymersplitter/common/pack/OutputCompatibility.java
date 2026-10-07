package org.karn.polymersplitter.common.pack;

import java.util.List;
import java.util.TreeSet;

public record OutputCompatibility(
        int algorithmVersion,
        boolean copyPackIcon,
        boolean deterministicZip,
        int minSplitPackSizeMb,
        int compressionLevel,
        List<String> includeNamespaces,
        List<String> excludeNamespaces
) {
    public static final int CURRENT_ALGORITHM_VERSION = 6;

    public OutputCompatibility {
        if (algorithmVersion <= 0) {
            throw new IllegalArgumentException("algorithmVersion must be positive");
        }
        if (minSplitPackSizeMb < 0) {
            throw new IllegalArgumentException("minSplitPackSizeMb must be non-negative");
        }
        if (compressionLevel < 0 || compressionLevel > 9) {
            throw new IllegalArgumentException("compressionLevel must be between 0 and 9");
        }

        includeNamespaces = normalizeNamespaces(includeNamespaces, "includeNamespaces");
        excludeNamespaces = normalizeNamespaces(excludeNamespaces, "excludeNamespaces");
    }

    public static OutputCompatibility current(SplitterConfig config) {
        return new OutputCompatibility(
                CURRENT_ALGORITHM_VERSION,
                config.copyPackIcon(),
                config.deterministicZip(),
                config.minSplitPackSizeMb(),
                config.compressionLevel(),
                config.includeNamespaces(),
                config.excludeNamespaces()
        );
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
