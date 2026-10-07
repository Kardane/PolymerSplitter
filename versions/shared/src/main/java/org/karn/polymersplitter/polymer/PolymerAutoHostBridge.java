package org.karn.polymersplitter.polymer;

import net.minecraft.server.level.ServerPlayer;
import org.karn.polymersplitter.common.hosting.HostingStatus;
import org.karn.polymersplitter.common.hosting.PackPushResult;
import org.karn.polymersplitter.common.lifecycle.SplitCoordinator;
import org.karn.polymersplitter.common.lifecycle.SplitGeneration;
import org.karn.polymersplitter.common.pack.HostedPackStore;
import org.karn.polymersplitter.common.pack.SplitPack;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Map;

public final class PolymerAutoHostBridge {
    private PolymerAutoHostBridge() {
    }

    public static HostingStatus currentHostingStatus() {
        return HostingPolicy.evaluate(AutoHostAccess.settings());
    }

    public static void registerPackCollector(SplitCoordinator coordinator) {
        AutoHostAccess.registerPackCollector(coordinator);
        AutoHostAccess.registerReadiness(coordinator);
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
            hostedFiles = HostedPackStore.resolvePublished(outputRoot, packs);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to resolve immutable hosted packs", e);
        }

        for (SplitPack pack : packs) {
            Path hostedPath = hostedFiles.get(pack.sha1());
            if (hostedPath == null) {
                throw new IllegalStateException(
                        "Missing hosted blob for namespace " + pack.namespace()
                );
            }

            AutoHostAccess.registerHostedFile(pack, hostedPath);
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

        List<SplitPack> selectedPacks = SplitDelivery.selectPacks(generation, namespace);
        String primaryNamespace = SplitDelivery.primaryNamespace(generation.packs());

        return SplitDelivery.push(
                players,
                selectedPacks,
                primaryNamespace,
                AutoHostAccess::sendPlayer
        );
    }

    public static void clearHostedRegistrations() {
        AutoHostAccess.clearHostedRegistrations();
    }
}
