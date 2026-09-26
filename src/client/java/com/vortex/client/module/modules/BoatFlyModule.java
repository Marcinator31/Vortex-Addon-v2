package com.vortex.client.module.modules;

import com.vortex.client.core.setting.BooleanSetting;
import com.vortex.client.core.setting.NumberSetting;
import com.vortex.client.module.Module;

/**
 * Mit dem Boot fliegen.
 *
 * Im Boot sitzen und das Modul einschalten: W/S/A/D fahren in Blickrichtung,
 * Springen steigt, Sprinttaste (Strg) sinkt. Ohne Taste schwebt das Boot.
 *
 *   Speed           Geschwindigkeit waagerecht, Bloecke pro Sekunde
 *   Vertical Speed  Steigen/Sinken, Bloecke pro Sekunde
 *   No Clip         durch Bloecke fliegen -- NUR im Einzelspieler (und als
 *                   LAN-Gastgeber). Auf fremden Servern prueft deren Code
 *                   die Bewegung des Boots und setzt es zurueck; das kann
 *                   kein Client aendern. Siehe BoatNoClipServerMixin.
 *   Anti Kick       alle zwei Sekunden ein kleines Stueck absinken -- viele
 *                   Server werfen Fahrzeuge, die zu lange in der Luft stehen
 *   Face Camera     das Boot dreht sich mit deiner Blickrichtung
 */
public class BoatFlyModule extends Module {

    public final NumberSetting speed = new NumberSetting("Speed", 20, 1, 80, 1);
    public final NumberSetting verticalSpeed = new NumberSetting("Vertical Speed", 10, 1, 40, 1);
    public final BooleanSetting noClip = new BooleanSetting("No Clip (Singleplayer)", false);
    public final BooleanSetting antiKick = new BooleanSetting("Anti Kick", true);
    public final BooleanSetting faceCamera = new BooleanSetting("Face Camera", true);

    @Override
    protected void onDisable() {
        com.vortex.client.cheat.MoveCheats.bootAus();
    }

    public BoatFlyModule() {
        super("Boat Fly", Category.CHEATS);
        addSetting(speed);
        addSetting(verticalSpeed);
        addSetting(noClip);
        addSetting(antiKick);
        addSetting(faceCamera);
    }

}
