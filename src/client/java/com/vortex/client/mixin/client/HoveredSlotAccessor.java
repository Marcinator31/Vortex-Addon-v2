package com.vortex.client.mixin.client;

import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.Slot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Der Platz unter dem Mauszeiger in einem Inventar (fuer Auto Totem "Hover").
 * Das Feld heisst in 26.1.2 und in Meteors 26.2-Code "hoveredSlot".
 */
@Mixin(AbstractContainerScreen.class)
public interface HoveredSlotAccessor {
    @Accessor("hoveredSlot")
    Slot vortex$hoveredSlot();
}
