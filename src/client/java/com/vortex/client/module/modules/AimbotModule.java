package com.vortex.client.module.modules;

import com.vortex.client.core.setting.BooleanSetting;
import com.vortex.client.core.setting.ModeSetting;
import com.vortex.client.core.setting.NumberSetting;
import com.vortex.client.module.Module;

/**
 * Aimbot (Nahkampf): zieht die Blickrichtung sanft in Richtung des besten
 * Ziel-Spielers. Der Blick springt NICHT schlagartig, sondern gleitet -- wie
 * viel pro Tick gezogen wird (Staerke) und wie schnell/weich das Gleiten ist,
 * sind einstellbar.
 *
 * Advanced-Einstellungen:
 *  - Staerke: Anteil (0..100%), um den der Blick pro Tick Richtung Ziel gezogen
 *    wird. 100% = sofort auf dem Ziel, kleine Werte = sanftes Nachziehen.
 *  - Glaettung: zusaetzliche Weichheit -- teilt die Bewegung in kleinere
 *    Schritte, damit die Drehung nicht mechanisch wirkt.
 *  - Reichweite: nur Spieler innerhalb dieser Distanz (Bloecke) werden anvisiert.
 *  - FOV: nur Ziele innerhalb dieses Blickwinkels (Grad) werden erfasst -- so
 *    dreht sich der Aimbot nicht ruckartig um 180 Grad zu jemandem hinter dir.
 *  - Ziel-Punkt: worauf gezielt wird (Kopf / Koerper / Fuesse).
 *  - Ziel-Wahl: nach kleinstem Winkel (was am ehesten "gemeint" ist) oder nach
 *    kleinster Distanz.
 *  - Nur bei Angriff: nur ziehen, solange die Angriffstaste gehalten wird.
 *  - Max. Drehung/Tick: begrenzt, wie viele Grad pro Tick maximal gedreht werden
 *    (haelt selbst hohe Staerke unauffaellig und menschlicher).
 */
public class AimbotModule extends Module {

    public final NumberSetting strength =
            new NumberSetting("Strength", 45, 1, 100, 1);
    public final NumberSetting smoothness =
            new NumberSetting("Smoothing", 3, 1, 10, 1);
    public final NumberSetting range =
            new NumberSetting("Range", 4.5, 2.0, 6.0, 0.5);
    public final NumberSetting fov =
            new NumberSetting("FOV", 60, 5, 180, 5);
    public final ModeSetting targetPoint =
            new ModeSetting("Aim Point", 0, "Nearest", "Kopf", "Koerper", "Fuesse");
    public final ModeSetting targetChoice =
            new ModeSetting("Target Priority", 0, "Winkel", "Distanz");
    public final NumberSetting maxTurn =
            new NumberSetting("Max Turn / Tick", 30, 1, 180, 1);
    public final BooleanSetting onlyWhenAttacking =
            new BooleanSetting("Only While Attacking", false);

    public AimbotModule() {
        super("Aimbot", Category.CHEATS);
        addSetting(strength);
        addSetting(smoothness);
        addSetting(range);
        addSetting(fov);
        addSetting(targetPoint);
        addSetting(targetChoice);
        addSetting(maxTurn);
        addSetting(onlyWhenAttacking);
    }

    public double getStrength() { return strength.get() / 100.0; }
    public int getSmoothness() { return smoothness.getInt(); }
    public double getRange() { return range.get(); }
    public double getFov() { return fov.get(); }
    public int getTargetPoint() { return targetPoint.getIndex(); }
    public boolean chooseByAngle() { return targetChoice.getIndex() == 0; }
    public double getMaxTurn() { return maxTurn.get(); }
    public boolean onlyWhenAttacking() { return onlyWhenAttacking.get(); }
}
