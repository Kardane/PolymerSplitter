package org.karn.polymersplitter.polymer;

import eu.pb4.polymer.resourcepack.api.PolymerResourcePackUtils;
import eu.pb4.polymer.resourcepack.impl.PolymerResourcePackMod;
import net.minecraft.commands.CommandSourceStack;
import org.karn.polymersplitter.common.lifecycle.SplitCoordinator;

import java.nio.file.Path;

public final class PolymerGenerationHook {
    private PolymerGenerationHook() {
    }

    public static void register(SplitCoordinator coordinator) {
        PolymerResourcePackUtils.RESOURCE_PACK_CREATION_EVENT.register(builder -> coordinator.markGenerating());
        PolymerResourcePackUtils.RESOURCE_PACK_FINISHED_EVENT.register(
                () -> SplitPublisher.publish(coordinator, resolveGeneratedPack())
        );
    }

    public static void requestRebuild(CommandSourceStack source) {
        PolymerResourcePackMod.generateAndCall(
                source.getServer(),
                false,
                message -> source.sendSuccess(() -> message, true),
                () -> {
                }
        );
    }

    private static Path resolveGeneratedPack() {
        Path mainPath = PolymerResourcePackUtils.getMainPath();
        if (PolymerResourcePackMod.useMainPath) {
            return mainPath;
        }

        return mainPath.resolveSibling(mainPath.getFileName().toString() + "_server.zip");
    }
}
