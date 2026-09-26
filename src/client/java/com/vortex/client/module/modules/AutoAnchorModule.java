package com.vortex.client.module.modules;

import com.vortex.client.core.setting.BooleanSetting;
import com.vortex.client.core.setting.ModeSetting;
import com.vortex.client.core.setting.NumberSetting;
import com.vortex.client.module.Module;

/**
 * Seelenanker-PvP: setzt einen Anker neben den Gegner, laedt ihn mit
 * Glowstone und bringt ihn zur Explosion.
 *
 * Braucht Seelenanker und Glowstone in der Hotbar. Funktioniert nur in der
 * Oberwelt und im End -- im Nether ist der Anker ein Spawnpunkt und
 * explodiert nicht. Freunde werden nie angegriffen. Der Anker trifft auch
 * dich: Mindestabstand und Lebensgrenze schuetzen davor.
 *
 * Extremes Bann-Risiko.
 */
public class AutoAnchorModule extends Module {

    public final NumberSetting range = new NumberSetting("Range", 4.5, 2.0, 6.0, 0.5);
    public final NumberSetting delay = new NumberSetting("Delay (ticks)", 3, 1, 10, 1);
    public final NumberSetting minSelfDistance = new NumberSetting("Min Self Distance", 3.0, 0.0, 6.0, 0.5);
    public final NumberSetting safetyHealth = new NumberSetting("Safety Health", 8, 0, 20, 1);
    public final BooleanSetting swing = new BooleanSetting("Swing", true);

    public AutoAnchorModule() {
        super("Auto Anchor", Category.CHEATS);
        addSetting(range);
        addSetting(delay);
        addSetting(minSelfDistance);
        addSetting(safetyHealth);
        addSetting(swing);
    }
}
