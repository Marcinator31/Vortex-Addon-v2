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
 * NUR IM NETHER: ausserhalb bleibt er aus und sagt es einmal im Chat.
 * Ancient Debris gibt es nirgendwo sonst.
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

    /**
     * Hoehe, auf der der Stollen gegraben wird.
     *
     * 15 ist ueblich: Ancient Debris kommt zwischen Y 8 und 22 vor, mit der
     * groessten Haeufigkeit um 15. Auf dieser Hoehe gibt es ausserdem kaum
     * noch Lavaseen -- die liegen meist tiefer.
     */
    public final NumberSetting mineY =
            new NumberSetting("Mine Y", 15, 8, 22, 1);

    /**
     * Wie weit um den Stollen herum nach freigelegtem Debris gesucht wird.
     *
     * 20 Bloecke: sieht der Client Debris in diesem Umkreis, geht der Bot
     * hin und baut es ab. Weiter entferntes wird nicht verfolgt -- der Weg
     * dorthin kostet mehr, als der Fund einbringt, und fuehrt oft durch
     * unbekanntes Gelaende.
     *
     * Auf Servern mit Chunk-Schutz liefert der Server die Bloecke hinter
     * Stein ohnehin nicht aus. Dann bleibt es beim blinden Graben auf der
     * eingestellten Hoehe -- und diese Reichweite greift nur, wenn beim
     * Graben etwas freigelegt wird.
     */
    public final NumberSetting pickupRange =
            new NumberSetting("Debris Range", 20, 4, 32, 2);

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

    /**
     * Wie schnell sich der Blick hoechstens dreht (Grad je Tick).
     *
     * 0 = sofort umschnappen. Das ist die alte Fassung -- und sie war
     * unbrauchbar: mehrere Stellen setzten den Blick im selben Tick
     * nacheinander, wodurch sich der Kopf im Kreis drehte und man nicht mehr
     * erkennen konnte, was der Bot gerade tut.
     *
     * Mit einem Wert dreht sich der Blick gleichmaessig auf das Ziel zu.
     * Nebeneffekt: gegenlaeufige Ziele heben sich nicht mehr gegenseitig auf.
     */
    public final NumberSetting turnSpeed =
            new NumberSetting("Turn Speed (deg/tick)", 12, 0, 90, 2);

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
        addSetting(mineY);
        addSetting(pickupRange);
        addSetting(eatBelow);
        addSetting(gappleBelow);
        addSetting(turnSpeed);
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
