package org.karn.polymersplitter.polymer;

import eu.pb4.polymer.autohost.api.AutoHostUtils;
import eu.pb4.polymer.autohost.api.ResourcePackDataProvider;
import eu.pb4.polymer.autohost.impl.AutoHost;
import net.minecraft.network.protocol.common.ClientboundResourcePackPushPacket;
import net.minecraft.server.level.ServerPlayer;
import org.karn.polymersplitter.common.lifecycle.SplitCoordinator;
import org.karn.polymersplitter.common.lifecycle.SplitState;
import org.karn.polymersplitter.common.pack.SplitPack;
import xyz.nucleoid.packettweaker.PacketContext;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

final class AutoHostAccess {
    private AutoHostAccess() {
    }

    static AutoHostSettings settings() {
        if (AutoHost.config == null) {
            return new AutoHostSettings(false, false, null);
        }
        return new AutoHostSettings(true, AutoHost.config.enabled, AutoHost.config.type);
    }

    static void registerPackCollector(SplitCoordinator coordinator) {
        AutoHostUtils.SEND_RESOURCE_PACK_COLLECTOR.register((provider, context, consumer) -> {
            var snapshot = coordinator.snapshot();
            if (snapshot.state() != SplitState.READY
                    || snapshot.generation() == null
                    || !PolymerAutoHostBridge.currentHostingStatus().supported()) {
                return;
            }

            List<SplitPack> packs = snapshot.generation().packs();

            String primaryNamespace = SplitDelivery.primaryNamespace(packs);
            for (SplitPack pack : packs) {
                consumer.accept(provider.createProperties(
                        context,
                        SplitDelivery.effectiveUuid(pack, primaryNamespace),
                        PackIdentifier.create(pack),
                        pack.sha1()
                ));
            }
        });
    }

    static void registerReadiness(SplitCoordinator coordinator) {
        // Legacy providers expose readiness without the modern per-context event.
    }

    static void registerHostedFile(SplitPack pack, Path path) {
        AutoHostUtils.registerHostedFile(PackIdentifier.create(pack), path);
    }

    static int sendPlayer(
            ServerPlayer player,
            List<SplitPack> packs,
            String primaryNamespace
    ) {
        ResourcePackDataProvider provider = ResourcePackDataProvider.getActive();
        if (!provider.isReady()) {
            return 0;
        }

        PacketContext context = PacketContext.create(player);
        for (SplitPack pack : packs) {
            var properties = provider.createProperties(
                    context,
                    SplitDelivery.effectiveUuid(pack, primaryNamespace),
                    PackIdentifier.create(pack),
                    pack.sha1()
            );

            player.connection.send(new ClientboundResourcePackPushPacket(
                    properties.id(),
                    properties.url(),
                    properties.hash(),
                    properties.isRequired(),
                    Optional.ofNullable(properties.prompt())
            ));
        }

        return packs.size();
    }

    static void clearHostedRegistrations() {
        AutoHost.FILES.keySet().removeIf(
                path -> path.startsWith(
                        SplitDelivery.HOST_NAMESPACE + "/" + SplitDelivery.HOST_PREFIX
                )
        );
    }
}
