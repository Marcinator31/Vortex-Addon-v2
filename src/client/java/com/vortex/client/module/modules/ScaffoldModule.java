package com.vortex.client.module.modules;

import com.vortex.client.core.setting.BooleanSetting;
import com.vortex.client.core.setting.ModeSetting;
import com.vortex.client.core.setting.NumberSetting;
import com.vortex.client.module.Module;

/**
 * Setzt beim Laufen automatisch Bloecke unter dich.
 *
 * Nimmt Bloecke aus der Hotbar. "Tower": Springen haelt dich an Ort und
 * Stelle und baut senkrecht nach oben. "Rotate" dreht dich fuer den Server
 * kurz zur Setzstelle -- die Kamera bleibt ruhig.
 */
public class ScaffoldModule extends Module {

    public final BooleanSetting tower = new BooleanSetting("Tower", true);
    public final BooleanSetting rotate = new BooleanSetting("Rotate", true);
    public final BooleanSetting swing = new BooleanSetting("Swing", true);
    public final BooleanSetting switchBack = new BooleanSetting("Switch Back", true);

    public ScaffoldModule() {
        super("Scaffold", Category.CHEATS);
        addSetting(tower);
        addSetting(rotate);
        addSetting(swing);
        addSetting(switchBack);
    }
}
