package com.vortex.client.module.modules;

import com.vortex.client.core.setting.BooleanSetting;
import com.vortex.client.core.setting.NumberSetting;
import com.vortex.client.module.Module;

/**
 * Auto Log: trennt die Verbindung, bevor es zu spaet ist.
 *
 * Ausloeser (jeder einzeln an/aus):
 *  - Leben unter X
 *  - weniger als N Totems
 *  - ein Totem ist gerade geplatzt
 *  - ein fremder Spieler (kein Freund) kommt naeher als X Bloecke
 *
 * Danach schaltet sich das Modul aus, und Auto Reconnect auch -- sonst waere
 * man sofort wieder drin.
 */
public class AutoLogModule extends Module {

    public final NumberSetting health = new NumberSetting("Health Below", 6, 0, 19, 1);
    public final NumberSetting totems = new NumberSetting("Totems Below", 0, 0, 10, 1);
    public final BooleanSetting onPop = new BooleanSetting("On Totem Pop", false);
    public final BooleanSetting onPlayer = new BooleanSetting("Player Nearby", false);
    public final NumberSetting playerRange = new NumberSetting("Player Range", 32, 4, 128, 2);
    public final BooleanSetting ignoreFriends = new BooleanSetting("Ignore Friends", true);
    public final BooleanSetting notSingleplayer = new BooleanSetting("Not In Singleplayer", true);
    public final BooleanSetting disableAfter = new BooleanSetting("Toggle Off After", true);

    public AutoLogModule() {
        super("Auto Log", Category.CHEATS);
        addSetting(health);
        addSetting(totems);
        addSetting(onPop);
        addSetting(onPlayer);
        addSetting(playerRange);
        addSetting(ignoreFriends);
        addSetting(notSingleplayer);
        addSetting(disableAfter);
    }
}
