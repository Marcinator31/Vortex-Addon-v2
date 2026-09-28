package com.vortex.client.module.modules;

import com.vortex.client.core.setting.ModeSetting;
import com.vortex.client.core.setting.NumberSetting;
import com.vortex.client.module.Module;

/**
 * Anti Void: faengt dich ab, bevor du in die Leere faellst.
 *
 *  - Return: zurueck zur letzten sicheren Stelle (Teleport in Schritten,
 *    wie Click TP)
 *  - Bounce: Schwung nach oben
 *  - Hover: bleibt in der Luft stehen
 *
 * Ausgeloest, wenn unter dir bis zum Weltende kein Block kommt und du schon
 * "Min Fall" Bloecke gefallen bist -- oder erst unterhalb der Welt.
 */
public class AntiVoidModule extends Module {

    public final ModeSetting mode = new ModeSetting("Mode", 0, "Return", "Bounce", "Hover");
    public final ModeSetting trigger = new ModeSetting("Trigger", 0, "Void Below", "Below World");
    public final NumberSetting minFall = new NumberSetting("Min Fall", 3, 1, 20, 1);

    public AntiVoidModule() {
        super("Anti Void", Category.CHEATS);
        addSetting(mode);
        addSetting(trigger);
        addSetting(minFall);
    }
}
