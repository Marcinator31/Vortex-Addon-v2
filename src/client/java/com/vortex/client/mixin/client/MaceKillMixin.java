package com.vortex.client.mixin.client;

import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Mace Kill: direkt VOR dem Angriffspaket die Hoehe melden. HEAD liegt vor
 * ensureHasSentCarriedItem und dem ServerboundAttackPacket -- der Server
 * verarbeitet die Pakete in genau dieser Reihenfolge.
 */
@Mixin(MultiPlayerGameMode.class)
public abstract class MaceKillMixin {

    /** Vanilla: schickt den Slot-Wechsel, falls noetig (fuer Attribute Swap). */
    @org.spongepowered.asm.mixin.Shadow
    private void ensureHasSentCarriedItem() {}

    @Inject(method = "attack", at = @At("HEAD"))
    private void vortex$maceKill(Player player, Entity target, CallbackInfo ci) {
        // Reihenfolge wichtig: erst die Waffe nehmen (Attribute Swap), dann
        // sehen Mace Kill und Criticals die richtige Waffe in der Hand.
        try {
            com.vortex.client.cheat.AttributeSwap.vorDemSchlag(player, target);
        } catch (Throwable t) {
            com.vortex.client.core.Errors.report("AttributeSwap", t);
        }
        try {
            // W-Tap vor Criticals: braucht Criticals "nicht sprinten", gewinnt der Krit.
            com.vortex.client.cheat.ExtraCheats.wTapVorher(player, target);
        } catch (Throwable t) {
            com.vortex.client.core.Errors.report("WTap", t);
        }
        try {
            com.vortex.client.cheat.MaceKill.vorDemSchlag(player, target);
        } catch (Throwable t) {
            com.vortex.client.core.Errors.report("MaceKill", t);
        }
        try {
            com.vortex.client.cheat.Criticals.vorDemSchlag(player, target);
        } catch (Throwable t) {
            com.vortex.client.core.Errors.report("Criticals", t);
        }
    }

    /** Criticals: nach dem Schlag wieder sprinten (falls vorher gestoppt). */
    @Inject(method = "attack", at = @At("TAIL"))
    private void vortex$nachSchlag(Player player, Entity target, CallbackInfo ci) {
        try {
            // Mace Kill "Auto Mace": wieder das vorige Item in die Hand (vor Attribute Swap, das ggf. davor gewechselt hat)
            if (com.vortex.client.cheat.MaceKill.nachDemSchlag(player)) ensureHasSentCarriedItem();
        } catch (Throwable t) {
            com.vortex.client.core.Errors.report("MaceKill", t);
        }
        try {
            // Attribute Swap: Slot zurueck und SOFORT melden (selber Paket-Stapel)
            if (com.vortex.client.cheat.AttributeSwap.nachDemSchlag(player)) ensureHasSentCarriedItem();
        } catch (Throwable t) {
            com.vortex.client.core.Errors.report("AttributeSwap", t);
        }
        try {
            com.vortex.client.cheat.ExtraCheats.wTapNachher(player, target);
        } catch (Throwable t) {
            com.vortex.client.core.Errors.report("WTap", t);
        }
        try {
            com.vortex.client.cheat.Criticals.nachDemSchlag(player);
        } catch (Throwable t) {
            com.vortex.client.core.Errors.report("Criticals", t);
        }
    }
}
