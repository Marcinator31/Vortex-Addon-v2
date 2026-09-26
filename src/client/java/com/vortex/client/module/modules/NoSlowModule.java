package com.vortex.client.module.modules;

import com.vortex.client.core.setting.BooleanSetting;
import com.vortex.client.core.setting.ModeSetting;
import com.vortex.client.core.setting.NumberSetting;
import com.vortex.client.module.Module;

/**
 * Kein Abbremsen beim Essen, Trinken, Bogenspannen oder Blocken.
 */
public class NoSlowModule extends Module {

    public final BooleanSetting items = new BooleanSetting("Items", true);

    public NoSlowModule() {
        super("No Slow", Category.CHEATS);
        addSetting(items);
    }
}
