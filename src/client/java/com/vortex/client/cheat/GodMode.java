package com.vortex.client.cheat;

import com.vortex.client.module.ModuleManager;
import com.vortex.client.module.modules.GodModeModule;
import net.minecraft.client.Minecraft;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.damagesource.DamageSource;

/** God Mode (siehe GodModeModule) -- wird vom Einzelspieler-Server gefragt. */
public final class GodMode {

    private GodMode() {}

    public static boolean schuetzt(ServerPlayer sp, DamageSource quelle) {
        try {
            GodModeModule m = ModuleManager.INSTANCE.get(GodModeModule.class);
            if (m == null || !m.isEnabled() || sp == null) return false;
            Minecraft mc = Minecraft.getInstance();
            if (!mc.hasSingleplayerServer() || mc.player == null) return false;
            if (!sp.getUUID().equals(mc.player.getUUID())) return false;
            // /kill und Void wie bei Vanilla-Unverwundbarkeit durchlassen
            return quelle == null || !quelle.is(DamageTypeTags.BYPASSES_INVULNERABILITY);
        } catch (Throwable t) {
            return false;
        }
    }
}
