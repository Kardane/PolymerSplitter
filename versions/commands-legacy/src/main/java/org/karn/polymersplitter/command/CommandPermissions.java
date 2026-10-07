package org.karn.polymersplitter.command;

import net.minecraft.commands.CommandSourceStack;

final class CommandPermissions {
    private CommandPermissions() {
    }

    static boolean canUse(CommandSourceStack source) {
        return source.hasPermission(3);
    }
}
