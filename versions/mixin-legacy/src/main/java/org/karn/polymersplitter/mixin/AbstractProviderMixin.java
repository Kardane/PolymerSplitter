package org.karn.polymersplitter.mixin;

import eu.pb4.polymer.autohost.api.AutoHostUtils;
import eu.pb4.polymer.autohost.impl.providers.AbstractProvider;
import eu.pb4.polymer.resourcepack.api.PolymerResourcePackUtils;
import net.minecraft.network.Connection;
import net.minecraft.server.MinecraftServer;
import org.karn.polymersplitter.PolymerSplitter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Collection;
import java.util.Iterator;

@Mixin(value = AbstractProvider.class, remap = false)
public abstract class AbstractProviderMixin {
    @Inject(method = "getProperties", at = @At("RETURN"), remap = false)
    private void polymersplitter$suppressMainPack(
            Connection connection,
            CallbackInfoReturnable<Collection<MinecraftServer.ServerResourcePackInfo>> cir
    ) {
        if (!PolymerSplitter.shouldUseSplitPacks()) {
            return;
        }

        removeOriginalMainPack(cir.getReturnValue());
    }

    private static void removeOriginalMainPack(Collection<MinecraftServer.ServerResourcePackInfo> packs) {
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
