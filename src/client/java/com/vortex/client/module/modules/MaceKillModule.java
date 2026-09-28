package com.vortex.client.module.modules;

import com.vortex.client.core.setting.BooleanSetting;
import com.vortex.client.core.setting.NumberSetting;
import com.vortex.client.module.Module;

/**
 * Mace Kill: jeder Schlag mit dem Streitkolben wird ein Smash-Angriff aus
 * grosser Hoehe -- ohne zu springen. Mit Kill Aura zusammen: One-Hit.
 *
 * WIE: Direkt vor dem Schlag meldet der Client dem Server "ich bin X Bloecke
 * hoch" und sofort "wieder unten". Der Server zaehlt die Strecke nach unten
 * als Fallhoehe (Entity.checkFallDamage). Der Streitkolben rechnet daraus
 * seinen Bonusschaden: +4 pro Block (bis 3), +2 (bis 8), danach +1 pro Block
 * -- bei 20 Bloecken +34 Schaden, mit Density-Verzauberung noch viel mehr.
 * Nach dem Treffer setzt der Streitkolben die Fallhoehe selbst zurueck, du
 * bekommst also keinen Fallschaden.
 *
 *   Height        gemeldete Hoehe. Bis 10 geht mit einem Paket; darueber
 *                 braucht es zusaetzliche Pakete (Server-Limit: 10 Bloecke je
 *                 Paket, bis zu 5 Pakete pro Tick) -> hoechstens 22.
 *   Players Only  nur bei Spielern
 *
 * GRENZEN:
 *  - Ueber dir muss die Hoehe frei sein (keine Decke) -- sonst wird sie
 *    automatisch kleiner gewaehlt.
 *  - Trifft der Schlag nicht (Ziel ausser Reichweite), bleibt die Fallhoehe
 *    auf dem Server stehen -> beim naechsten Landen gibt es Fallschaden.
 *  - Viele Server patchen genau das (Paper-Plugins, Anticheats wie Grim) --
 *    dort wirkt es nicht oder fuehrt zum Bann.
 */
public class MaceKillModule extends Module {

    public final NumberSetting height = new NumberSetting("Height", 20, 2, 22, 1);
    public final BooleanSetting playersOnly = new BooleanSetting("Players Only", false);
    /** Mit Schwert/Axt in der Hand: fuer den Schlag den Streitkolben aus der Hotbar nehmen. */
    public final BooleanSetting autoMace = new BooleanSetting("Auto Mace", true);
    /** In der Aktionsleiste zeigen, mit welcher Hoehe geschlagen wurde -- oder warum nicht. */
    public final BooleanSetting showInfo = new BooleanSetting("Show Info", true);

    public MaceKillModule() {
        super("Mace Kill", Category.CHEATS);
        addSetting(height);
        addSetting(playersOnly);
        addSetting(autoMace);
        addSetting(showInfo);
    }
}
