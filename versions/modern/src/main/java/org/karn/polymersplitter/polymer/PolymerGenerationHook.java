package org.karn.polymersplitter.polymer;

import eu.pb4.polymer.resourcepack.api.OutputGenerator;
import eu.pb4.polymer.resourcepack.api.PolymerResourcePackUtils;
import org.karn.polymersplitter.common.lifecycle.SplitCoordinator;

import java.nio.file.Path;

public final class PolymerGenerationHook {
    private static final System.Logger LOGGER = System.getLogger("PolymerSplitter");

    private PolymerGenerationHook() {
    }

    public static void register(SplitCoordinator coordinator) {
        PolymerResourcePackUtils.RESOURCE_PACK_INITIALIZED_EVENT.register(coordinator::markGenerating);
        PolymerResourcePackUtils.RESOURCE_PACK_FINISHED_EVENT.register(result -> {
            if (result instanceof OutputGenerator.Result output) {
                if (output.hadIssues()) {
                    coordinator.markFailed(new IllegalStateException(
                            "Polymer reported issues while generating the resource pack"
                    ));
                    LOGGER.log(System.Logger.Level.ERROR,
                            "Polymer resource pack generation reported issues; split registry was not replaced");
                    return;
                }

                split(coordinator, output.path());
                return;
            }

            split(coordinator, PolymerResourcePackUtils.getMainPath());
        });
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
