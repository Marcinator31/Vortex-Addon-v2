package com.vortex.client.mixin.client;

//#if 26.2
import net.minecraft.client.renderer.block.BlockAndTintGetter;
//#endif
import net.minecraft.client.renderer.block.ModelBlockRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Xray, Teil 2: Bloecke AUS der Liste zeichnen jede Seite -- auch die, die an
 * (jetzt unsichtbaren) Stein grenzt. Ohne das saehe man von einem Erz nur die
 * Flaechen, die zufaellig an Luft liegen.
 *
 * Signatur gegen 26.1.2 geprueft. Passt sie in einer anderen Fassung nicht,
 * greift require = 0: Xray laeuft dann ohne diesen Teil.
 */
@Mixin(ModelBlockRenderer.class)
public abstract class XrayFaceMixin {

    //#if 26.2
    @Inject(method = "shouldRenderFace(Lnet/minecraft/client/renderer/block/BlockAndTintGetter;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/core/Direction;Lnet/minecraft/core/BlockPos;)Z",
            at = @At("HEAD"), cancellable = true, require = 0)
    private void vortex$xrayFace(BlockAndTintGetter level, BlockState state, Direction dir, BlockPos pos,
                                 CallbackInfoReturnable<Boolean> cir) {
    //#else
    //$ @Inject(method = "shouldRenderFace(Lnet/minecraft/world/level/BlockAndTintGetter;Lnet/minecraft/world/level/block/state/BlockState;ZLnet/minecraft/core/Direction;Lnet/minecraft/core/BlockPos;)Z",
    //$         at = @At("HEAD"), cancellable = true, require = 0)
    //$ private static void vortex$xrayFace(net.minecraft.world.level.BlockAndTintGetter level, BlockState state, boolean cull,
    //$                                     Direction dir, BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
    //#endif
        try {
            if (com.vortex.client.cheat.Xray.zeigeAlles(state)) cir.setReturnValue(true);
        } catch (Throwable ignored) {
        }
    }
}
