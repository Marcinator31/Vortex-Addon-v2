package com.vortex.client.module.modules;

import com.vortex.client.core.setting.BooleanSetting;
import com.vortex.client.core.setting.KeySetting;
import com.vortex.client.core.setting.NumberSetting;
import com.vortex.client.module.Module;
import org.lwjgl.glfw.GLFW;

/**
 * Freecam: loest die Kamera vom Spieler. Mit der eingestellten Taste schaltet
 * man die freie Kamera an/aus und fliegt dann mit WASD + Leertaste/Shift herum.
 * Der Spieler bleibt dabei stehen.
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
     */
    public final BooleanSetting showPlayer =
            new BooleanSetting("Show Player", true);

    public FreecamModule() {
        super("Freecam", Category.CHEATS);
        addSetting(speed);
        addSetting(sprintMult);
        addSetting(renderAnchor);
        addSetting(rotatePlayer);
        addSetting(showPlayer);
    }
}
