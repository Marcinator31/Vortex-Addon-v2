package com.vortex.client.mixin.client;

import java.util.Optional;
import net.minecraft.network.protocol.game.ClientboundExplodePacket;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Anti Knockback bei Explosionen. Feld "playerKnockback" (Optional&lt;Vec3&gt;)
 * wie in Meteor fuer 26.2.
 */
@Mixin(ClientboundExplodePacket.class)
public interface ExplodePacketAccessor {
    @Mutable
    @Accessor("playerKnockback")
    void vortex$setKnockback(Optional<Vec3> knockback);
}
