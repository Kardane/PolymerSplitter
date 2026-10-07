package org.karn.polymersplitter.common.lifecycle;

import org.karn.polymersplitter.common.pack.SplitPack;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

public final class SplitRegistry {
    private final AtomicReference<List<SplitPack>> currentPacks = new AtomicReference<>(List.of());

    public List<SplitPack> currentPacks() {
        return currentPacks.get();
    }

    public boolean isEmpty() {
        return currentPacks.get().isEmpty();
    }

    public void replace(List<SplitPack> packs) {
        currentPacks.set(List.copyOf(packs));
    }
}
