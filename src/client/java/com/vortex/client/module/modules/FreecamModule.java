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
     * Render-Anker: spawnt eine unsichtbare Kamera-Entity und macht sie zur
     * aktiven Kamera. Das verbessert das Chunk-Rendering unter der Erde, hat aber
     * einen Haken: der echte Spieler gilt dann nicht mehr als "Kamera", woran
     * Minecraft u.a. das Senden der Bewegungspakete koppelt -- dadurch kann der
     * Spieler nach dem Beenden haengen bleiben.
     *
     * Standard AUS: sicheres Verhalten. Fuer bessere Sicht unter der Erde sorgt
     * ohnehin die automatische Helligkeit in der Freecam.
     */
    public final BooleanSetting renderAnchor =
            new BooleanSetting("Render Anchor", false);

    /**
     * Dreht den Spieler mit der Kamera mit.
     *
     * AUS (Standard): der Koerper bleibt stehen, wie er stand -- nur die
     * Kamera dreht sich. Das ist der Sinn einer Freecam und verraet auf einem
     * Server nichts.
     *
     * AN: der Spieler dreht sich mit. Nuetzlich, um sich selbst aus einer
     * anderen Richtung zu sehen -- aber jeder Server sieht diese Drehung.
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

    /**
     * Mehr Sicht: Minecraft blendet Chunk-Abschnitte aus, die es fuer
     * verdeckt haelt. Von oben, aus Hoehlen heraus oder beim schnellen Fliegen
     * fehlen dadurch ganze Stuecke. AN: alles im Blickfeld wird gezeichnet
     * (kostet etwas Leistung).
     */
    public final BooleanSetting noCulling =
            new BooleanSetting("No Culling", true);

    /** Kein Nebel in der Freecam -- auch nicht durch Blindheit/Dunkelheit oder Wasser. */
    public final BooleanSetting noFog =
            new BooleanSetting("No Fog", true);

    /**
     * Overlays des Spielers weglassen: Kuerbiskopf, Pulverschnee, Feuer,
     * Wasser, "Kopf im Block", Vignette, Portal-Schwindel.
     */
    public final BooleanSetting hideOverlays =
            new BooleanSetting("Hide Overlays", true);

    public FreecamModule() {
        super("Freecam", Category.CHEATS);
        addSetting(speed);
        addSetting(sprintMult);
        addSetting(renderAnchor);
        addSetting(rotatePlayer);
        addSetting(showPlayer);
        addSetting(noCulling);
        addSetting(noFog);
        addSetting(hideOverlays);
        addSetting(scrollSpeed);
        addSetting(horizontal);
        addSetting(smooth);
        addSetting(disableOnDamage);
        addSetting(keepSneaking);
        addSetting(fullbright);
        addSetting(showInfo);
    }
}
