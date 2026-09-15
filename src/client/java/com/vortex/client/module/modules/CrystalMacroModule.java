package com.vortex.client.module.modules;

import com.vortex.client.core.setting.BooleanSetting;
import com.vortex.client.core.setting.NumberSetting;
import com.vortex.client.module.Module;

/**
 * Crystal Macro: setzt automatisch Enderkristalle und sprengt sie sofort.
 *
 * Ablauf, sobald das Fadenkreuz auf einem geeigneten Block (Obsidian oder
 * Grundgestein) liegt:
 *   1) auf den Hotbar-Platz mit Kristallen wechseln, falls noetig
 *   2) Kristall setzen
 *   3) den gesetzten Kristall sofort wieder zerschlagen
 *   4) auf den vorherigen Platz zurueckwechseln (einstellbar)
 *
 * BANN-RISIKO -- deutlich hoeher als bei allem anderen in diesem Client:
 * Kristall-Automatiken sind das am besten erkannte Merkmal ueberhaupt. Setzen
 * und Sprengen in derselben Sekunde, immer gleiche Abstaende, sofortiges
 * Zurueckwechseln -- das ist mit blossem Auge im Serverprotokoll sichtbar und
 * wird von jedem Anticheat erfasst. Auf DonutSMP heisst das sehr wahrscheinlich
 * Bann, und zwar schnell.
 */
public class CrystalMacroModule extends Module {

    /**
     * Pause zwischen den Aktionen in Ticks (1 Tick = 50 ms).
     *
     * 0 bedeutet: so schnell das Spiel es zulaesst. Genau das faellt allerdings
     * am staerksten auf -- ein Mensch schafft solche Abstaende nicht.
     */
    public final NumberSetting delay = new NumberSetting("Delay", 0, 0, 10, 1);

    /** Nach dem Setzen wieder auf den vorherigen Hotbar-Platz zurueck. */
    public final BooleanSetting switchBack = new BooleanSetting("Switch Back", true);

    /** Auch auf Grundgestein setzen (im Nether ueblich). */
    public final BooleanSetting bedrock = new BooleanSetting("Include Bedrock", true);

    /** Gesetzte Kristalle sofort wieder zerschlagen. */
    public final BooleanSetting breakThem = new BooleanSetting("Break Instantly", true);

    /** Reichweite, in der Kristalle zum Sprengen gesucht werden. */
    public final NumberSetting breakRange = new NumberSetting("Break Range", 4.5, 2.0, 6.0, 0.5);

    /** Nur setzen, solange die Angriffstaste gehalten wird. */
    public final BooleanSetting onlyWhenHolding =
            new BooleanSetting("Only While Key Held", true);

    public CrystalMacroModule() {
        super("Crystal Macro", Category.CHEATS);
        addSetting(delay);
        addSetting(switchBack);
        addSetting(bedrock);
        addSetting(breakThem);
        addSetting(breakRange);
        addSetting(onlyWhenHolding);
    }
}
