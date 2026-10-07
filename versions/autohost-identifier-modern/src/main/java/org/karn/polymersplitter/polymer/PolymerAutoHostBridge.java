package org.karn.polymersplitter.polymer;

import eu.pb4.polymer.autohost.api.AutoHostUtils;
import eu.pb4.polymer.autohost.api.ResourcePackDataProvider;
import eu.pb4.polymer.resourcepack.api.PolymerResourcePackUtils;
import net.fabricmc.fabric.api.networking.v1.context.PacketContext;
import net.minecraft.resources.Identifier;
import org.karn.polymersplitter.common.lifecycle.SplitCoordinator;
import org.karn.polymersplitter.common.lifecycle.SplitState;
import org.karn.polymersplitter.common.pack.SplitPack;

import java.util.List;
import java.util.UUID;

public final class PolymerAutoHostBridge {
    private static final String HOST_NAMESPACE = "polymersplitter";
    private static final String HOST_PREFIX = "packs/";

    private PolymerAutoHostBridge() {
    }

    public static void registerPackCollector(SplitCoordinator coordinator) {
        AutoHostUtils.SEND_RESOURCE_PACK_COLLECTOR.register((provider, context, consumer) -> {
            if (coordinator.state() != SplitState.READY) {
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

    public static void registerHostedPacks(List<SplitPack> packs) {
        for (SplitPack pack : packs) {
            AutoHostUtils.registerHostedFile(identifier(pack), pack.path());
        }
    }

    public static String fileUrl(
            ResourcePackDataProvider provider,
            PacketContext context,
            SplitPack pack
    ) {
        return provider.getFilePath(context, identifier(pack), pack.sha1());
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
                HOST_PREFIX + pack.namespace()
        );
    }
}
