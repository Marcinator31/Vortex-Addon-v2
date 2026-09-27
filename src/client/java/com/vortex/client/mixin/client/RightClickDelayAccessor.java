package com.vortex.client.mixin.client;

import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Fast Anchor: Minecrafts Rechtsklick-Wiederholung kurz anhalten. */
@Mixin(Minecraft.class)
public interface RightClickDelayAccessor {
    @Accessor("rightClickDelay")
    void vortex$setRightClickDelay(int wert);
}
