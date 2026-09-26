package com.vortex.client.module.modules;

import com.vortex.client.core.setting.BooleanSetting;
import com.vortex.client.core.setting.ModeSetting;
import com.vortex.client.core.setting.NumberSetting;
import com.vortex.client.module.Module;

/**
 * Ueber Wasser (und auf Wunsch Lava) laufen.
 *
 * "Solid": die Oberflaeche traegt wie ein Block. "Bob": du bleibst an der
 * Oberflaeche und wippst leicht -- unauffaelliger. Schleichen laesst dich
 * eintauchen. Beim Fallen aus grosser Hoehe wird das Wasser weich, damit es
 * dich wie gewohnt auffaengt.
 */
public class JesusModule extends Module {

    public final ModeSetting mode = new ModeSetting("Mode", 0, "Solid", "Bob");
    public final BooleanSetting lava = new BooleanSetting("Lava Too", false);
    public final NumberSetting dipHeight = new NumberSetting("Dip When Falling From", 4, 2, 20, 1);

    public JesusModule() {
        super("Jesus", Category.CHEATS);
        addSetting(mode);
        addSetting(lava);
        addSetting(dipHeight);
    }
}
