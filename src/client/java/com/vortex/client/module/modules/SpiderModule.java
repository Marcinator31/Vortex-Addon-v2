package com.vortex.client.module.modules;

import com.vortex.client.core.setting.NumberSetting;
import com.vortex.client.module.Module;

/**
 * Spider: laeuft Waende hoch, wenn du dagegen laeufst.
 *
 * Vanilla-Server kicken nicht: die Flug-Pruefung zaehlt nur, solange KEIN
 * Block um dich ist -- an einer Wand ist immer einer. Anticheats erkennen es.
 */
public class SpiderModule extends Module {

    public final NumberSetting speed = new NumberSetting("Climb Speed", 0.2, 0.1, 0.5, 0.05);

    public SpiderModule() {
        super("Spider", Category.CHEATS);
        addSetting(speed);
    }
}
