package com.vortex.client.module.modules;

import com.vortex.client.core.setting.BooleanSetting;
import com.vortex.client.core.setting.ColorSetting;
import com.vortex.client.module.Module;

/**
 * Spawner ESP: hebt Mob-Spawner durch Waende hervor. Nuetzlich, um XP-Farmen,
 * Dungeons oder von Spielern gebaute Spawner-Farmen zu finden.
 */
public class SpawnerEspModule extends Module {

    public final ColorSetting color = new ColorSetting("Color", 0xFFFF0000);
    public final BooleanSetting tracer = new BooleanSetting("Tracers", false);

    public SpawnerEspModule() {
        super("Spawner ESP", Category.CHEATS);
        addSetting(color);
        addSetting(tracer);
    }

    public int getColor() { return color.get(); }
    public boolean tracerEnabled() { return tracer.get(); }
}
