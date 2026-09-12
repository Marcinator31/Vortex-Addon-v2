package com.vortex.client.module.modules;

import com.vortex.client.core.setting.BooleanSetting;
import com.vortex.client.core.setting.NumberSetting;
import com.vortex.client.module.Module;

/**
 * Netherite-Farmer.
 *
 * Sucht Ancient Debris, geht hin, baut ab, sammelt die Drops ein und haelt
 * sich dabei selbst am Leben: Essen bei Hunger, goldene Aepfel bei Schaden,
 * Ruestung und Totems nachlegen, Werkzeug per Mending reparieren.
 *
 * ZUM AUFBAU
 * Der Bot ist ein Zustandsautomat. Jeder Tick fragt: was ist gerade am
 * dringendsten? Ueberleben schlaegt Reparatur, Reparatur schlaegt Abbauen.
 * Ohne diese feste Rangfolge kaempfen die Teilaufgaben gegeneinander -- der
 * Bot faengt an zu essen, bricht ab, um zu graben, und verhungert dabei.
 *
 * WAS ER NICHT TUT
 * Er ahmt keine menschlichen Bewegungsmuster nach. Die Abstaende zwischen
 * seinen Aktionen sind so gewaehlt, dass sie FUNKTIONIEREN -- eine
 * XP-Flasche zu frueh geworfen repariert nichts, weil das Werkzeug noch
 * voll ist. Wer einen Bot auf einem fremden Server laufen laesst, faellt
 * ohnehin durch die Spielzeit auf, nicht durch Bewegungsmuster.
 */
public class NetheriteFarmerModule extends Module {

    // --- Suche -----------------------------------------------------------

    /** Wie weit nach Ancient Debris gesucht wird. */
    public final NumberSetting searchRange =
            new NumberSetting("Search Range", 32, 8, 64, 4);

    /** Unterhalb dieser Hoehe wird gesucht. Debris kommt tief vor. */
    public final NumberSetting maxY =
            new NumberSetting("Max Y", 22, 8, 119, 1);

    // --- Ueberleben ------------------------------------------------------

    /** Ab wie vielen Hunger-Balken gegessen wird (halbe Balken, 20 = voll). */
    public final NumberSetting eatBelow =
            new NumberSetting("Eat Below Hunger", 16, 6, 19, 1);

    /**
     * Ab wie vielen Herzen ein goldener Apfel gegessen wird.
     *
     * Getrennt vom Hunger, weil Schaden in Lava auch bei vollem Hunger
     * kommt -- dort hilft nur die Regeneration des Apfels.
     */
    public final NumberSetting gappleBelow =
            new NumberSetting("Gapple Below Health", 12, 4, 19, 1);

    /** Lava im Weg meiden statt hindurchzulaufen. */
    public final BooleanSetting avoidLava =
            new BooleanSetting("Avoid Lava", true);

    // --- Ausruestung -----------------------------------------------------

    /**
     * Unter dieser Haltbarkeit wird repariert (Prozent).
     *
     * Ueber 90 zu halten ist Absicht: faellt die Spitzhacke mitten im Abbau
     * aus, steht der Bot ohne Werkzeug in einer Lavahoehle.
     */
    public final NumberSetting repairBelow =
            new NumberSetting("Repair Below %", 90, 20, 99, 5);

    /** Abstand zwischen XP-Flaschen in Ticks. */
    public final NumberSetting bottleDelay =
            new NumberSetting("Bottle Delay (ticks)", 15, 5, 60, 5);

    /** Totem in die Off-Hand nachlegen. */
    public final BooleanSetting keepTotem =
            new BooleanSetting("Keep Totem", true);

    /** Ruestung unter dieser Haltbarkeit tauschen (Prozent). */
    public final NumberSetting armorBelow =
            new NumberSetting("Swap Armor Below %", 30, 5, 90, 5);

    // --- Aufhoeren -------------------------------------------------------

    /**
     * /afk senden, wenn etwas ausgeht.
     *
     * Besser als einfach stehenzubleiben: der Bot sagt selbst, dass er
     * fertig ist, statt bewegungslos weiterzulaufen.
     */
    public final BooleanSetting afkWhenDone =
            new BooleanSetting("/afk When Out", true);

    /**
     * Ausloggen, wenn ein Spieler naeher kommt.
     *
     * Sicherheitsfunktion: ein unbeaufsichtigter Bot wehrt sich nicht und
     * verliert im Zweifel alles, was er getragen hat.
     */
    public final BooleanSetting logoutOnPlayer =
            new BooleanSetting("Logout On Player", true);

    /** Ab welcher Entfernung ein Spieler als nah gilt. */
    public final NumberSetting playerRange =
            new NumberSetting("Player Range", 48, 16, 128, 8);

    public NetheriteFarmerModule() {
        super("Netherite Farmer", Category.BOTS);
        addSetting(searchRange);
        addSetting(maxY);
        addSetting(eatBelow);
        addSetting(gappleBelow);
        addSetting(avoidLava);
        addSetting(repairBelow);
        addSetting(bottleDelay);
        addSetting(keepTotem);
        addSetting(armorBelow);
        addSetting(afkWhenDone);
        addSetting(logoutOnPlayer);
        addSetting(playerRange);
    }

    @Override
    public void onDisable() {
        // Beim Ausschalten alles loslassen, sonst laeuft der Spieler weiter.
        com.vortex.client.bot.NetheriteFarmer.stop();
    }
}
