package org.karn.polymersplitter.polymer;

import eu.pb4.polymer.resourcepack.api.PolymerResourcePackUtils;
import net.minecraft.server.level.ServerPlayer;
import org.karn.polymersplitter.common.hosting.PackPushResult;
import org.karn.polymersplitter.common.lifecycle.SplitGeneration;
import org.karn.polymersplitter.common.pack.SplitPack;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

final class SplitDelivery {
    static final String HOST_NAMESPACE = "polymersplitter";
    static final String HOST_PREFIX = "packs/";

    private SplitDelivery() {
    }

    static List<SplitPack> selectPacks(SplitGeneration generation, String namespace) {
        if (namespace == null) {
            return generation.packs();
        }

        SplitPack selected = generation.byNamespace().get(namespace);
        if (selected == null) {
            throw new IllegalArgumentException("Unknown split namespace: " + namespace);
        }
        return List.of(selected);
    }

    static String primaryNamespace(List<SplitPack> packs) {
        for (SplitPack pack : packs) {
            if ("minecraft".equals(pack.namespace())) {
                return pack.namespace();
            }
        }
        return packs.get(0).namespace();
    }

    static UUID effectiveUuid(SplitPack pack, String primaryNamespace) {
        if (pack.namespace().equals(primaryNamespace)) {
            return PolymerResourcePackUtils.getMainUuid();
        }
        return pack.uuid();
    }

    static PackPushResult push(
            Collection<ServerPlayer> players,
            List<SplitPack> packs,
            String primaryNamespace,
            PlayerPackSender sender
    ) {
        int readyPlayers = 0;
        int packetsSent = 0;

        for (ServerPlayer player : players) {
            int sent = sender.send(player, packs, primaryNamespace);
            if (sent <= 0) {
                continue;
            }

            readyPlayers++;
            packetsSent += sent;
        }

        return new PackPushResult(
                players.size(),
                readyPlayers,
                packs.size(),
                packetsSent
        );
    }

    @FunctionalInterface
    interface PlayerPackSender {
        int send(ServerPlayer player, List<SplitPack> packs, String primaryNamespace);
    }
}
