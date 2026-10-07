package org.karn.polymersplitter.command;

import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerPlayer;
import org.karn.polymersplitter.PolymerSplitter;
import org.karn.polymersplitter.common.lifecycle.SplitState;
import org.karn.polymersplitter.common.pack.SplitPack;
import org.karn.polymersplitter.polymer.PolymerAutoHostBridge;
import org.karn.polymersplitter.polymer.PolymerGenerationHook;
import org.karn.polymersplitter.util.Format;

import java.util.Collection;
import java.util.Comparator;

public final class PolymerSplitterCommands {
    private PolymerSplitterCommands() {
    }

    public static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                dispatcher.register(Commands.literal("polymersplitter")
                        .requires(CommandPermissions::canUse)
                        .then(Commands.literal("status").executes(context -> status(context.getSource())))
                        .then(Commands.literal("list").executes(context -> list(context.getSource())))
                        .then(Commands.literal("reload").executes(context -> reload(context.getSource())))
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
                                                        .suggests((context, builder) -> {
                                                            var snapshot = PolymerSplitter.coordinator().snapshot();
                                                            var generation = snapshot.generation();
                                                            return SharedSuggestionProvider.suggest(
                                                                    snapshot.state() == SplitState.READY && generation != null
                                                                            ? generation.byNamespace().keySet().stream().sorted()
                                                                            : java.util.stream.Stream.<String>empty(),
                                                                    builder
                                                            );
                                                        })
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
        var snapshot = coordinator.snapshot();
        var packs = snapshot.generation() == null
                ? java.util.List.<SplitPack>of()
                : snapshot.generation().packs();

        source.sendSuccess(() -> message("Status", ChatFormatting.AQUA)
                .append(field("state", snapshot.state(), stateColor(snapshot.state())))
                .append(field("enabled", PolymerSplitter.isEnabled(),
                        PolymerSplitter.isEnabled() ? ChatFormatting.GREEN : ChatFormatting.YELLOW))
                .append(field("packs", packs.size(), ChatFormatting.WHITE))
                .append(field("total", Format.formatBytes(totalSize(packs)), ChatFormatting.WHITE)), false);

        var hosting = PolymerAutoHostBridge.currentHostingStatus();
        source.sendSuccess(() -> message("AutoHost", ChatFormatting.AQUA)
                .append(field("kind", hosting.kind(), ChatFormatting.WHITE))
                .append(field("provider", hosting.providerType(), ChatFormatting.WHITE))
                .append(field("supported", hosting.supported(),
                        hosting.supported() ? ChatFormatting.GREEN : ChatFormatting.YELLOW)), false);

        if (!hosting.supported()) {
            source.sendSuccess(() -> message("AutoHost reason: " + hosting.message(),
                    ChatFormatting.YELLOW), false);
        }

        var transition = snapshot.lastTransition();
        source.sendSuccess(() -> message("Namespaces", ChatFormatting.AQUA)
                .append(field("added", transition.added().size(), ChatFormatting.GREEN))
                .append(field("removed", transition.removed().size(), ChatFormatting.RED))
                .append(field("changed", transition.changed().size(), ChatFormatting.YELLOW))
                .append(field("unchanged", transition.unchanged().size(), ChatFormatting.GRAY)), false);

        if (snapshot.sourceHash() != null) {
            source.sendSuccess(() -> message("Source", ChatFormatting.AQUA)
                    .append(field("SHA-1", snapshot.sourceHash(), ChatFormatting.GRAY)), false);
        }

        if (snapshot.lastFailure() != null) {
            source.sendSuccess(() -> message("Last failure: " + snapshot.lastFailure(),
                    ChatFormatting.RED), false);
        }

        return 1;
    }

    private static int list(CommandSourceStack source) {
        var packs = PolymerSplitter.registry().currentPacks().stream()
                .sorted(Comparator.comparing(SplitPack::namespace))
                .toList();

        if (packs.isEmpty()) {
            source.sendSuccess(() -> message("No split packs are ready", ChatFormatting.YELLOW), false);
            return 1;
        }

        source.sendSuccess(() -> message("Split packs: " + packs.size(), ChatFormatting.AQUA), false);
        for (SplitPack pack : packs) {
            source.sendSuccess(() -> message(pack.namespace(), ChatFormatting.GOLD)
                    .append(field("size", Format.formatBytes(pack.size()), ChatFormatting.WHITE))
                    .append(field("sha1", abbreviate(pack.sha1()), ChatFormatting.GRAY))
                    .append(field("uuid", pack.uuid(), ChatFormatting.GRAY)), false);
        }

        return packs.size();
    }

