package com.vortex.client.mixin.client;

import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Boat Fly "No Clip": im Block ist man nicht "in der Wand".
 *
 * Auf deiner Seite verschwindet damit die Block-Textur, die sonst den ganzen
 * Bildschirm fuellt (das "schwarze Bild"), und im Einzelspieler erstickt man
 * nicht, waehrend das Boot durch Bloecke fliegt. Gilt nur fuer dich, nur im
 * Boot, nur mit No Clip. Entity.isInWall wie in Meteor (Freecam) fuer 26.2.
 */
@Mixin(Entity.class)
public abstract class NoClipWallMixin {

    @Inject(method = "isInWall", at = @At("HEAD"), cancellable = true, require = 0)
    private void vortex$keineWand(CallbackInfoReturnable<Boolean> cir) {
        try {
            if (com.vortex.client.cheat.MoveCheats.noClipFuer((Entity) (Object) this)) cir.setReturnValue(false);
        } catch (Throwable ignored) {
            // Wird fuer jedes Wesen in jedem Tick gefragt -- nie hier abstuerzen.
        }
    }
}
