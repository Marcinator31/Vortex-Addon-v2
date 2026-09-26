package com.vortex.client.mixin.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Inventory Move: welche Taste eine Belegung gerade hat. Wie in Meteor fuer 26.2. */
@Mixin(KeyMapping.class)
public interface KeyMappingKeyAccessor {
    @Accessor("key")
    InputConstants.Key vortex$getKey();
}
