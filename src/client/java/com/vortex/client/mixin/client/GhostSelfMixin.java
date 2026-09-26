package com.vortex.client.mixin.client;

import com.vortex.client.module.modules.GhostViewModule;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Ghost View: den EIGENEN Spieler in F5 wie unsichtbar zeichnen.
 *
 * Minecraft merkt sich beim Vorbereiten jedes Bildes zwei Werte:
 *   isInvisible          -> der Koerper wird nicht normal gezeichnet
 *   isInvisibleToPlayer  -> ... und auch nicht durchscheinend
 * Genau so entsteht der Unsichtbarkeits-Look (beide an) und der
 * durchscheinende Look, den Teammitglieder sehen (nur der erste an).
 * Hier werden sie fuer dich selbst gesetzt -- nur auf deinem Bildschirm.
 *
 * Stelle: Ende von LivingEntityRenderer.extractRenderState (belegt im
 * 26.1.2-Code: dort werden beide Felder gesetzt).
 */
@Mixin(LivingEntityRenderer.class)
public abstract class GhostSelfMixin {

    @Inject(method = "extractRenderState(Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/client/renderer/entity/state/LivingEntityRenderState;F)V",
            at = @At("TAIL"), require = 0)
    private void vortex$geist(LivingEntity entity, LivingEntityRenderState state, float partialTick,
                              CallbackInfo ci) {
        try {
            Minecraft mc = Minecraft.getInstance();
            if (entity != mc.player) return;
            GhostViewModule m = GhostViewModule.aktiv();
            if (m == null) return;
            state.isInvisible = true;
            state.isInvisibleToPlayer = m.mode.getIndex() == 0;
        } catch (Throwable ignored) {
        }
    }
}
