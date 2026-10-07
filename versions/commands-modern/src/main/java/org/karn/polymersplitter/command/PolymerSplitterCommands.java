package org.karn.polymersplitter.command;

import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.permissions.Permissions;
import org.karn.polymersplitter.PolymerSplitter;
import org.karn.polymersplitter.common.pack.SplitPack;
import org.karn.polymersplitter.polymer.PolymerAutoHostBridge;
import org.karn.polymersplitter.polymer.PolymerGenerationHook;

import java.util.Comparator;

public final class PolymerSplitterCommands {
    private PolymerSplitterCommands() {
    }

    public static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                dispatcher.register(Commands.literal("polymersplitter")
                        .requires(source -> source.permissions().hasPermission(Permissions.COMMANDS_MODERATOR))
                        .then(Commands.literal("status").executes(context -> status(context.getSource())))
                        .then(Commands.literal("list").executes(context -> list(context.getSource())))
                        .then(Commands.literal("rebuild").executes(context -> rebuild(context.getSource())))
                        .then(Commands.literal("send")
                                .then(Commands.argument("targets", EntityArgument.players())
                                        .then(Commands.literal("all")
                                                .executes(context -> send(
                                                        context.getSource(),
                                                        EntityArgument.getPlayers(context, "targets"),
                                                        null
                                                )))
                                        .then(Commands.literal("namespace")
                                                .then(Commands.argument("namespace", StringArgumentType.word())
                                                        .executes(context -> send(
                                                                context.getSource(),
                                                                EntityArgument.getPlayers(context, "targets"),
                                                                StringArgumentType.getString(context, "namespace")
                                                        ))
                                                )
                                        )
                                )
                        )
                )
        );
    }

    private static int status(CommandSourceStack source) {
        var coordinator = PolymerSplitter.coordinator();
        var packs = PolymerSplitter.registry().currentPacks();

        source.sendSuccess(() -> Component.literal(
                "PolymerSplitter: " + coordinator.state()
                        + " | enabled=" + PolymerSplitter.isEnabled()
                        + " | packs=" + packs.size()
                        + " | total=" + formatBytes(totalSize(packs))
        ), false);

        var hosting = PolymerAutoHostBridge.currentHostingStatus();
        source.sendSuccess(() -> Component.literal(
                "AutoHost: " + hosting.kind()
                        + " | provider=" + hosting.providerType()
                        + " | supported=" + hosting.supported()
        ), false);

        if (!hosting.supported()) {
            source.sendSuccess(() -> Component.literal(
                    "AutoHost reason: " + hosting.message()
            ), false);
        }

        var transition = coordinator.lastTransition();
        source.sendSuccess(() -> Component.literal(
                "Namespaces: +" + transition.added().size()
                        + " -" + transition.removed().size()
                        + " ~" + transition.changed().size()
                        + " =" + transition.unchanged().size()
        ), false);

        if (coordinator.sourceHash() != null) {
            source.sendSuccess(() -> Component.literal(
                    "Source SHA-1: " + coordinator.sourceHash()
            ), false);
        }

        if (coordinator.lastFailure() != null) {
            source.sendSuccess(() -> Component.literal(
                    "Last failure: " + coordinator.lastFailure()
            ), false);
        }

        return 1;
    }

    private static int list(CommandSourceStack source) {
        var packs = PolymerSplitter.registry().currentPacks().stream()
                .sorted(Comparator.comparing(SplitPack::namespace))
                .toList();

        if (packs.isEmpty()) {
            source.sendSuccess(() -> Component.literal("PolymerSplitter: no split packs are ready"), false);
            return 1;
        }

        for (SplitPack pack : packs) {
            source.sendSuccess(() -> Component.literal(
                    pack.namespace()
                            + " | " + formatBytes(pack.size())
                            + " | sha1=" + abbreviate(pack.sha1())
                            + " | uuid=" + pack.uuid()
            ), false);
        }

        return packs.size();
    }

    private static int send(
            CommandSourceStack source,
            java.util.Collection<net.minecraft.server.level.ServerPlayer> targets,
            String namespace
    ) {
        var hosting = PolymerAutoHostBridge.currentHostingStatus();
        if (!hosting.supported()) {
            source.sendFailure(Component.literal(
                    "Split delivery is unavailable: " + hosting.message()
                            + " (provider=" + hosting.providerType() + ")"
            ));
            return 0;
        }

        var generation = PolymerSplitter.registry().currentGeneration();
        if (generation == null || PolymerSplitter.coordinator().state()
                != org.karn.polymersplitter.common.lifecycle.SplitState.READY) {
            source.sendFailure(Component.literal(
                    "No split generation is ready"
            ));
            return 0;
        }

        if (namespace != null && !generation.byNamespace().containsKey(namespace)) {
            source.sendFailure(Component.literal(
                    "Unknown split namespace: " + namespace
            ));
            return 0;
        }

        var result = PolymerAutoHostBridge.pushSplitPacks(
                targets,
                generation,
                namespace
        );

        if (result.readyPlayers() == 0) {
            source.sendFailure(Component.literal(
                    "AutoHost is not ready for any selected player"
            ));
            return 0;
        }

        source.sendSuccess(() -> Component.literal(
                "Pushed " + result.selectedPacks() + " split pack(s) to "
                        + result.readyPlayers() + "/" + result.targetedPlayers()
                        + " player(s) (" + result.packetsSent() + " packet(s))"
        ), true);

        return result.packetsSent();
    }

    private static int rebuild(CommandSourceStack source) {
        if (!PolymerSplitter.isEnabled()) {
            source.sendFailure(Component.literal(
                    "PolymerSplitter is disabled in config/polymersplitter.json"
            ));
            return 0;
        }

        var hosting = PolymerAutoHostBridge.currentHostingStatus();
        if (!hosting.supported()) {
            source.sendFailure(Component.literal(
                    "Split delivery is unavailable: " + hosting.message()
                            + " (provider=" + hosting.providerType() + ")"
            ));
            return 0;
        }

        source.sendSuccess(() -> Component.literal(
                "Requesting Polymer resource-pack rebuild"
        ), false);

        PolymerGenerationHook.requestRebuild(source);
        return 1;
    }

    private static long totalSize(Iterable<SplitPack> packs) {
        long total = 0;
        for (SplitPack pack : packs) {
            total += pack.size();
        }
        return total;
    }

    private static String formatBytes(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }

        double value = bytes;
        String[] units = {"KiB", "MiB", "GiB"};
        int unit = -1;

        do {
            value /= 1024.0;
            unit++;
        } while (value >= 1024.0 && unit + 1 < units.length);

        return String.format("%.1f %s", value, units[unit]);
    }

    private static String abbreviate(String hash) {
        return hash.length() <= 12 ? hash : hash.substring(0, 12);
    }
}
