package com.vortex.client.mixin.client;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * No Slow: beim Essen, Trinken, Blocken und Bogenspannen nicht abbremsen.
 *
 * Minecraft fragt in modifyInput, ob gerade ein Gegenstand benutzt wird, und
 * verlangsamt dann die Eingabe. Diese eine Abfrage antwortet mit "nein".
 * Stelle exakt wie in Meteor fuer 26.2 (LocalPlayerMixin).
 */
@Mixin(LocalPlayer.class)
public abstract class NoSlowMixin {

    @ModifyExpressionValue(method = "modifyInput",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/LocalPlayer;isUsingItem()Z"),
            require = 0)
    private boolean vortex$noSlow(boolean benutzt) {
        try {
            if (com.vortex.client.cheat.MoveCheats.noSlow()) return false;
        } catch (Throwable pvpErr) {
            com.vortex.client.core.Errors.report("NoSlowMixin", pvpErr);
        }
        return benutzt;
    }
}
