package com.vortex.client.module.modules;

import com.vortex.client.core.setting.BooleanSetting;
import com.vortex.client.core.setting.ModeSetting;
import com.vortex.client.core.setting.NumberSetting;
import com.vortex.client.module.Module;

/**
 * Leert eine geoeffnete Kiste automatisch in dein Inventar.
 *
 * Mit einstellbarer Pause zwischen den Gegenstaenden -- 0 ist am schnellsten,
 * faellt aber am meisten auf. Ist das Inventar voll oder die Kiste leer, wird
 * sie auf Wunsch geschlossen.
 */
public class ChestStealerModule extends Module {

    public final NumberSetting delay = new NumberSetting("Delay (ticks)", 2, 0, 10, 1);
    public final BooleanSetting autoClose = new BooleanSetting("Auto Close", true);
    public final BooleanSetting onlyChests = new BooleanSetting("Only Chests", true);

    public ChestStealerModule() {
        super("Chest Stealer", Category.CHEATS);
        addSetting(delay);
        addSetting(autoClose);
        addSetting(onlyChests);
    }
}
