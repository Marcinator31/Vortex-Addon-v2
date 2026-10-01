package com.vortex.client.module.modules;

import com.vortex.client.core.setting.BooleanSetting;
import com.vortex.client.core.setting.ColorSetting;
import com.vortex.client.core.setting.ModeSetting;
import com.vortex.client.core.setting.NumberSetting;
import com.vortex.client.module.Module;

/**
 * Spawner ESP: hebt Mob-Spawner durch Waende hervor. Nuetzlich, um XP-Farmen,
 * Dungeons oder von Spielern gebaute Spawner-Farmen zu finden.
 */
public class SpawnerEspModule extends Module {

    public final ColorSetting color = new ColorSetting("Color", 0xFFFF0000);
    public final BooleanSetting tracer = new BooleanSetting("Tracers", false);
    /** Aussehen wie beim Block-ESP (seit 2.38). */
    public final ModeSetting style = new ModeSetting("Style", 0, "Outline + Fill", "Outline", "Fill");
    public final NumberSetting fillOpacity = new NumberSetting("Fill Opacity", 20, 5, 80, 5);
    public final BooleanSetting glow = new BooleanSetting("Glow", true);

    public SpawnerEspModule() {
        super("Spawner ESP", Category.CHEATS);
        addSetting(color);
        addSetting(tracer);
        addSetting(style);
        addSetting(fillOpacity);
        addSetting(glow);
    }

    public int getColor() { return color.get(); }
    public boolean tracerEnabled() { return tracer.get(); }
}
