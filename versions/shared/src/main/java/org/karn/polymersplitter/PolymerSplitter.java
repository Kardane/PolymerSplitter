package org.karn.polymersplitter;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import org.karn.polymersplitter.command.PolymerSplitterCommands;
import org.karn.polymersplitter.common.lifecycle.SplitCoordinator;
import org.karn.polymersplitter.common.lifecycle.SplitRegistry;
import org.karn.polymersplitter.common.lifecycle.SplitState;
import org.karn.polymersplitter.config.PolymerSplitterConfig;
import org.karn.polymersplitter.polymer.PolymerAutoHostBridge;
import org.karn.polymersplitter.polymer.PolymerGenerationHook;

import java.io.IOException;
import java.nio.file.Path;

public final class PolymerSplitter implements ModInitializer {
    private static final System.Logger LOGGER = System.getLogger("PolymerSplitter");

    private static SplitRegistry registry;
    private static SplitCoordinator coordinator;
    private static PolymerSplitterConfig config;

    @Override
    public void onInitialize() {
        Path configDirectory = FabricLoader.getInstance().getConfigDir();

        try {
            config = PolymerSplitterConfig.load(configDirectory.resolve("polymersplitter.json"));
        } catch (IOException | RuntimeException e) {
            throw new IllegalStateException("Failed to load PolymerSplitter config", e);
        }

        registry = new SplitRegistry();
        coordinator = new SplitCoordinator(
                configDirectory
                        .resolve("polymersplitter")
                        .resolve("generated"),
                config.toSplitterConfig(),
                registry
        );

        PolymerSplitterCommands.register();

        if (!config.enabled()) {
            LOGGER.log(System.Logger.Level.INFO,
                    "PolymerSplitter is disabled by config; Polymer AutoHost will use its normal resource pack");
            return;
        }

        PolymerAutoHostBridge.registerPackCollector(coordinator);
        PolymerGenerationHook.register(coordinator);

        LOGGER.log(System.Logger.Level.INFO,
                "PolymerSplitter initialized with splitMode=" + config.splitMode());
    }

    public static boolean isEnabled() {
        return config != null && config.enabled();
    }

    public static boolean shouldUseSplitPacks() {
        return isEnabled()
                && coordinator != null
                && coordinator.state() == SplitState.READY
                && registry != null
                && !registry.isEmpty();
    }

    public static SplitCoordinator coordinator() {
        return coordinator;
    }

    public static SplitRegistry registry() {
        return registry;
    }

    public static PolymerSplitterConfig config() {
        return config;
    }
}
