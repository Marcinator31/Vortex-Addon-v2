package com.vortex.client.module.modules;

import com.vortex.client.core.setting.BooleanSetting;
import com.vortex.client.core.setting.ModeSetting;
import com.vortex.client.core.setting.NumberSetting;
import com.vortex.client.module.Module;

/**
 * Weniger oder gar kein Rueckstoss, wenn du getroffen wirst.
 *
 * Der Server schickt nach einem Treffer die neue Geschwindigkeit. Hier wird
 * nur der Anteil gekuerzt, der vom Treffer kommt -- deine eigene Bewegung
 * bleibt erhalten. 0 % = kein Rueckstoss, 100 % = wie immer.
 *
 * Hohes Bann-Risiko: fast jeder Anti-Cheat prueft genau das.
 */
public class AntiKnockbackModule extends Module {

    public final NumberSetting horizontal = new NumberSetting("Horizontal %", 0, 0, 100, 5);
    public final NumberSetting vertical = new NumberSetting("Vertical %", 0, 0, 100, 5);
    public final BooleanSetting explosions = new BooleanSetting("Explosions", true);

    public AntiKnockbackModule() {
        super("Anti Knockback", Category.CHEATS);
        addSetting(horizontal);
        addSetting(vertical);
        addSetting(explosions);
    }
}
