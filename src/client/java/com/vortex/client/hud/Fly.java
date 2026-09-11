package com.vortex.client.hud;

import com.vortex.client.module.Module;
import com.vortex.client.module.ModuleManager;
import com.vortex.client.module.modules.FlyModule;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.player.Abilities;

/**
 * Logik fuer das Fly-Modul.
 *
 * Es werden nur die Flug-Faehigkeiten des Spielers gesetzt -- die eigentliche
 * Flugbewegung uebernimmt Minecraft danach selbst, exakt wie im Kreativmodus.
 * Beim Ausschalten werden die Werte wieder zurueckgesetzt.
 *
 * Im Kreativ-/Spectator-Modus wird nichts angefasst: dort darf man ohnehin
 * fliegen, und ein Zuruecksetzen wuerde das kaputt machen.
 */
public final class Fly {

    // Merker, damit beim Ausschalten genau einmal zurueckgesetzt wird.
    private static boolean wasActive = false;

    private Fly() {}

    public static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            long pvpT0 = System.nanoTime();
            try {
            if (client.player == null) {
                wasActive = false;
                return;
            }

            LocalPlayer self = client.player;
            Abilities abilities = self.getAbilities();

            // Kreativ/Spectator: Finger weg, da fliegt man sowieso.
            if (abilities.instabuild || self.isSpectator()) {
                wasActive = false;
                return;
            }

            FlyModule mod = (FlyModule) find(FlyModule.class);
            boolean active = mod != null && mod.isEnabled();

            if (active) {
                abilities.mayfly = true;
                abilities.flying = true;
                abilities.setFlyingSpeed(mod.flySpeed());
                wasActive = true;
            } else if (wasActive) {
                // Genau einmal beim Ausschalten aufraeumen.
                boolean keep = mod != null && mod.keepAllowFlying.get();
                abilities.flying = false;
                abilities.mayfly = keep;
                abilities.setFlyingSpeed(0.05f);
                wasActive = false;
            }
                    } finally {
                com.vortex.client.core.Profiler.record("Fly",
                        System.nanoTime() - pvpT0);
            }
        });
    }

    private static Module find(Class<? extends Module> type) {
        // Konstante Laufzeit statt die ganze Liste zu durchlaufen.
        return ModuleManager.INSTANCE.get(type);
    }
}
