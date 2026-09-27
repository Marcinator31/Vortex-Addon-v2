package com.vortex.client.module.modules;

import com.vortex.client.core.setting.BooleanSetting;
import com.vortex.client.module.Module;

/**
 * Safe Walk: du faellst nicht von Kanten -- wie Schleichen, nur ohne
 * langsamer zu werden. Nutzt genau die Kanten-Bremse, die Minecraft beim
 * Schleichen verwendet. Fuer den Server sieht das wie normales Laufen aus.
 */
public class SafeWalkModule extends Module {

    public final BooleanSetting allowJump = new BooleanSetting("Off While Jumping", true);

    public SafeWalkModule() {
        super("Safe Walk", Category.CHEATS);
        addSetting(allowJump);
    }
}
