package com.vortex.client.module.modules;

import com.vortex.client.core.setting.BooleanSetting;
import com.vortex.client.core.setting.NumberSetting;
import com.vortex.client.module.Module;

/**
 * Kill Aura: greift automatisch das naechste Ziel in Reichweite an -- auch
 * durch Waende.
 *
 * WARUM DURCH WAENDE GEHT: Der Server (Vanilla/Paper, 26.x) prueft beim
 * Angriff nur Weltgrenze und Reichweite -- keine Sichtlinie. Reichweite:
 * 3 Bloecke plus 3 Bloecke Toleranz, gemessen von den Augen bis zur Hitbox.
 *
 *   Range          Reichweite in Bloecken (Server erlaubt bis 6.0)
 *   Through Walls  auch Ziele hinter Bloecken angreifen
 *   Players Only   nur Spieler (keine Mobs)
 *   Min Charge     erst bei voll aufgeladenem Schlag zuschlagen (1.0 = voll)
 *   Auto Mace      Streitkolben aus der Hotbar nehmen, solange ein Ziel da ist
 *                  (zusammen mit "Mace Kill" = One-Hit)
 *
 * Freunde (Modul Friends) werden nie angegriffen.
 *
 * ACHTUNG: Anticheats pruefen Reichweite ueber 3 Bloecke, Treffer ohne
 * Sichtlinie und Treffer ohne Blick zum Ziel -- auf Servern mit Anticheat
 * fuehrt das schnell zum Bann.
 */
public class KillAuraModule extends Module {

    public final NumberSetting range = new NumberSetting("Range", 4.5, 3.0, 6.0, 0.1);
    public final BooleanSetting throughWalls = new BooleanSetting("Through Walls", true);
    public final BooleanSetting playersOnly = new BooleanSetting("Players Only", true);
    public final NumberSetting minCharge = new NumberSetting("Min Charge", 1.0, 0.5, 1.0, 0.05);
    public final BooleanSetting autoMace = new BooleanSetting("Auto Mace", true);

    public KillAuraModule() {
        super("Kill Aura", Category.CHEATS);
        addSetting(range);
        addSetting(throughWalls);
        addSetting(playersOnly);
        addSetting(minCharge);
        addSetting(autoMace);
    }

    @Override
    protected void onDisable() {
        com.vortex.client.cheat.KillAura.slotZurueck();
    }
}
