package com.vortex.client.module.modules;

import com.vortex.client.core.setting.BooleanSetting;
import com.vortex.client.core.setting.ColorSetting;
import com.vortex.client.core.setting.ModeSetting;
import com.vortex.client.core.setting.NumberSetting;
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
    /** Aussehen wie beim Block-ESP (seit 2.38). */
    public final ModeSetting style = new ModeSetting("Style", 0, "Outline + Fill", "Outline", "Fill");
    public final NumberSetting fillOpacity = new NumberSetting("Fill Opacity", 20, 5, 80, 5);
    public final BooleanSetting glow = new BooleanSetting("Glow", true);
    /** Bis wohin gezeichnet wird (seit 2.44 bis 1024; vorher fest 96). Gesucht wird in der ganzen Sichtweite. */
    public final NumberSetting viewDistance = new NumberSetting("View Distance", 1024, 32, 1024, 16);

    public ContainerEspModule() {
        super("Container ESP", Category.CHEATS);
        addSetting(color);
        addSetting(tracer);
        addSetting(style);
        addSetting(fillOpacity);
        addSetting(glow);
        addSetting(viewDistance);
    }

    public int getColor() { return color.get(); }
    public boolean tracerEnabled() { return tracer.get(); }
}
