package com.vortex.client.mixin.client;

import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * W-Tap "Legit": nach einem Sprint-Schlag ein paar Ticks nicht sprinten.
 * Ende von aiStep = nach Minecrafts eigener Sprint-Entscheidung, aber VOR dem
 * Melden an den Server (LocalPlayer.tick: erst aiStep, dann
 * sendIsSprintingIfNeeded). So entsteht genau das STOP/START eines echten
 * W-Taps, ohne die W-Taste anzufassen.
 */
@Mixin(LocalPlayer.class)
public abstract class WTapSprintMixin {

    @Inject(method = "aiStep", at = @At("TAIL"))
    private void vortex$wTapPause(CallbackInfo ci) {
        try {
            com.vortex.client.cheat.ExtraCheats.wTapNachAiStep((LocalPlayer) (Object) this);
        } catch (Throwable t) {
            com.vortex.client.core.Errors.report("WTap", t);
        }
    }
}
