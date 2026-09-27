package com.vortex.client.mixin.client;

import com.vortex.client.module.ModuleManager;
import com.vortex.client.module.modules.ReachModule;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Reach: groessere Ziel-Reichweite -- nur fuer den eigenen Spieler auf dem
 * Client (LocalPlayer.raycastHitResult liest genau diese zwei Werte).
 */
@Mixin(Player.class)
public abstract class ReachMixin {

    private boolean vortex$ich() {
        return (Object) this instanceof LocalPlayer;
    }

    @Inject(method = "entityInteractionRange", at = @At("RETURN"), cancellable = true)
    private void vortex$entityReach(CallbackInfoReturnable<Double> cir) {
        if (!vortex$ich()) return;
        ReachModule m = ModuleManager.INSTANCE.get(ReachModule.class);
        if (m != null && m.isEnabled()) cir.setReturnValue(Math.max(cir.getReturnValue(), m.entityReach.get()));
    }

    @Inject(method = "blockInteractionRange", at = @At("RETURN"), cancellable = true)
    private void vortex$blockReach(CallbackInfoReturnable<Double> cir) {
        if (!vortex$ich()) return;
        ReachModule m = ModuleManager.INSTANCE.get(ReachModule.class);
        if (m != null && m.isEnabled()) cir.setReturnValue(Math.max(cir.getReturnValue(), m.blockReach.get()));
    }
}
