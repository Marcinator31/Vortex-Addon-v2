package com.vortex.client.module.modules;

import com.vortex.client.core.setting.BooleanSetting;
import com.vortex.client.core.setting.ColorSetting;
import com.vortex.client.core.setting.NumberSetting;
import com.vortex.client.module.Module;

/**
 * Blink: haelt deine Bewegungspakete zurueck. Fuer den Server stehst du still,
 * bis du Blink ausschaltest -- dann kommt der ganze Weg auf einmal an.
 *
 * Setzt dich der Server zurueck, wird der Puffer verworfen und Blink geht aus.
 */
public class BlinkModule extends Module {

    public final NumberSetting maxSeconds = new NumberSetting("Auto Release (s)", 10, 1, 30, 1);
    public final NumberSetting pulse = new NumberSetting("Pulse (s)", 0, 0, 5, 0.5);
    public final BooleanSetting render = new BooleanSetting("Show Server Position", true);
    public final ColorSetting color = new ColorSetting("Color", 0xFF55CCFF);

    public BlinkModule() {
        super("Blink", Category.CHEATS);
        addSetting(maxSeconds);
        addSetting(pulse);
        addSetting(render);
        addSetting(color);
    }

    @Override
    protected void onEnable() {
        com.vortex.client.cheat.Blink.start();
    }

    @Override
    protected void onDisable() {
        com.vortex.client.cheat.Blink.loslassen();
    }
}
