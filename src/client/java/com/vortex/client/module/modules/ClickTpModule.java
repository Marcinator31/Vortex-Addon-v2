package com.vortex.client.module.modules;

import com.vortex.client.core.setting.KeySetting;
import com.vortex.client.core.setting.ModeSetting;
import com.vortex.client.core.setting.NumberSetting;
import com.vortex.client.module.Module;

/**
 * Click TP: teleportiert dich auf den Block, den du ansiehst.
 *
 * Minecraft laesst pro Bewegungspaket nur ~10 Bloecke zu, mit mehreren Paketen
 * im selben Tick ~20. Weitere Strecken gehen in mehreren Spruengen, einer pro
 * Tick. Der Weg muss frei sein (der Server prueft Kollisionen) -- ist die
 * gerade Linie verbaut, wird ueber oben ausgewichen.
 */
public class ClickTpModule extends Module {

    public final ModeSetting trigger = new ModeSetting("Trigger", 0, "Right Click (Empty Hand)", "Key");
    public final KeySetting key = new KeySetting("Teleport Key", org.lwjgl.glfw.GLFW.GLFW_KEY_UNKNOWN);
    public final NumberSetting maxDistance = new NumberSetting("Max Distance", 60, 5, 150, 5);
    public final ModeSetting speed = new ModeSetting("Hop Size", 1, "Safe (9 per tick)", "Fast (20 per tick)");

    public ClickTpModule() {
        super("Click TP", Category.CHEATS);
        addSetting(trigger);
        addSetting(key);
        addSetting(maxDistance);
        addSetting(speed);
    }
}
