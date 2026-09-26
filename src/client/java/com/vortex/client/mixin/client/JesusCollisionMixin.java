package com.vortex.client.mixin.client;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockCollisions;
import net.minecraft.world.level.CollisionGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Jesus (Modus "Solid"): Wasser -- und auf Wunsch Lava -- unter den Fuessen
 * bekommt die Kollisionsform eines vollen Blocks. Man steht und laeuft darauf
 * wie auf Stein.
 *
 * Ziel und Signatur exakt wie in Meteor fuer 26.2 (BlockCollisionsMixin).
 * WrapOperation stammt aus MixinExtras, das Fabric Loader mitbringt.
 * require = 0: findet sich das Ziel nicht, bleibt nur dieser Modus
 * wirkungslos, das Spiel startet trotzdem.
 */
@Mixin(BlockCollisions.class)
public abstract class JesusCollisionMixin {

    @WrapOperation(method = "computeNext",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/phys/shapes/CollisionContext;getCollisionShape(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/CollisionGetter;Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/phys/shapes/VoxelShape;"),
            require = 0)
    private VoxelShape vortex$jesus(CollisionContext instance, BlockState state, CollisionGetter view,
                                    BlockPos pos, Operation<VoxelShape> original) {
        VoxelShape form = original.call(instance, state, view, pos);
        try {
            if (view == Minecraft.getInstance().level
                    && com.vortex.client.cheat.MoveCheats.jesusFest(state, pos)) {
                return Shapes.block();
            }
        } catch (Throwable pvpErr) {
            com.vortex.client.core.Errors.report("JesusCollisionMixin", pvpErr);
        }
        return form;
    }
}
