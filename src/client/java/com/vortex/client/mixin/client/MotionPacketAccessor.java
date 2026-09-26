package com.vortex.client.mixin.client;

import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Anti Knockback: die Bewegung im Rueckstoss-Paket abschwaechen, bevor
 * Minecraft sie anwendet. Feldname "movement" wie in Meteor fuer 26.2.
 */
@Mixin(ClientboundSetEntityMotionPacket.class)
public interface MotionPacketAccessor {
    @Mutable
    @Accessor("movement")
    void vortex$setMovement(Vec3 movement);
}
