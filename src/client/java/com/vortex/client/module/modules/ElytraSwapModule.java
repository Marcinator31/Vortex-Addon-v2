package com.vortex.client.module.modules;

import com.vortex.client.core.setting.BooleanSetting;
import com.vortex.client.core.setting.KeySetting;
import com.vortex.client.core.setting.NumberSetting;
import com.vortex.client.module.Module;

/**
 * Elytra Swap: Brustplatte und Elytra automatisch tauschen.
 *
 *  - Swap Key: Taste tauscht sofort (Brustplatte <-> Elytra).
 *  - Auto Elytra: im Fallen Sprungtaste druecken -> Elytra an und losgleiten.
 *  - Auto Chestplate: gelandet -> wieder die beste Brustplatte an.
 *
 * Getauscht wird per Inventarklick -- die Elytra darf irgendwo im Inventar
 * liegen, nicht nur in der Hotbar.
 */
public class ElytraSwapModule extends Module {

    public final KeySetting swapKey = new KeySetting("Swap Key", org.lwjgl.glfw.GLFW.GLFW_KEY_UNKNOWN);
    public final BooleanSetting autoElytra = new BooleanSetting("Auto Elytra", true);
    public final BooleanSetting autoGlide = new BooleanSetting("Start Gliding", true);
    public final BooleanSetting autoChestplate = new BooleanSetting("Auto Chestplate", true);
    public final NumberSetting minFall = new NumberSetting("Min Fall Blocks", 1.5, 0.5, 6, 0.5);
    public final NumberSetting delay = new NumberSetting("Delay Ticks", 4, 1, 20, 1);

    public ElytraSwapModule() {
        super("Elytra Swap", Category.CHEATS);
        addSetting(swapKey);
        addSetting(autoElytra);
        addSetting(autoGlide);
        addSetting(autoChestplate);
        addSetting(minFall);
        addSetting(delay);
    }
}
