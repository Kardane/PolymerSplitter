package org.karn.polymersplitter.polymer;

import eu.pb4.polymer.autohost.api.AutoHostUtils;
import eu.pb4.polymer.autohost.api.ResourcePackDataProvider;
import net.fabricmc.fabric.api.networking.v1.context.PacketContext;
import net.minecraft.resources.Identifier;
import org.karn.polymersplitter.common.lifecycle.SplitRegistry;
import org.karn.polymersplitter.common.pack.SplitPack;

import java.util.List;

public final class PolymerAutoHostBridge {
    private static final String HOST_NAMESPACE = "polymersplitter";
    private static final String HOST_PREFIX = "packs/";

    private PolymerAutoHostBridge() {
    }

    public static void registerPackCollector(SplitRegistry registry) {
        AutoHostUtils.SEND_RESOURCE_PACK_COLLECTOR.register((provider, context, consumer) -> {
            for (SplitPack pack : registry.currentPacks()) {
                consumer.accept(provider.createProperties(
                        context,
                        pack.uuid(),
                        identifier(pack),
                        pack.sha1()
                ));
            }
        });
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

    private static Identifier identifier(SplitPack pack) {
        return Identifier.fromNamespaceAndPath(
                HOST_NAMESPACE,
                HOST_PREFIX + pack.namespace()
        );
    }
}
