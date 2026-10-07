package org.karn.polymersplitter.mixin;

import eu.pb4.polymer.autohost.impl.providers.AbstractProvider;
import net.fabricmc.fabric.api.networking.v1.context.PacketContext;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Collection;

@Mixin(value = AbstractProvider.class, remap = false)
public abstract class AbstractProviderMixin {
    @Inject(method = "getProperties", at = @At("RETURN"), remap = false)
    private void polymersplitter$suppressMainPack(
            PacketContext context,
            CallbackInfoReturnable<Collection<MinecraftServer.ServerResourcePackInfo>> cir
    ) {
        MainPackSuppression.suppressOriginalMainPackIfReady(cir.getReturnValue());
    }
}
