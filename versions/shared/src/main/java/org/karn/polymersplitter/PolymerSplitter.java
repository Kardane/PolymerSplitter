package org.karn.polymersplitter;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.loader.api.FabricLoader;
import org.karn.polymersplitter.command.PolymerSplitterCommands;
import org.karn.polymersplitter.common.hosting.HostingStatus;
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

        coordinator = new SplitCoordinator(
                configDirectory
                        .resolve("polymersplitter")
                        .resolve("generated"),
                config.toSplitterConfig()
        );
        registry = coordinator.registry();

        PolymerSplitterCommands.register();

        if (!config.enabled()) {
            LOGGER.log(System.Logger.Level.INFO,
                    "PolymerSplitter is disabled by config; Polymer AutoHost will use its normal resource pack");
            return;
        }

        PolymerAutoHostBridge.registerPackCollector(coordinator);
        PolymerGenerationHook.register(coordinator);

        ServerLifecycleEvents.SERVER_STARTED.register(server -> inspectHostingAndRecover());
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            PolymerAutoHostBridge.clearHostedRegistrations();
            coordinator.resetForServerStop();
        });

        LOGGER.log(System.Logger.Level.INFO,
                "PolymerSplitter initialized with splitMode=" + config.splitMode());
    }

    private static void inspectHostingAndRecover() {
        HostingStatus hosting = PolymerAutoHostBridge.currentHostingStatus();

        if (hosting.supported()) {
            LOGGER.log(System.Logger.Level.INFO,
                    "Polymer AutoHost provider supported: " + hosting.providerType());
        } else {
            LOGGER.log(System.Logger.Level.WARNING,
                    "PolymerSplitter split delivery disabled for AutoHost provider '"
                            + hosting.providerType() + "': " + hosting.message());
        }

        if (coordinator.snapshot().state() != SplitState.NOT_STARTED) {
            return;
        }

        // Safe at server start before cached state is published. This removes
        // PolymerSplitter mappings left by a prior server instance in the same JVM.
        PolymerAutoHostBridge.clearHostedRegistrations();
        recoverStartupState(hosting);
    }

    private static void recoverStartupState(HostingStatus hosting) {
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

        if (hosting.supported()) {
            try {
                var restored = coordinator.restore(
                        packs -> PolymerAutoHostBridge.registerHostedPacks(
                                coordinator.outputRoot(),
                                packs
                        )
                );

                if (restored.isPresent()) {
                    LOGGER.log(System.Logger.Level.INFO,
                            "Restored cached split generation: packs=" + restored.get().size()
                                    + ", sourceSha1=" + coordinator.sourceHash());

                    try {
                        int deletedBlobs = SplitRecovery.cleanupUnreferencedHostedBlobs(
                                coordinator.outputRoot(),
                                restored.get()
                        );
                        if (deletedBlobs > 0) {
                            LOGGER.log(System.Logger.Level.INFO,
                                    "Removed " + deletedBlobs + " unreferenced hosted blob(s)");
                        }
                    } catch (IOException | RuntimeException e) {
                        LOGGER.log(System.Logger.Level.WARNING,
                                "Failed to clean unreferenced PolymerSplitter hosted blobs", e);
                    }
                }
            } catch (IOException | RuntimeException e) {
                LOGGER.log(System.Logger.Level.WARNING,
                        "Cached split generation could not be restored; Polymer main pack remains the fallback",
                        e);
            }
        }
    }

    public static boolean isEnabled() {
        return config != null && config.enabled();
    }

    public static boolean shouldUseSplitPacks() {
        if (!isEnabled()
                || coordinator == null
                || !PolymerAutoHostBridge.currentHostingStatus().supported()) {
            return false;
        }

        var snapshot = coordinator.snapshot();
        return snapshot.state() == SplitState.READY
                && snapshot.generation() != null;
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
