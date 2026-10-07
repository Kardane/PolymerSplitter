package org.karn.polymersplitter.polymer;

import eu.pb4.polymer.resourcepack.api.PolymerResourcePackUtils;
import net.minecraft.commands.CommandSourceStack;
import org.karn.polymersplitter.common.lifecycle.SplitCoordinator;


public final class PolymerGenerationHook {
    private PolymerGenerationHook() {
    }

    public static void register(SplitCoordinator coordinator) {
        PolymerResourcePackUtils.RESOURCE_PACK_CREATION_EVENT.register(builder -> coordinator.markGenerating());
        PolymerResourcePackUtils.RESOURCE_PACK_FINISHED_EVENT.register(
                () -> SplitPublisher.publish(
                        coordinator,
                        PolymerResourcePackInternals.resolveGeneratedPack()
                )
        );
    }

    public static void requestRebuild(CommandSourceStack source) {
        PolymerResourcePackInternals.requestRebuild(source);
    }

}
