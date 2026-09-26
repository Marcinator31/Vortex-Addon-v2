package com.vortex.client.module.modules;

import com.vortex.client.core.setting.BooleanSetting;
import com.vortex.client.core.setting.ModeSetting;
import com.vortex.client.core.setting.NumberSetting;
import com.vortex.client.module.Module;

/**
 * Legt automatisch die beste Ruestung aus dem Inventar an.
 *
 * Bewertet werden Schutzwert, Haerte und Verzauberungen (Schutz, Explosions-,
 * Feuer- und Projektilschutz, Haltbarkeit, Reparatur). Pro Durchgang wird
 * hoechstens ein Teil getauscht, damit der Server nicht mit Klicks
 * ueberschuettet wird.
 */
public class AutoArmorModule extends Module {

    public final NumberSetting delay = new NumberSetting("Delay (ticks)", 3, 1, 20, 1);
    public final BooleanSetting preferElytra = new BooleanSetting("Prefer Elytra", false);
    public final BooleanSetting skipBinding = new BooleanSetting("Skip Curse Of Binding", true);

    public AutoArmorModule() {
        super("Auto Armor", Category.CHEATS);
        addSetting(delay);
        addSetting(preferElytra);
        addSetting(skipBinding);
    }
}
