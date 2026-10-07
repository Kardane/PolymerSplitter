package org.karn.polymersplitter.polymer;

import eu.pb4.polymer.autohost.api.AutoHostUtils;
import eu.pb4.polymer.resourcepack.api.PolymerResourcePackUtils;
import net.minecraft.server.MinecraftServer;
import org.karn.polymersplitter.PolymerSplitter;

import java.util.Collection;
import java.util.Iterator;

public final class MainPackSuppression {
    private MainPackSuppression() {
    }

    public static void suppressOriginalMainPackIfReady(
            Collection<MinecraftServer.ServerResourcePackInfo> packs
    ) {
        if (!PolymerSplitter.shouldUseSplitPacks()) {
            return;
        }

        String defaultPath = AutoHostUtils.getPathFromId(AutoHostUtils.DEFAULT_PACK_ID);

        Iterator<MinecraftServer.ServerResourcePackInfo> iterator = packs.iterator();
        while (iterator.hasNext()) {
            MinecraftServer.ServerResourcePackInfo pack = iterator.next();
            if (pack.id().equals(PolymerResourcePackUtils.getMainUuid())
                    && pack.url().contains(defaultPath)) {
                iterator.remove();
                return;
            }
        }
    }
}
