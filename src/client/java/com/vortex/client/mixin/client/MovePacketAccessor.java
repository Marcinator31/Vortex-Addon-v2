package com.vortex.client.mixin.client;

import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Anti Hunger: das "am Boden"-Flag im Bewegungspaket. Wie in Meteor fuer 26.2. */
@Mixin(ServerboundMovePlayerPacket.class)
public interface MovePacketAccessor {
    @Mutable
    @Accessor("onGround")
    void vortex$setOnGround(boolean onGround);
}
