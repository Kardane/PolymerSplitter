package org.karn.polymersplitter;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import org.karn.polymersplitter.command.PolymerSplitterCommands;
import org.karn.polymersplitter.common.lifecycle.SplitCoordinator;
import org.karn.polymersplitter.common.lifecycle.SplitRegistry;
import org.karn.polymersplitter.common.lifecycle.SplitState;
import org.karn.polymersplitter.common.pack.SplitRecovery;
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

        recoverStartupState();

        LOGGER.log(System.Logger.Level.INFO,
                "PolymerSplitter initialized with splitMode=" + config.splitMode());
    }

    private static void recoverStartupState() {
        try {
            int deletedTemps = SplitRecovery.cleanupTemporaryFiles(coordinator.outputRoot());
            if (deletedTemps > 0) {
                LOGGER.log(System.Logger.Level.INFO,
                        "Removed " + deletedTemps + " interrupted temporary file(s)");
            }
        } catch (IOException | RuntimeException e) {
            LOGGER.log(System.Logger.Level.WARNING,
                    "Failed to clean interrupted PolymerSplitter temporary files", e);
        }

        String restoredHash = null;

        try {
            var restored = coordinator.restore(
                    packs -> PolymerAutoHostBridge.registerHostedPacks(
                            coordinator.outputRoot(),
                            packs
                    )
            );

            if (restored.isPresent()) {
                restoredHash = coordinator.sourceHash();
                LOGGER.log(System.Logger.Level.INFO,
                        "Restored cached split generation: packs=" + restored.get().size()
                                + ", sourceSha1=" + restoredHash);
            }
        } catch (IOException | RuntimeException e) {
            LOGGER.log(System.Logger.Level.WARNING,
                    "Cached split generation could not be restored; Polymer main pack remains the fallback",
                    e);
        }

        try {
            int deletedGenerations = SplitRecovery.cleanupIncompleteGenerations(
                    coordinator.outputRoot(),
                    restoredHash
            );

            if (deletedGenerations > 0) {
                LOGGER.log(System.Logger.Level.INFO,
                        "Removed " + deletedGenerations + " incomplete generation director"
                                + (deletedGenerations == 1 ? "y" : "ies"));
            }
        } catch (IOException | RuntimeException e) {
            LOGGER.log(System.Logger.Level.WARNING,
                    "Failed to clean incomplete PolymerSplitter generations", e);
        }
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
