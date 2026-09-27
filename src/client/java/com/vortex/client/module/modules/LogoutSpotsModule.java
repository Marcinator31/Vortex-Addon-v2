package com.vortex.client.module.modules;

import com.vortex.client.core.setting.BooleanSetting;
import com.vortex.client.core.setting.ColorSetting;
import com.vortex.client.core.setting.NumberSetting;
import com.vortex.client.module.Module;

/**
 * Logout Spots: merkt sich, wo Spieler in deiner Naehe sich ausgeloggt haben,
 * und zeigt dort ihre Umrisse. Kommen sie zurueck, verschwindet die Markierung.
 */
public class LogoutSpotsModule extends Module {

    public final ColorSetting color = new ColorSetting("Color", 0xFFFF4060);
    public final BooleanSetting tracer = new BooleanSetting("Tracer", false);
    public final BooleanSetting chat = new BooleanSetting("Chat Message", true);
    public final NumberSetting maxSpots = new NumberSetting("Max Spots", 20, 1, 100, 1);

    public LogoutSpotsModule() {
        super("Logout Spots", Category.CHEATS);
        addSetting(color);
        addSetting(tracer);
        addSetting(chat);
        addSetting(maxSpots);
    }

    @Override
    protected void onDisable() {
        com.vortex.client.hud.LogoutSpots.leeren();
    }
}
