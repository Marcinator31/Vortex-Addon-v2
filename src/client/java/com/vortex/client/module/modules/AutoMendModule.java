package com.vortex.client.module.modules;

import com.vortex.client.core.setting.BooleanSetting;
import com.vortex.client.core.setting.ModeSetting;
import com.vortex.client.core.setting.NumberSetting;
import com.vortex.client.module.Module;

/**
 * Repariert die Ruestung mit Erfahrungsflaschen.
 *
 * Sinkt ein Ruestungsteil unter die Grenze, wird eine Flasche aus der Hotbar
 * nach unten geworfen -- nur fuer den Server, die Kamera bleibt, wie sie ist.
 * Nur Teile mit Reparatur-Verzauberung zaehlen, denn nur die werden von
 * Erfahrung repariert. Sind Gegner in der Naehe, wird gewartet.
 */
public class AutoMendModule extends Module {

    public final NumberSetting threshold = new NumberSetting("Repair Below %", 60, 10, 100, 5);
    public final NumberSetting delay = new NumberSetting("Delay (ticks)", 2, 1, 10, 1);
    public final NumberSetting pauseRange = new NumberSetting("Pause Near Enemies", 6, 0, 16, 1);
    public final BooleanSetting onlyMending = new BooleanSetting("Only With Mending", true);

    public AutoMendModule() {
        super("Auto Mend", Category.CHEATS);
        addSetting(threshold);
        addSetting(delay);
        addSetting(pauseRange);
        addSetting(onlyMending);
    }
}
