package com.vortex.client.mixin.client;

import com.vortex.client.module.modules.GhostViewModule;
import net.minecraft.client.Camera;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Ghost View "Camera Clip": die F5-Kamera geht durch Waende.
 *
 * getMaxZoom rechnet aus, wie weit die Kamera hinter den Spieler darf, und
 * kuerzt den Abstand, wenn ein Block dazwischen ist. Mit Camera Clip wird
 * der volle Abstand zurueckgegeben -- die Kamera bleibt, wo sie ist, auch
 * wenn sie dabei in einem Block steckt. Dieselbe Stelle wie Meteors
 * "Camera Tweaks" in 26.2.
 */
@Mixin(Camera.class)
public abstract class GhostCameraMixin {

    @Inject(method = "getMaxZoom", at = @At("HEAD"), cancellable = true, require = 0)
    private void vortex$durchWaende(float abstand, CallbackInfoReturnable<Float> cir) {
        try {
            GhostViewModule m = GhostViewModule.aktiv();
            if (m != null && m.cameraClip.get()) cir.setReturnValue(abstand);
        } catch (Throwable ignored) {
        }
    }
}
