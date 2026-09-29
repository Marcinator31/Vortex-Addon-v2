package com.vortex.client.mixin.client;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import net.minecraft.client.Camera;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Boat Fly "No Clip": im Block trotzdem etwas sehen.
 *
 * Steckt die Kamera in einem Block, ueberspringt Minecraft zur Ersparnis alles
 * Verdeckte -- das Bild wird fast leer. Fuer Zuschauer schaltet es das ab;
 * genau diese Abfrage wird hier fuer die Dauer von No Clip auf "ja"
 * gestellt. Dieselbe Stelle wie Meteor fuer die Freecam in 26.2.
 */
//#if 26.2
@Mixin(Camera.class)
//#else
//$ @Mixin(net.minecraft.client.renderer.LevelRenderer.class)
//#endif
public abstract class NoClipCameraMixin {

    //#if 26.2
    @ModifyExpressionValue(method = "extractRenderState",
    //#else
    //$ // 1.21.11: renderLevel fragt isSpectator() fuer das Ausblenden von Waenden.
    //$ @ModifyExpressionValue(method = "renderLevel",
    //#endif
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/LocalPlayer;isSpectator()Z"),
            require = 0)
    private boolean vortex$sehen(boolean original) {
        try {
            var mc = net.minecraft.client.Minecraft.getInstance();
            if (mc.player != null && com.vortex.client.cheat.MoveCheats.noClipFuer(mc.player)) return true;
            // Ghost View "Wall Vision": die F5-Kamera steckt in einer Wand --
            // dann wie ein Zuschauer alles dahinter zeichnen.
            var gv = com.vortex.client.module.modules.GhostViewModule.aktiv();
            if (gv != null && gv.wallVision.get() && mc.options.getCameraType() != null
                    && !mc.options.getCameraType().isFirstPerson()) return true;
        } catch (Throwable ignored) {
        }
        return original;
    }
}
