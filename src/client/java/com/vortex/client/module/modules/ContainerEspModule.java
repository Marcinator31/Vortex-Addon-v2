package com.vortex.client.module.modules;

import com.vortex.client.core.setting.BooleanSetting;
import com.vortex.client.core.setting.ColorSetting;
import com.vortex.client.module.Module;

/**
 * Container ESP: markiert einzelne Container (Truhen, Shulker, Faesser,
 * Trichter, Spender, ...) durch Waende mit einer Box-Outline. Ergaenzt den
 * Stash Finder: waehrend der Stash Finder ganze Cluster meldet, zeigt das hier
 * jeden einzelnen Container in Reichweite.
 */
public class ContainerEspModule extends Module {

    public final ColorSetting color = new ColorSetting("Color", 0xFFFFA500);
    public final BooleanSetting tracer = new BooleanSetting("Tracers", false);

    public ContainerEspModule() {
        super("Container ESP", Category.CHEATS);
        addSetting(color);
        addSetting(tracer);
    }

    public int getColor() { return color.get(); }
    public boolean tracerEnabled() { return tracer.get(); }
}
