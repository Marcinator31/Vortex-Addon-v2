package com.vortex.client.module.modules;

import com.vortex.client.core.setting.BooleanSetting;
import com.vortex.client.core.setting.ModeSetting;
import com.vortex.client.module.Module;

/**
 * Ghost View -- in F5 so aussehen, als haette man Unsichtbarkeit.
 *
 * Der eigene Koerper verdeckt in der Dritt-Person-Ansicht die Sicht. Mit
 * Unsichtbarkeit faellt er weg, und mit der Kamera in der Wand sieht man
 * durch die Bloecke -- die bekannte F5-Taktik. Dieses Modul macht beides,
 * ohne Trank, und NUR fuer dich: der Server und andere Spieler merken nichts,
 * es wird nur anders gezeichnet.
 *
 *   Mode         Invisible = wie mit Unsichtbarkeit (Ruestung und Items
 *                bleiben sichtbar, wie bei echter Unsichtbarkeit)
 *                Ghost     = durchscheinend, so wie Teammitglieder einen
 *                unsichtbaren Spieler sehen
 *   Camera Clip  die F5-Kamera wird nicht mehr von Waenden herangezogen,
 *                sondern geht durch Bloecke hindurch
 *   Wall Vision  steckt die Kamera in einem Block, trotzdem die Hoehlen und
 *                Gaenge dahinter zeichnen (wie ein Zuschauer)
 */
public class GhostViewModule extends Module {

    public final ModeSetting mode = new ModeSetting("Mode", 0, "Invisible", "Ghost");
    public final BooleanSetting cameraClip = new BooleanSetting("Camera Clip", true);
    public final BooleanSetting wallVision = new BooleanSetting("Wall Vision", true);

    public GhostViewModule() {
        super("Ghost View", Category.CHEATS);
        addSetting(mode);
        addSetting(cameraClip);
        addSetting(wallVision);
    }

    /** Das Modul, wenn es an ist -- sonst null. Fuer die Mixins. */
    public static GhostViewModule aktiv() {
        try {
            GhostViewModule m = com.vortex.client.module.ModuleManager.INSTANCE.get(GhostViewModule.class);
            return (m != null && m.isEnabled()) ? m : null;
        } catch (Throwable t) {
            return null;
        }
    }
}
