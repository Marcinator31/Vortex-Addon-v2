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

    public NetheriteFarmerModule() {
        super("Netherite Farmer", Category.BOTS);
        addSetting(mineY);
        addSetting(debrisRange);
        addSetting(turnSpeed);
        addSetting(avoidLava);
        addSetting(afkWhenOut);
        addSetting(afkOnPlayer);
        addSetting(playerRange);
    }

    @Override
    public void onDisable() {
        // Beim Ausschalten alles loslassen, sonst laeuft der Spieler weiter.
        com.vortex.client.bot.NetheriteFarmer.stop();
    }
}
