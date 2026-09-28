package com.vortex.client.mixin.client;

import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Xray, Teil 1: Bloecke ausserhalb der Liste melden "unsichtbar" und werden
 * beim Chunk-Bau ausgelassen (wie Barrieren). Nur die Darstellung aendert
 * sich -- Kollision und Abbauzeit bleiben.
 */
@Mixin(BlockBehaviour.BlockStateBase.class)
public abstract class XrayShapeMixin {

    @Inject(method = "getRenderShape", at = @At("HEAD"), cancellable = true, require = 0)
    private void vortex$xrayShape(CallbackInfoReturnable<RenderShape> cir) {
        try {
            if (com.vortex.client.cheat.Xray.versteckt((BlockState) (Object) this)) {
                cir.setReturnValue(RenderShape.INVISIBLE);
            }
        } catch (Throwable ignored) {
        }
    }
}
