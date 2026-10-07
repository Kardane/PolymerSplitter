package org.karn.polymersplitter.common.lifecycle;

import org.karn.polymersplitter.common.pack.SplitPack;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

public final class SplitRegistry {
    private final AtomicReference<CoordinatorSnapshot> snapshot;

    SplitRegistry(AtomicReference<CoordinatorSnapshot> snapshot) {
        this.snapshot = Objects.requireNonNull(snapshot, "snapshot");
    }

    public SplitGeneration currentGeneration() {
        return snapshot.get().generation();
    }

    public List<SplitPack> currentPacks() {
        SplitGeneration generation = snapshot.get().generation();
        return generation == null ? List.of() : generation.packs();
    }

    public boolean isEmpty() {
        return snapshot.get().generation() == null;
    }
}
