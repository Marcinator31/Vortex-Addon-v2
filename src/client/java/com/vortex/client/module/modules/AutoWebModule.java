package com.vortex.client.module.modules;

import com.vortex.client.core.setting.BooleanSetting;
import com.vortex.client.core.setting.ModeSetting;
import com.vortex.client.core.setting.NumberSetting;
import com.vortex.client.module.Module;

/** Auto Web: setzt Spinnweben auf die Fuesse (und den Kopf) des Gegners. */
public class AutoWebModule extends Module {

    public final NumberSetting range = new NumberSetting("Range", 4.5, 2, 5.5, 0.5);
    public final ModeSetting where = new ModeSetting("Place", 0, "Feet", "Feet + Head");
    public final NumberSetting delay = new NumberSetting("Delay Ticks", 4, 0, 20, 1);
    public final BooleanSetting onlyPlayers = new BooleanSetting("Only Players", true);
    public final BooleanSetting onlyOnGround = new BooleanSetting("Target On Ground", false);
    public final BooleanSetting notSelf = new BooleanSetting("Never Next To Me", true);
    public final BooleanSetting swing = new BooleanSetting("Swing", true);

    public AutoWebModule() {
        super("Auto Web", Category.CHEATS);
        addSetting(range);
        addSetting(where);
        addSetting(delay);
        addSetting(onlyPlayers);
        addSetting(onlyOnGround);
        addSetting(notSelf);
        addSetting(swing);
    }
}
