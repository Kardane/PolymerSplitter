package org.karn.polymersplitter.polymer;

import eu.pb4.polymer.resourcepack.api.PolymerResourcePackUtils;
import eu.pb4.polymer.resourcepack.impl.PolymerResourcePackMod;
import net.minecraft.commands.CommandSourceStack;

import java.nio.file.Path;

final class PolymerResourcePackInternals {
    private PolymerResourcePackInternals() {
    }

    static void requestRebuild(CommandSourceStack source) {
        PolymerResourcePackMod.generateAndCall(
                source.getServer(),
                false,
                message -> source.sendSuccess(() -> message, true),
                () -> {
                }
        );
    }

    static Path resolveGeneratedPack() {
        Path mainPath = PolymerResourcePackUtils.getMainPath();
        if (PolymerResourcePackMod.useMainPath) {
            return mainPath;
        }

        return mainPath.resolveSibling(
                mainPath.getFileName().toString() + "_server.zip"
        );
    }
}
