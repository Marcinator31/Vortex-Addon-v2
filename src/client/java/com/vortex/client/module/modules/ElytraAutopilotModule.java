package com.vortex.client.module.modules;

import com.vortex.client.core.setting.BooleanSetting;
import com.vortex.client.core.setting.NumberSetting;
import com.vortex.client.module.Module;

/**
 * Elytra Autopilot: fliegt mit der Elytra zu einem Ziel.
 *
 *   /autopilot <x> <z>   Ziel setzen (und losfliegen, wenn das Modul an ist)
 *   /autopilot stop      anhalten
 *
 * Startet vom Boden, haelt die Reiseflughoehe mit Feuerwerksraketen, weicht
 * Bergen aus (steigt) und landet am Ziel. Bei fast kaputter Elytra oder ohne
 * Raketen unterhalb der Reisehoehe wird gelandet.
 */
public class ElytraAutopilotModule extends Module {

    public final NumberSetting cruiseY = new NumberSetting("Cruise Height", 200, 64, 320, 5);
    public final BooleanSetting rockets = new BooleanSetting("Use Rockets", true);
    public final NumberSetting rocketDelay = new NumberSetting("Rocket Delay (s)", 2.0, 0.5, 6, 0.5);
    public final NumberSetting minSpeed = new NumberSetting("Min Speed (b/s)", 18, 5, 40, 1);
    public final BooleanSetting land = new BooleanSetting("Land At Target", true);
    public final NumberSetting minDurability = new NumberSetting("Land Below Durability", 20, 2, 100, 1);
    public final NumberSetting turnSpeed = new NumberSetting("Turn Speed", 6, 1, 20, 1);

    public ElytraAutopilotModule() {
        super("Elytra Autopilot", Category.BOTS);
        addSetting(cruiseY);
        addSetting(rockets);
        addSetting(rocketDelay);
        addSetting(minSpeed);
        addSetting(land);
        addSetting(minDurability);
        addSetting(turnSpeed);
    }
}
