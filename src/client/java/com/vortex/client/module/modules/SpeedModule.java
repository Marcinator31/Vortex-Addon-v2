package com.vortex.client.module.modules;

import com.vortex.client.core.setting.BooleanSetting;
import com.vortex.client.core.setting.ModeSetting;
import com.vortex.client.core.setting.NumberSetting;
import com.vortex.client.module.Module;

/**
 * Schneller laufen.
 *
 * "Strafe": volle Geschwindigkeit in jede Richtung, auch seitwaerts und in
 * der Luft lenkbar. "Boost": verstaerkt die normale Bewegung am Boden.
 * "Auto Jump" springt dabei automatisch (Bunny Hop).
 *
 * Hohes Bann-Risiko.
 */
public class SpeedModule extends Module {

    public final ModeSetting mode = new ModeSetting("Mode", 0, "Strafe", "Boost");
    public final NumberSetting speed = new NumberSetting("Speed", 1.3, 1.0, 3.0, 0.05);
    public final BooleanSetting autoJump = new BooleanSetting("Auto Jump", false);

    public SpeedModule() {
        super("Speed", Category.CHEATS);
        addSetting(mode);
        addSetting(speed);
        addSetting(autoJump);
    }
}
