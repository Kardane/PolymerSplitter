package org.karn.polymersplitter.polymer;

import eu.pb4.polymer.resourcepack.api.OutputGenerator;
import eu.pb4.polymer.resourcepack.api.PolymerResourcePackUtils;
import net.minecraft.commands.CommandSourceStack;
import org.karn.polymersplitter.common.lifecycle.SplitCoordinator;

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

                SplitPublisher.publish(coordinator, output.path());
                return;
            }

            SplitPublisher.publish(coordinator, PolymerResourcePackUtils.getMainPath());
        });
    }

    public static void requestRebuild(CommandSourceStack source) {
        PolymerResourcePackInternals.requestRebuild(source);
    }

}
