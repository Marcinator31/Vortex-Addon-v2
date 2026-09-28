package com.vortex.client.mixin.client;

import com.vortex.client.bot.NetheriteFarmer;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Bots duerfen auch graben, wenn das Fenster im Hintergrund ist.
 *
 * Minecraft baut nur ab, wenn die Maus im Fenster gefangen ist
 * (continueAttack(screen == null && keyAttack.isDown() && mouseGrabbed)).
 * Wechselt man per Alt-Tab weg, wird die Maus freigegeben -- der Farmer lief
 * dann weiter gegen die Wand, ohne einen Block abzubauen.
 *
 * Nur wenn ein Bot die Angriffstaste selbst haelt und kein Menue offen ist.
 */
@Mixin(Minecraft.class)
public abstract class BotAttackMixin {

    @ModifyVariable(method = "continueAttack", at = @At("HEAD"), argsOnly = true, require = 0)
    private boolean vortex$botAttack(boolean down) {
        if (down) return true;
        try {
            return NetheriteFarmer.botHaeltAngriff();
        } catch (Throwable t) {
            return false;
        }
    }
}
