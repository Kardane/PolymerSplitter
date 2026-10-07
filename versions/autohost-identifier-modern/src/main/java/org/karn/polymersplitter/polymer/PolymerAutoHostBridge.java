package org.karn.polymersplitter.polymer;

import eu.pb4.polymer.autohost.api.AutoHostUtils;
import eu.pb4.polymer.autohost.api.ResourcePackDataProvider;
import eu.pb4.polymer.autohost.impl.AutoHost;
import eu.pb4.polymer.resourcepack.api.PolymerResourcePackUtils;
import net.fabricmc.fabric.api.networking.v1.context.PacketContext;
import net.minecraft.network.protocol.common.ClientboundResourcePackPushPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import org.karn.polymersplitter.common.hosting.HostingStatus;
import org.karn.polymersplitter.common.hosting.PackPushResult;
import org.karn.polymersplitter.common.lifecycle.SplitCoordinator;
import org.karn.polymersplitter.common.lifecycle.SplitGeneration;
import org.karn.polymersplitter.common.lifecycle.SplitState;
import org.karn.polymersplitter.common.pack.HostedPackStore;
import org.karn.polymersplitter.common.pack.SplitPack;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public final class PolymerAutoHostBridge {
    private static final String HOST_NAMESPACE = "polymersplitter";
    private static final String HOST_PREFIX = "packs/";

    private PolymerAutoHostBridge() {
    }

    public static HostingStatus currentHostingStatus() {
        if (AutoHost.config == null) {
            return new HostingStatus(
                    HostingStatus.Kind.UNKNOWN,
                    "<uninitialized>",
                    "Polymer AutoHost configuration is not initialized"
            );
        }

        String providerType = AutoHost.config.type == null
                ? "<unset>"
                : AutoHost.config.type.trim();

        if (!AutoHost.config.enabled) {
            return new HostingStatus(
                    HostingStatus.Kind.DISABLED,
                    providerType,
                    "Polymer AutoHost is disabled"
            );
        }

        return switch (providerType) {
            case "polymer:automatic",
                 "polymer:auto",
                 "polymer:netty",
                 "polymer:same_port",
                 "polymer:http_server",
                 "polymer:standalone" -> new HostingStatus(
                    HostingStatus.Kind.LOCAL,
                    providerType,
                    "Polymer AutoHost can serve registered split files"
            );
            case "polymer:external" -> new HostingStatus(
                    HostingStatus.Kind.EXTERNAL,
                    providerType,
                    "Polymer AutoHost external provider only constructs URLs; PolymerSplitter does not upload split ZIPs"
            );
            case "polymer:empty" -> new HostingStatus(
                    HostingStatus.Kind.EMPTY,
                    providerType,
                    "Polymer AutoHost empty provider does not host resource packs"
            );
            default -> new HostingStatus(
                    HostingStatus.Kind.UNKNOWN,
                    providerType,
                    "AutoHost provider is not explicitly supported by PolymerSplitter"
            );
        };
    }

    public static void registerPackCollector(SplitCoordinator coordinator) {
        AutoHostUtils.SEND_RESOURCE_PACK_COLLECTOR.register((provider, context, consumer) -> {
            if (coordinator.state() != SplitState.READY
                    || !currentHostingStatus().supported()) {
                return;
            }

            List<SplitPack> packs = coordinator.registry().currentPacks();
            if (packs.isEmpty()) {
                return;
            }

            String primaryNamespace = primaryNamespace(packs);

            for (SplitPack pack : packs) {
                consumer.accept(provider.createProperties(
                        context,
                        effectiveUuid(pack, primaryNamespace),
                        identifier(pack),
                        pack.sha1()
                ));
            }
        });

        AutoHostUtils.RESOURCE_PACKS_READY.register((provider, context) ->
                coordinator.state() != SplitState.GENERATING
        );

    }

    public static void registerHostedPacks(
            Path outputRoot,
            List<SplitPack> packs
    ) {
        HostingStatus hosting = currentHostingStatus();
        if (!hosting.supported()) {
            throw new IllegalStateException(
                    "Split-pack hosting is unavailable: " + hosting.message()
                            + " (provider=" + hosting.providerType() + ")"
            );
        }

        final Map<String, Path> hostedFiles;

        try {
            hostedFiles = HostedPackStore.materialize(outputRoot, packs);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to materialize immutable hosted packs", e);
        }

        for (SplitPack pack : packs) {
            Path hostedPath = hostedFiles.get(pack.sha1());
            if (hostedPath == null) {
                throw new IllegalStateException(
                        "Missing hosted blob for namespace " + pack.namespace()
                );
            }

            AutoHostUtils.registerHostedFile(identifier(pack), hostedPath);
        }
    }


    public static PackPushResult pushSplitPacks(
            Collection<ServerPlayer> players,
            SplitGeneration generation,
            String namespace
    ) {
        HostingStatus hosting = currentHostingStatus();
        if (!hosting.supported()) {
            throw new IllegalStateException(
                    "Split-pack hosting is unavailable: " + hosting.message()
                            + " (provider=" + hosting.providerType() + ")"
            );
        }

        List<SplitPack> selectedPacks;
        if (namespace == null) {
            selectedPacks = generation.packs();
        } else {
            SplitPack selected = generation.byNamespace().get(namespace);
            if (selected == null) {
                throw new IllegalArgumentException("Unknown split namespace: " + namespace);
            }
            selectedPacks = List.of(selected);
        }

        ResourcePackDataProvider provider = ResourcePackDataProvider.getActive();
        String primaryNamespace = primaryNamespace(generation.packs());
        int readyPlayers = 0;
        int packetsSent = 0;

        for (ServerPlayer player : players) {
            PacketContext context = player.connection.getPacketContext();
            if (!provider.isReady(context)) {
                continue;
            }
            readyPlayers++;

            for (SplitPack pack : selectedPacks) {
                var properties = provider.createProperties(
                        context,
                        effectiveUuid(pack, primaryNamespace),
                        identifier(pack),
                        pack.sha1()
                );

                player.connection.send(new ClientboundResourcePackPushPacket(
                        properties.id(),
                        properties.url(),
                        properties.hash(),
                        properties.isRequired(),
                        Optional.ofNullable(properties.prompt())
                ));
                packetsSent++;
            }
        }

        return new PackPushResult(
                players.size(),
                readyPlayers,
                selectedPacks.size(),
                packetsSent
        );
    }

    public static void clearHostedRegistrations() {
        AutoHost.FILES.keySet().removeIf(
                path -> path.startsWith(HOST_NAMESPACE + "/" + HOST_PREFIX)
        );
    }

    private static UUID effectiveUuid(SplitPack pack, String primaryNamespace) {
        if (pack.namespace().equals(primaryNamespace)) {
            return PolymerResourcePackUtils.getMainUuid();
        }
        return pack.uuid();
    }

    private static String primaryNamespace(List<SplitPack> packs) {
        for (SplitPack pack : packs) {
            if ("minecraft".equals(pack.namespace())) {
                return pack.namespace();
            }
        }
        return packs.get(0).namespace();
    }

    private static Identifier identifier(SplitPack pack) {
        return Identifier.fromNamespaceAndPath(
                HOST_NAMESPACE,
                HOST_PREFIX + pack.namespace() + "/" + pack.sha1()
        );
    }
}