    private static int send(
            CommandSourceStack source,
            Collection<ServerPlayer> targets,
            String namespace
    ) {
        var hosting = PolymerAutoHostBridge.currentHostingStatus();
        if (!hosting.supported()) {
            source.sendFailure(message(
                "Split delivery is unavailable: " + hosting.message()
                            + " (provider=" + hosting.providerType() + ")", ChatFormatting.RED
            ));
            return 0;
        }

        var snapshot = PolymerSplitter.coordinator().snapshot();
        var generation = snapshot.generation();
        if (generation == null || snapshot.state() != SplitState.READY) {
            source.sendFailure(message("No split generation is ready", ChatFormatting.RED));
            return 0;
        }

        if (namespace != null && !generation.byNamespace().containsKey(namespace)) {
            source.sendFailure(message("Unknown split namespace: " + namespace, ChatFormatting.RED));
            return 0;
        }

        var result = PolymerAutoHostBridge.pushSplitPacks(targets, generation, namespace);
        if (result.readyPlayers() == 0) {
            source.sendFailure(message(
                    "AutoHost is not ready for any selected player", ChatFormatting.RED
            ));
            return 0;
        }

        source.sendSuccess(() -> message("Packs sent", ChatFormatting.GREEN)
                .append(field("namespace", namespace == null ? "all" : namespace, ChatFormatting.GOLD))
                .append(field("packs", result.selectedPacks(), ChatFormatting.WHITE))
                .append(field("players", result.readyPlayers() + "/" + result.targetedPlayers(),
                        result.readyPlayers() == result.targetedPlayers()
                                ? ChatFormatting.GREEN : ChatFormatting.YELLOW))
                .append(field("packets", result.packetsSent(), ChatFormatting.WHITE)), true);

        return result.packetsSent();
    }

    private static int reload(CommandSourceStack source) {
        try {
            var result = PolymerSplitter.reloadConfig();
            var config = PolymerSplitter.config();

            source.sendSuccess(() -> message("Config reloaded", ChatFormatting.GREEN)
                    .append(field("compressionLevel", config.compressionLevel(), ChatFormatting.WHITE))
                    .append(field("minSplitPackSizeMb", config.minSplitPackSizeMb(), ChatFormatting.WHITE))
                    .append(field("outputChanged", result.outputSettingsChanged(),
                            result.outputSettingsChanged() ? ChatFormatting.YELLOW : ChatFormatting.GRAY)), false);

            if (result.outputSettingsChanged()) {
                source.sendSuccess(() -> message(
                        "Output settings will apply to the next Polymer generation; run /polymersplitter rebuild to apply them now",
                        ChatFormatting.YELLOW
                ), false);
            }

            if (result.enabledRestartRequired()) {
                source.sendSuccess(() -> message(
                        "enabled=" + result.configuredEnabled()
                                + " requires a server restart; effective enabled="
                                + result.effectiveEnabled(),
                        ChatFormatting.YELLOW
                ), false);
            }

            return 1;
        } catch (Exception e) {
            source.sendFailure(message(
                    "Config reload failed: "
                            + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()),
                    ChatFormatting.RED
            ));
            return 0;
        }
    }

    private static int rebuild(CommandSourceStack source) {
        if (!PolymerSplitter.isEnabled()) {
            source.sendFailure(message(
                    "PolymerSplitter is disabled in config/polymersplitter.json", ChatFormatting.RED
            ));
            return 0;
        }

        var hosting = PolymerAutoHostBridge.currentHostingStatus();
        if (!hosting.supported()) {
            source.sendFailure(message(
                    "Split delivery is unavailable: " + hosting.message()
                            + " (provider=" + hosting.providerType() + ")", ChatFormatting.RED
            ));
            return 0;
        }

        source.sendSuccess(() -> message("Requesting Polymer resource-pack rebuild",
                ChatFormatting.YELLOW), false);

        PolymerGenerationHook.requestRebuild(source);
        return 1;
    }

    private static MutableComponent message(String text, ChatFormatting color) {
        return Component.literal("[PolymerSplitter] ").withStyle(ChatFormatting.AQUA)
                .append(Component.literal(text).withStyle(color));
    }

    private static MutableComponent field(String label, Object value, ChatFormatting color) {
        return Component.literal(" | " + label + "=").withStyle(ChatFormatting.GRAY)
                .append(Component.literal(String.valueOf(value)).withStyle(color));
    }

    private static ChatFormatting stateColor(SplitState state) {
        return switch (state) {
            case READY -> ChatFormatting.GREEN;
            case GENERATING -> ChatFormatting.YELLOW;
            case FAILED -> ChatFormatting.RED;
            case NOT_STARTED -> ChatFormatting.GRAY;
        };
    }

    private static long totalSize(Iterable<SplitPack> packs) {
        long total = 0;
        for (SplitPack pack : packs) {
            total += pack.size();
        }
        return total;
    }

    private static String abbreviate(String hash) {
        return hash.length() <= 12 ? hash : hash.substring(0, 12);
    }
}
