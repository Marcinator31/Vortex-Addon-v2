package com.vortex.client.module.modules;

import com.vortex.client.core.setting.BooleanSetting;
import com.vortex.client.module.Module;

/**
 * Ghost Hand: Rechtsklick oeffnet Kisten, Oefen, Schalter usw. durch Waende
 * hindurch -- das erste benutzbare Objekt auf der Blicklinie in Reichweite.
 */
public class GhostHandModule extends Module {

    public final BooleanSetting containersOnly = new BooleanSetting("Containers Only", true);
    public final BooleanSetting swing = new BooleanSetting("Swing", true);

    public GhostHandModule() {
        super("Ghost Hand", Category.CHEATS);
        addSetting(containersOnly);
        addSetting(swing);
    }
}
