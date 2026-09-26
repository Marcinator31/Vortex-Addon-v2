package com.vortex.client.module.modules;

import com.vortex.client.core.setting.BooleanSetting;
import com.vortex.client.core.setting.ModeSetting;
import com.vortex.client.core.setting.NumberSetting;
import com.vortex.client.module.Module;

/**
 * Isst automatisch, wenn der Hunger unter die Grenze faellt.
 *
 * Nimmt das naehrendste Essen aus der Hotbar (liegt keins dort, wird eins
 * aus dem Inventar geholt) und schaltet danach auf den vorherigen Platz
 * zurueck. Schaedliches Essen wie verrottetes Fleisch oder Spinnenaugen wird
 * nie gegessen. Beim Kaempfen (Angriffstaste gedrueckt) wird gewartet.
 */
public class AutoEatModule extends Module {

    public final NumberSetting hunger = new NumberSetting("Eat Below Hunger", 14, 1, 19, 1);
    /** 0 = aus. Sonst: auch essen, um Leben zu regenerieren. */
    public final NumberSetting health = new NumberSetting("Eat Below Health", 0, 0, 20, 1);
    public final BooleanSetting gapples = new BooleanSetting("Allow Golden Apples", false);
    public final BooleanSetting pauseFighting = new BooleanSetting("Pause While Fighting", true);

    public AutoEatModule() {
        super("Auto Eat", Category.CHEATS);
        addSetting(hunger);
        addSetting(health);
        addSetting(gapples);
        addSetting(pauseFighting);
    }
}
