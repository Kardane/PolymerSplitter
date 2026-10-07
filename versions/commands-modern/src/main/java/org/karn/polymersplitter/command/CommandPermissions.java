package org.karn.polymersplitter.command;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.permissions.Permissions;

final class CommandPermissions {
    private CommandPermissions() {
    }

    static boolean canUse(CommandSourceStack source) {
        return source.permissions().hasPermission(Permissions.COMMANDS_MODERATOR);
    }
}
