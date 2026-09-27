package com.vortex.client.mixin.client;

import net.minecraft.world.entity.projectile.FishingHook;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Auto Fish: "biting" setzt der Client aus den Daten, die der Server beim Biss schickt. */
@Mixin(FishingHook.class)
public interface FishingHookAccessor {
    @Accessor("biting")
    boolean vortex$isBiting();
}
