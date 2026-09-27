package com.vortex.client.module.modules;

import com.vortex.client.core.setting.BooleanSetting;
import com.vortex.client.core.setting.NumberSetting;
import com.vortex.client.module.Module;

/**
 * Netherite-Farmer.
 *
 * Graebt auf der eingestellten Hoehe einen Stollen, bis er Ancient Debris
 * trifft, sammelt die Brocken ein und haelt sich dabei selbst am Leben.
 *
 * NUR IM NETHER: ausserhalb bleibt er aus und sagt es einmal im Chat.
 *
 * ZU DEN EINSTELLUNGEN
 * Frueher gab es dreizehn. Die meisten waren Zahlen, die man einmal richtig
 * setzt und nie wieder anfasst -- Hungergrenze, Reparaturgrenze,
 * Wurfabstand. Sie standen nur im Weg.
 *
 * Geblieben sind die sieben, bei denen eine andere Wahl wirklich Sinn
 * ergibt. Alles andere ist auf bewaehrte Werte festgelegt und im Code
 * dokumentiert.
 */
public class NetheriteFarmerModule extends Module {

    /**
     * Hoehe, auf der der Stollen gegraben wird.
     *
     * 15 ist ueblich: Ancient Debris kommt zwischen Y 8 und 22 vor, am
     * haeufigsten um 15, und Lavaseen liegen meist tiefer.
     */
    public final NumberSetting mineY =
            new NumberSetting("Mine Y", 15, 8, 22, 1);

    /**
     * Wie weit entferntes Debris noch geholt wird.
     *
     * Auf Servern mit Chunk-Schutz sieht der Client ohnehin nur, was beim
     * Graben freigelegt wird -- dort wirkt die Zahl kaum.
     */
    public final NumberSetting debrisRange =
            new NumberSetting("Debris Range", 20, 4, 32, 2);

    /**
     * Wie schnell sich der Blick dreht (Grad je Tick).
     *
     * Sofortiges Umschnappen waere unbrauchbar: dann ueberschreiben sich
     * mehrere Blickziele im selben Tick und der Bot dreht sich im Kreis.
     */
    public final NumberSetting turnSpeed =
            new NumberSetting("Turn Speed", 12, 2, 45, 1);

    /**
     * Streifen-Muster: parallele Bahnen im Abstand von 3 Bloecken statt
     * geradeaus und zufaellig drehen. Legt jeden Wandblock genau einmal frei
     * und laeuft nicht durch alte Gaenge.
     */
    public final BooleanSetting stripMine =
            new BooleanSetting("Strip Mine Pattern", true);

    /** Laenge einer Bahn, bevor 3 Bloecke seitlich die naechste beginnt. */
    public final NumberSetting laneLength =
            new NumberSetting("Lane Length", 40, 12, 128, 4);

    /** Lava meiden statt hindurchzulaufen. */
    public final BooleanSetting avoidLava =
            new BooleanSetting("Avoid Lava", true);

    /** /afk senden, wenn Werkzeug, Essen oder Totem ausgehen. */
    public final BooleanSetting afkWhenOut =
            new BooleanSetting("/afk When Out", true);

    /** Auf /afk gehen, wenn ein anderer Spieler naeher kommt. */
    public final BooleanSetting afkOnPlayer =
            new BooleanSetting("/afk On Player", true);

    /** Ab welcher Entfernung ein Spieler als nah gilt. */
    public final NumberSetting playerRange =
            new NumberSetting("Player Range", 48, 16, 128, 8);

    /**
     * Totem immer in der Off-Hand -- unabhaengig davon, was der Bot gerade
     * tut. Poppt eins, liegt das naechste im folgenden Tick bereit.
     */
    public final BooleanSetting autoTotem =
            new BooleanSetting("Auto Totem", true);

    /** Ab diesem Hunger wird gegessen (20 = voll). */
    public final NumberSetting eatBelow =
            new NumberSetting("Eat Below Hunger", 16, 6, 19, 1);

    /** Ab diesem Leben wird ein goldener Apfel gegessen (20 = voll). */
    public final NumberSetting gappleBelow =
            new NumberSetting("Golden Apple Below Health", 12, 4, 18, 1);

    /** Alle 5 Minuten im Chat: wie viel Debris bisher, wie viel pro Stunde. */
    public final BooleanSetting stats =
            new BooleanSetting("Stats In Chat", true);

    /** Statuszeile fuer die Bot-Seite des Clients (dort per Reflexion abgefragt). */
    public String getStatus() {
        return com.vortex.client.bot.NetheriteFarmer.statusText();
    }

    public NetheriteFarmerModule() {
        super("Netherite Farmer", Category.BOTS);
        addSetting(mineY);
        addSetting(debrisRange);
        addSetting(turnSpeed);
        addSetting(stripMine);
        addSetting(laneLength);
        addSetting(avoidLava);
        addSetting(afkWhenOut);
        addSetting(afkOnPlayer);
        addSetting(playerRange);
        addSetting(autoTotem);
        addSetting(eatBelow);
        addSetting(gappleBelow);
        addSetting(stats);
    }

    @Override
    public void onDisable() {
        // Beim Ausschalten alles loslassen, sonst laeuft der Spieler weiter.
        com.vortex.client.bot.NetheriteFarmer.stop();
    }
}
