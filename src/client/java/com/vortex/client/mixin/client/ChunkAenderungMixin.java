package com.vortex.client.mixin.client;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Chunk-Scanner (Block-ESP, Tunnel, Amethyst): merkt sich, in welchem Chunk sich
 * auf dem Client ein Block geaendert hat -- nur dieser wird neu durchsucht.
 */
@Mixin(LevelChunk.class)
public abstract class ChunkAenderungMixin {
    @Inject(method = "setBlockState", at = @At("RETURN"), require = 0)
    private void vortex$geaendert(BlockPos pos, BlockState state, int flags, CallbackInfoReturnable<BlockState> cir) {
        try {
            LevelChunk self = (LevelChunk) (Object) this;
            if (cir.getReturnValue() != null && self.getLevel().isClientSide()) {
                com.vortex.client.hud.ChunkScanner.geaendert(pos.getX(), pos.getZ());
            }
        } catch (Throwable ignored) {
        }
    }
}
