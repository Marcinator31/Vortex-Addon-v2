package com.vortex.client.module.modules;

import com.vortex.client.core.setting.BooleanSetting;
import com.vortex.client.core.setting.KeySetting;
import com.vortex.client.core.setting.NumberSetting;
import com.vortex.client.module.Module;
import org.lwjgl.glfw.GLFW;

/**
 * Freecam: loest die Kamera vom Spieler. Mit der eingestellten Taste schaltet
 * man die freie Kamera an/aus und fliegt dann mit den Bewegungstasten +
 * Springen/Schleichen herum, das Mausrad regelt das Tempo. Der Spieler bleibt
 * dabei stehen und meldet sich weiter ganz normal beim Server.
 *
 * Die eigentliche Logik steckt in der Freecam-Klasse + CameraMixin. Dieses
 * Modul haelt nur die Tasten-Einstellung (in der GUI aenderbar).
 */
public class FreecamModule extends Module {


    // Fluggeschwindigkeit (Bloecke pro Sekunde) und Multiplikator, solange die
    // Sprint-Taste gehalten wird.
    public final NumberSetting speed =
            new NumberSetting("Speed", 10.0, 1.0, 50.0, 1.0);
    public final NumberSetting sprintMult =
            new NumberSetting("Sprint Multiplier", 3.0, 1.0, 10.0, 0.5);

    /**
     * Den Spieler festhalten (kein Rueckstoss, kein Treiben im Wasser, kein
     * Rutschen auf Eis).
     *
     * STANDARD AUS -- und das mit Absicht: Anti-Cheats wie Grim rechnen jede
     * Bewegung nach. Wer Rueckstoss bekommt und stehen bleibt, sieht aus wie
     * "Anti-Knockback" und wird zurueckgesetzt oder markiert. Ohne diese
     * Einstellung verhaelt sich der Koerper genau wie bei jemandem, der
     * einfach keine Taste drueckt.
     *
     * (Ersetzt "Render Anchor": damit schickte der Spieler gar keine
     * Bewegungspakete mehr -- noch auffaelliger. Unter der Erde rendert die
     * Freecam inzwischen auch ohne sauber.)
     */
    public final BooleanSetting holdPosition =
            new BooleanSetting("Hold Position", false);

    /**
     * Dreht den Spieler mit der Kamera mit.
     *
     * AUS (Standard): der Koerper bleibt stehen, wie er stand -- nur die
     * Kamera dreht sich. Das ist der Sinn einer Freecam und verraet auf einem
     * Server nichts.
     *
     * AN: der Spieler dreht sich mit. Nuetzlich, um sich selbst aus einer
     * anderen Richtung zu sehen -- aber jeder Server sieht diese Drehung, und
     * Abbauen/Angreifen zielt dann dorthin, wohin die Kamera schaut (vom
     * Spieler aus). Die Drehung kommt direkt von der Maus, also mit denselben
     * Schritten wie normales Umschauen.
     */
    public final BooleanSetting rotatePlayer =
            new BooleanSetting("Rotate Player", false);

    /**
     * Den eigenen Spieler in der Freecam anzeigen.
     *
     * AN (Standard): man sieht sich selbst dort stehen, wo man geblieben ist.
     * Das ist meist gewollt -- sonst weiss man nicht, wo der eigene Koerper
     * steht, und fliegt beim Beenden ueberraschend zurueck.
     *
     * Seit Client 4.6.1 ohne eigene Kamera-Entity: die Kamera gilt nur als
     * "abgeloest" wie in F5. Der Spieler bleibt die Kamera und schickt weiter
     * ganz normal seine Bewegungspakete -- vorher verstummte er dabei auf dem
     * Server, was Anti-Cheats auffaellt.
     */
    public final BooleanSetting showPlayer =
            new BooleanSetting("Show Player", true);

    /** Mausrad aendert in der Freecam die Geschwindigkeit (statt den Hotbar-Slot). */
    public final BooleanSetting scrollSpeed =
            new BooleanSetting("Scroll Changes Speed", true);

    /**
     * Wie Kreativ-Flug: W fliegt waagerecht, hoch/runter nur mit Springen und
     * Schleichen. AUS: man fliegt dorthin, wohin man schaut.
     */
    public final BooleanSetting horizontal =
            new BooleanSetting("Horizontal Movement", false);

    /** Weich anfahren und abbremsen -- fuer Aufnahmen und Kamerafahrten. */
    public final BooleanSetting smooth =
            new BooleanSetting("Smooth Movement", false);

    /** Bei Schaden sofort zurueck in den eigenen Koerper. */
    public final BooleanSetting disableOnDamage =
            new BooleanSetting("Disable On Damage", true);

    /**
     * Schleicht man beim Einschalten, bleibt der Spieler geduckt. Vorher stand
     * er auf -- an einer Kante (Bruecke, Dach) fiel man so leicht herunter.
     */
    public final BooleanSetting keepSneaking =
            new BooleanSetting("Keep Sneaking", true);

    /** Automatisch hell, solange die Freecam laeuft. */
    public final BooleanSetting fullbright =
            new BooleanSetting("Fullbright", true);

    /** Zeile oben: Tempo und Abstand zum eigenen Koerper. */
    public final BooleanSetting showInfo =
            new BooleanSetting("Show Info", true);

    public FreecamModule() {
        super("Freecam", Category.CHEATS);
        addSetting(speed);
        addSetting(sprintMult);
        addSetting(holdPosition);
        addSetting(rotatePlayer);
        addSetting(showPlayer);
        addSetting(scrollSpeed);
        addSetting(horizontal);
        addSetting(smooth);
        addSetting(disableOnDamage);
        addSetting(keepSneaking);
        addSetting(fullbright);
        addSetting(showInfo);
    }
}
