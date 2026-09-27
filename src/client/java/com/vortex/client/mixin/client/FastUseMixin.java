package com.vortex.client.mixin.client;

import com.vortex.client.module.modules.FastUseModule;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Fast Use: die Wartezeit nach einem Rechtsklick verkuerzen.
 *
 * Minecraft.startUseItem setzt als Erstes rightClickDelay = 4 (belegt im
 * 26.1.2-Code; Meteor greift in 26.2 dasselbe Feld an). Nach dem Aufruf --
 * an jedem Ausgang -- wird es hier auf den eingestellten Wert gesetzt, wenn
 * der Gegenstand in der Hand dazu passt.
 */
@Mixin(Minecraft.class)
public abstract class FastUseMixin {

    @Shadow
    private int rightClickDelay;

    @Inject(method = "startUseItem", at = @At("RETURN"), require = 0)
    private void vortex$schneller(CallbackInfo ci) {
        try {
            FastUseModule m = com.vortex.client.module.ModuleManager.INSTANCE.get(FastUseModule.class);
            if (m == null || !m.isEnabled()) return;
            LocalPlayer p = ((Minecraft) (Object) this).player;
            if (p == null) return;
            int w = m.wartezeit(p.getMainHandItem());
            if (w < 0) w = m.wartezeit(p.getOffhandItem());
            if (w >= 0 && w < rightClickDelay) rightClickDelay = w;
        } catch (Throwable ignored) {
        }
    }
}
