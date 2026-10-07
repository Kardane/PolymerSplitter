package org.karn.polymersplitter.polymer;

import eu.pb4.polymer.autohost.api.AutoHostUtils;
import eu.pb4.polymer.autohost.api.ResourcePackDataProvider;
import eu.pb4.polymer.resourcepack.api.PolymerResourcePackUtils;
import xyz.nucleoid.packettweaker.PacketContext;
import net.minecraft.resources.Identifier;
import org.karn.polymersplitter.common.lifecycle.SplitCoordinator;
import org.karn.polymersplitter.common.lifecycle.SplitState;
import org.karn.polymersplitter.common.pack.HostedPackStore;
import org.karn.polymersplitter.common.pack.SplitPack;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
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

    }

    public static void registerHostedPacks(
            Path outputRoot,
            List<SplitPack> packs
    ) {
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
                HOST_PREFIX + pack.namespace() + "/" + pack.sha1()
        );
    }
}
