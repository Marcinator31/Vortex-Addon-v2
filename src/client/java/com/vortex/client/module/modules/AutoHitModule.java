package com.vortex.client.module.modules;

import com.vortex.client.core.setting.BooleanSetting;
import com.vortex.client.core.setting.NumberSetting;
import com.vortex.client.module.Module;

/**
 * Auto Hit ("Killaura ohne Auto-Aim"): schlaegt automatisch zu, sobald
 *  - das Fadenkreuz auf der Hitbox eines Spielers liegt,
 *  - dieser Spieler in Reichweite ist, und
 *  - die Angriffs-Aufladung voll ist (voller Schaden).
 *
 * Es dreht NICHT automatisch -- man zielt selbst (ggf. mit dem Aimbot), und
 * dieses Modul uebernimmt nur das Timing des Zuschlagens. Dadurch trifft man
 * immer mit voll aufgeladenem Angriff, ohne manuell auf den Cooldown zu achten.
 */
public class AutoHitModule extends Module {

    // Ab welcher Aufladung geschlagen wird (1.0 = voll). Etwas unter 1.0 kann
    // minimal frueher zuschlagen, kostet aber Schaden -> Standard 1.0.
    public final NumberSetting minCharge =
            new NumberSetting("Min Charge", 1.0, 0.5, 1.0, 0.05);
    // Nur Spieler angreifen (keine Mobs/andere Entities)?
    public final BooleanSetting playersOnly =
            new BooleanSetting("Players Only", true);

    public AutoHitModule() {
        super("Auto Hit", Category.CHEATS);
        addSetting(minCharge);
        addSetting(playersOnly);
    }

    public double getMinCharge() { return minCharge.get(); }
    public boolean playersOnly() { return playersOnly.get(); }
}
