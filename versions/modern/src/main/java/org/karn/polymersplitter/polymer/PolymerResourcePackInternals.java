package org.karn.polymersplitter.polymer;

import eu.pb4.polymer.resourcepack.impl.PolymerResourcePackMod;
import net.minecraft.commands.CommandSourceStack;

final class PolymerResourcePackInternals {
    private PolymerResourcePackInternals() {
    }

    static void requestRebuild(CommandSourceStack source) {
        PolymerResourcePackMod.generateAndCall(
                source.getServer(),
                false,
                message -> source.sendSuccess(() -> message, true),
                ignored -> {
                }
        );
    }
}
