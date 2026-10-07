package org.karn.polymersplitter.common.lifecycle;

import org.karn.polymersplitter.common.pack.SplitPack;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

public record NamespaceTransition(
        List<String> added,
        List<String> removed,
        List<String> changed,
        List<String> unchanged
) {
    private static final NamespaceTransition EMPTY =
            new NamespaceTransition(List.of(), List.of(), List.of(), List.of());

    public NamespaceTransition {
        added = List.copyOf(added);
        removed = List.copyOf(removed);
        changed = List.copyOf(changed);
        unchanged = List.copyOf(unchanged);
    }

    public static NamespaceTransition empty() {
        return EMPTY;
    }

    public static NamespaceTransition between(
            SplitGeneration previous,
            SplitGeneration next
    ) {
        Map<String, SplitPack> before = previous == null
                ? Map.of()
                : previous.byNamespace();
        Map<String, SplitPack> after = next == null
                ? Map.of()
                : next.byNamespace();

        List<String> added = new ArrayList<>();
        List<String> removed = new ArrayList<>();
        List<String> changed = new ArrayList<>();
        List<String> unchanged = new ArrayList<>();

        Map<String, SplitPack> all = new TreeMap<>();
        all.putAll(before);
        all.putAll(after);

        for (String namespace : all.keySet()) {
            SplitPack oldPack = before.get(namespace);
            SplitPack newPack = after.get(namespace);

            if (oldPack == null) {
                added.add(namespace);
            } else if (newPack == null) {
                removed.add(namespace);
            } else if (oldPack.sha1().equals(newPack.sha1())) {
                unchanged.add(namespace);
            } else {
                changed.add(namespace);
            }
        }

        return new NamespaceTransition(added, removed, changed, unchanged);
    }

    public int totalChanged() {
        return added.size() + removed.size() + changed.size();
    }
}
