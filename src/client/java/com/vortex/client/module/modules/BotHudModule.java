package com.vortex.client.module.modules;

import com.vortex.client.core.setting.ColorSetting;
import com.vortex.client.core.setting.NumberSetting;
import com.vortex.client.hud.HudElement;
import com.vortex.client.module.Module;

/**
 * Bot Status: zeigt im Spiel, was jeder laufende Bot gerade tut (dieselbe
 * Zeile wie auf der Bot-Seite). Nur sichtbar, solange ein Bot laeuft; im
 * HUD-Editor verschiebbar. Gezeichnet in com.vortex.client.bot.BotHud.
 */
public class BotHudModule extends Module implements HudElement {

    public final NumberSetting x = new NumberSetting("X", 4, 0, 1920, 1);
    public final NumberSetting y = new NumberSetting("Y", 90, 0, 1080, 1);
    public final ColorSetting color = new ColorSetting("Text Color", 0xFFFFFFFF);
    public final NumberSetting scale = new NumberSetting("Scale", 1.0, 0.5, 3.0, 0.1);
    public final com.vortex.client.hud.HudStyle style = new com.vortex.client.hud.HudStyle(true);

    public BotHudModule() {
        super("Bot Status", Category.HUD);
        enabledByDefault();
        addSetting(x);
        addSetting(y);
        addSetting(color);
        addSetting(scale);
        style.addTo(this::addSetting);
    }

    @Override public String hudName() { return "Bot Status"; }
    @Override public NumberSetting hudX() { return x; }
    @Override public NumberSetting hudY() { return y; }
    @Override public NumberSetting hudScale() { return scale; }
    @Override public ColorSetting hudColor() { return color; }
    @Override public int hudWidth() { return style.breite(220); }
    @Override public int hudHeight() { return style.hoehe(12); }
}
