package org.karn.polymersplitter.common.lifecycle;

import org.karn.polymersplitter.common.pack.SplitPack;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

public record SplitGeneration(
        String sourceHash,
        List<SplitPack> packs,
        Map<String, SplitPack> byNamespace
) {
    private static final Pattern SHA1_PATTERN = Pattern.compile("[0-9a-f]{40}");

    public SplitGeneration {
        Objects.requireNonNull(sourceHash, "sourceHash");
        Objects.requireNonNull(packs, "packs");
        Objects.requireNonNull(byNamespace, "byNamespace");

        if (!SHA1_PATTERN.matcher(sourceHash).matches()) {
            throw new IllegalArgumentException("Invalid generation source SHA-1: " + sourceHash);
        }
        if (packs.isEmpty()) {
            throw new IllegalArgumentException("Split generation must contain at least one pack");
        }

        packs = List.copyOf(packs);
        byNamespace = Map.copyOf(byNamespace);
    }

    public static SplitGeneration create(String sourceHash, List<SplitPack> packs) {
        Objects.requireNonNull(packs, "packs");

        List<SplitPack> ordered = new ArrayList<>(packs);
        ordered.sort(
                Comparator.comparingInt((SplitPack pack) ->
                                "minecraft".equals(pack.namespace()) ? 0 : 1)
                        .thenComparing(SplitPack::namespace)
        );

        Map<String, SplitPack> byNamespace = new LinkedHashMap<>();
        for (SplitPack pack : ordered) {
            SplitPack previous = byNamespace.putIfAbsent(pack.namespace(), pack);
            if (previous != null) {
                throw new IllegalArgumentException(
                        "Duplicate namespace in split generation: " + pack.namespace()
                );
            }
        }

        return new SplitGeneration(
                sourceHash,
                List.copyOf(ordered),
                byNamespace
        );
    }
}
