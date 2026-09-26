package com.vortex.client.module.modules;

import com.vortex.client.core.setting.BooleanSetting;
import com.vortex.client.core.setting.ModeSetting;
import com.vortex.client.core.setting.NumberSetting;
import com.vortex.client.module.Module;

/**
 * Laufen, springen und schauen, waehrend das Inventar offen ist.
 *
 * Gilt fuer Inventar, Kisten und andere Behaelter -- nicht fuer den Chat und
 * nicht fuer Fenster mit Eingabefeld (Amboss, Schild), dort wird getippt.
 */
public class InventoryMoveModule extends Module {

    public final BooleanSetting jump = new BooleanSetting("Jump", true);
    public final BooleanSetting sneak = new BooleanSetting("Sneak", false);
    public final BooleanSetting arrows = new BooleanSetting("Arrow Keys Look", true);

    public InventoryMoveModule() {
        super("Inventory Move", Category.CHEATS);
        addSetting(jump);
        addSetting(sneak);
        addSetting(arrows);
    }
}
