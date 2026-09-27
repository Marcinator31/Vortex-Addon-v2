package com.vortex.client.mixin.client;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * God Mode -- greift im Server-Code, der im Einzelspieler in DEINEM Minecraft
 * laeuft. Auf fremden Servern laeuft deren Code; dort passiert hier nichts.
 */
@Mixin(ServerPlayer.class)
public abstract class GodModeServerMixin {

    @Inject(method = "hurtServer", at = @At("HEAD"), cancellable = true, require = 0)
    private void vortex$godMode(ServerLevel level, DamageSource source, float amount, CallbackInfoReturnable<Boolean> cir) {
        if (com.vortex.client.cheat.GodMode.schuetzt((ServerPlayer) (Object) this, source)) cir.setReturnValue(false);
    }
}
