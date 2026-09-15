package com.vortex.client.module.modules;

import com.vortex.client.core.setting.BooleanSetting;
import com.vortex.client.core.setting.ColorSetting;
import com.vortex.client.module.Module;

/**
 * Item ESP: markiert auf dem Boden liegende Items (gedroppte ItemEntities) mit
 * einer Box und optional einem Tracer. Nuetzlich, um Loot nach Kaempfen oder
 * ausgelaufene Stashes zu finden.
 */
public class ItemEspModule extends Module {

    public final ColorSetting color = new ColorSetting("Color", 0xFF00FF00);
    public final BooleanSetting tracer = new BooleanSetting("Tracers", false);

    /**
     * How far away an item is still drawn.
     *
     * Distant items are a few pixels on screen and cost exactly as much to draw
     * as close ones. Standing near a stash with hundreds of them on the floor,
     * that is where the frames go.
     */
    public final com.vortex.client.core.setting.NumberSetting maxDistance =
            new com.vortex.client.core.setting.NumberSetting("Max Distance", 64, 8, 256, 8);

    public ItemEspModule() {
        super("Item ESP", Category.CHEATS);
        addSetting(color);
        addSetting(tracer);
        addSetting(maxDistance);
    }

    public int getColor() { return color.get(); }
    public boolean tracerEnabled() { return tracer.get(); }
    public double maxDistance() { return maxDistance.get(); }
}
