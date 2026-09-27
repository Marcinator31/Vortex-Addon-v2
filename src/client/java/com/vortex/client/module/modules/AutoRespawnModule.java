package com.vortex.client.module.modules;

import com.vortex.client.core.setting.BooleanSetting;
import com.vortex.client.core.setting.NumberSetting;
import com.vortex.client.module.Module;

/** Auto Respawn: nach dem Tod sofort wieder erscheinen, mit Todespunkt im Chat. */
public class AutoRespawnModule extends Module {

    public final NumberSetting delay = new NumberSetting("Delay Ticks", 5, 0, 100, 1);
    public final BooleanSetting deathCoords = new BooleanSetting("Death Coords In Chat", true);

    public AutoRespawnModule() {
        super("Auto Respawn", Category.CHEATS);
        addSetting(delay);
        addSetting(deathCoords);
    }
}
