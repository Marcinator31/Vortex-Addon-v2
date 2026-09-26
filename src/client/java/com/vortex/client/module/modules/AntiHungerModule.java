package com.vortex.client.module.modules;

import com.vortex.client.core.setting.BooleanSetting;
import com.vortex.client.core.setting.ModeSetting;
import com.vortex.client.core.setting.NumberSetting;
import com.vortex.client.module.Module;

/**
 * Verbraucht weniger Hunger.
 *
 * Sprinten und Springen kosten am meisten. "Sprint": der Server erfaehrt
 * nicht, dass du sprintest. "Jump": der Server haelt dich beim Springen fuer
 * am Boden stehend. Laufen kostet ohnehin fast nichts. Hunger faellt also
 * weiter -- nur deutlich langsamer.
 */
public class AntiHungerModule extends Module {

    public final BooleanSetting sprint = new BooleanSetting("Sprint", true);
    public final BooleanSetting jump = new BooleanSetting("Jump", true);

    public AntiHungerModule() {
        super("Anti Hunger", Category.CHEATS);
        addSetting(sprint);
        addSetting(jump);
    }
}
