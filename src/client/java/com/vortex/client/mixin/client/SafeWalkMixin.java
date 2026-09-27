package com.vortex.client.mixin.client;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Safe Walk / Parkour: die Kanten-Bremse, die Minecraft beim Schleichen
 * benutzt (maybeBackOffFromEdge fragt isStayingOnGroundSurface), auch ohne
 * Schleichen einschalten. Nur fuer den eigenen Spieler.
 */
@Mixin(Player.class)
public abstract class SafeWalkMixin {

    @Inject(method = "isStayingOnGroundSurface", at = @At("RETURN"), cancellable = true, require = 0)
    private void vortex$safeWalk(CallbackInfoReturnable<Boolean> cir) {
        try {
            if (cir.getReturnValueZ()) return;
            Minecraft mc = Minecraft.getInstance();
            if (mc == null || (Object) this != mc.player) return;
            if (com.vortex.client.cheat.ExtraCheats.kantenBremse(mc)) cir.setReturnValue(true);
        } catch (Throwable pvpErr) {
            com.vortex.client.core.Errors.report("SafeWalkMixin", pvpErr);
        }
    }
}
