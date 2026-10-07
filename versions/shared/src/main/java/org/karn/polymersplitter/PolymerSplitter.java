package org.karn.polymersplitter;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import org.karn.polymersplitter.common.lifecycle.SplitCoordinator;
import org.karn.polymersplitter.common.lifecycle.SplitRegistry;
import org.karn.polymersplitter.common.pack.SplitterConfig;
import org.karn.polymersplitter.polymer.PolymerGenerationHook;

public final class PolymerSplitter implements ModInitializer {
    private static final SplitRegistry REGISTRY = new SplitRegistry();
    private static final SplitCoordinator COORDINATOR = new SplitCoordinator(
            FabricLoader.getInstance()
                    .getConfigDir()
                    .resolve("polymersplitter")
                    .resolve("generated"),
            SplitterConfig.defaults(),
            REGISTRY
    );

    @Override
    public void onInitialize() {
        PolymerGenerationHook.register(COORDINATOR);
    }

    public static SplitCoordinator coordinator() {
        return COORDINATOR;
    }

    public static SplitRegistry registry() {
        return REGISTRY;
    }
}
