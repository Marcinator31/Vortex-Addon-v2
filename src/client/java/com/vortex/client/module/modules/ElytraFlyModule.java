package com.vortex.client.module.modules;

import com.vortex.client.core.setting.BooleanSetting;
import com.vortex.client.core.setting.ModeSetting;
import com.vortex.client.core.setting.NumberSetting;
import com.vortex.client.module.Module;

/**
 * Volle Kontrolle mit der Elytra.
 *
 *  "Control"  -- schweben und in jede Richtung fliegen (WASD, Springen = hoch,
 *               Schleichen = runter). Ohne Eingabe bleibst du in der Luft.
 *  "Boost"    -- normaler Gleitflug, W beschleunigt in Blickrichtung bis zur
 *               Hoechstgeschwindigkeit.
 *  "Firework" -- normaler Gleitflug, Raketen aus der Hotbar werden
 *               automatisch gezuendet, sobald du zu langsam wirst.
 *
 * "Auto Takeoff" oeffnet die Elytra von selbst, sobald du von einer Kante
 * faellst oder in der Luft springst. Im Wasser wird angehalten.
 *
 * Hohes Bann-Risiko ausser im Modus Firework.
 */
public class ElytraFlyModule extends Module {

    public final ModeSetting mode = new ModeSetting("Mode", 0, "Control", "Boost", "Firework");
    public final NumberSetting speed = new NumberSetting("Horizontal Speed", 1.5, 0.2, 4.0, 0.1);
    public final NumberSetting verticalSpeed = new NumberSetting("Vertical Speed", 0.8, 0.1, 2.0, 0.1);
    public final NumberSetting acceleration = new NumberSetting("Boost Acceleration", 0.05, 0.01, 0.2, 0.01);
    public final NumberSetting maxSpeed = new NumberSetting("Max Speed", 2.5, 0.5, 5.0, 0.1);
    public final NumberSetting fireworkDelay = new NumberSetting("Firework Delay (s)", 2.5, 0.5, 10.0, 0.5);
    public final NumberSetting fireworkBelow = new NumberSetting("Firework Below Speed", 0.8, 0.1, 3.0, 0.1);
    public final BooleanSetting autoTakeoff = new BooleanSetting("Auto Takeoff", true);
    public final BooleanSetting antiKick = new BooleanSetting("Anti Kick Drift", true);
    public final BooleanSetting stopInWater = new BooleanSetting("Stop In Water", true);

    public ElytraFlyModule() {
        super("Elytra Fly", Category.CHEATS);
        addSetting(mode);
        addSetting(speed);
        addSetting(verticalSpeed);
        addSetting(acceleration);
        addSetting(maxSpeed);
        addSetting(fireworkDelay);
        addSetting(fireworkBelow);
        addSetting(autoTakeoff);
        addSetting(antiKick);
        addSetting(stopInWater);
    }
}
