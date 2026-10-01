package com.vortex.client.mixin.client;

import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * W-Tap: was der Client zuletzt an den Server gemeldet hat ("sprintet ja/nein").
 * LocalPlayer schickt START/STOP_SPRINTING nur, wenn sich sein Sprint-Zustand
 * gegenueber diesem Wert aendert.
 */
@Mixin(LocalPlayer.class)
public interface LocalPlayerSprintAccessor {
    @Accessor("wasSprinting")
    boolean vortex$getWasSprinting();

    @Accessor("wasSprinting")
    void vortex$setWasSprinting(boolean wert);
}
