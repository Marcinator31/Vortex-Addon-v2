package com.vortex.client.mixin.client;

import com.vortex.client.module.ModuleManager;
import com.vortex.client.module.modules.SpeedMineModule;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Speed Mine. Laeuft am Anfang jedes Abbau-Ticks:
 *  - Pause nach dem letzten Block (destroyDelay, 5 Ticks) auf 0.
 *  - Fortschritt >= "Break At" -> auf 1 setzen. Im selben Tick meldet
 *    Minecraft den Block dann als abgebaut (STOP_DESTROY_BLOCK). Der Server
 *    nimmt ihn ab 70 % seines eigenen Fortschritts an.
 */
@Mixin(MultiPlayerGameMode.class)
public abstract class SpeedMineMixin {

    @Shadow private float destroyProgress;
    @Shadow private int destroyDelay;

    @Inject(method = "continueDestroyBlock", at = @At("HEAD"), require = 0)
    private void vortex$speedMine(BlockPos pos, Direction dir, CallbackInfoReturnable<Boolean> cir) {
        try {
            SpeedMineModule m = ModuleManager.INSTANCE.get(SpeedMineModule.class);
            if (m == null || !m.isEnabled()) return;
            if (m.noDelay.get() && destroyDelay > 0) destroyDelay = 0;
            float grenze = m.breakAt.getFloat();
            if (grenze < 1.0f && destroyProgress >= grenze && destroyProgress < 1.0f) destroyProgress = 1.0f;
        } catch (Throwable pvpErr) {
            com.vortex.client.core.Errors.report("SpeedMineMixin", pvpErr);
        }
    }
}
