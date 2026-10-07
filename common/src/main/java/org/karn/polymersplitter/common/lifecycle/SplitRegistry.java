package org.karn.polymersplitter.common.lifecycle;

import org.karn.polymersplitter.common.pack.SplitPack;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

public final class SplitRegistry {
    private final AtomicReference<SplitGeneration> currentGeneration = new AtomicReference<>();

    public SplitGeneration currentGeneration() {
        return currentGeneration.get();
    }

    public List<SplitPack> currentPacks() {
        SplitGeneration generation = currentGeneration.get();
        return generation == null ? List.of() : generation.packs();
    }

    public boolean isEmpty() {
        return currentGeneration.get() == null;
    }

    public NamespaceTransition replace(SplitGeneration generation) {
        SplitGeneration previous = currentGeneration.getAndSet(generation);
        return NamespaceTransition.between(previous, generation);
    }

    public NamespaceTransition clear() {
        SplitGeneration previous = currentGeneration.getAndSet(null);
        return NamespaceTransition.between(previous, null);
    }
}
