package org.karn.polymersplitter.polymer;

import eu.pb4.polymer.resourcepack.api.PolymerResourcePackUtils;
import eu.pb4.polymer.resourcepack.impl.PolymerResourcePackMod;
import org.karn.polymersplitter.common.lifecycle.SplitCoordinator;

import java.nio.file.Path;

public final class PolymerGenerationHook {
    private static final System.Logger LOGGER = System.getLogger("PolymerSplitter");

    private PolymerGenerationHook() {
    }

    public static void register(SplitCoordinator coordinator) {
        PolymerResourcePackUtils.RESOURCE_PACK_CREATION_EVENT.register(builder -> coordinator.markGenerating());
        PolymerResourcePackUtils.RESOURCE_PACK_FINISHED_EVENT.register(
                () -> split(coordinator, resolveGeneratedPack())
        );
    }

    private static Path resolveGeneratedPack() {
        Path mainPath = PolymerResourcePackUtils.getMainPath();
        if (PolymerResourcePackMod.useMainPath) {
            return mainPath;
        }

        return mainPath.resolveSibling(mainPath.getFileName().toString() + "_server.zip");
    }

    private static void split(SplitCoordinator coordinator, Path generatedPack) {
        try {
            int count = coordinator.process(
                    generatedPack,
                    PolymerAutoHostBridge::registerHostedPacks
            ).size();

            LOGGER.log(System.Logger.Level.INFO,
                    "Split and registered Polymer resource pack into " + count + " namespace pack(s)");
        } catch (Exception e) {
            LOGGER.log(System.Logger.Level.ERROR,
                    "Failed to split or register Polymer resource pack; existing split registry was kept", e);
        }
    }
}
