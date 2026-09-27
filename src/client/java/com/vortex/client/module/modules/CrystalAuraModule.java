package com.vortex.client.module.modules;

import com.vortex.client.core.setting.BooleanSetting;
import com.vortex.client.core.setting.ColorSetting;
import com.vortex.client.core.setting.ModeSetting;
import com.vortex.client.core.setting.NumberSetting;
import com.vortex.client.module.Module;

/**
 * Crystal Aura: setzt und sprengt Enderkristalle vollautomatisch.
 *
 * Anders als das Crystal Macro (Fadenkreuz) sucht die Aura selbst den besten
 * Platz: fuer jeden moeglichen Obsidian-/Grundgestein-Block wird der Schaden
 * am Gegner UND an dir so berechnet, wie der Server ihn rechnet (Abstand,
 * Deckung, Ruestung, Schutz, Resistenz, Schwierigkeit). Gesetzt wird nur, wo
 * der Gegner genug abbekommt und du nicht zu viel.
 *
 * Face Place: ist der Gegner fast tot oder seine Ruestung fast kaputt, reicht
 * auch wenig Schaden -- dann wird auch auf Kopfhoehe gesetzt.
 * Instant Break: ein gesetzter Kristall wird im selben Moment gesprengt, in
 * dem der Server ihn meldet -- nicht erst im naechsten Tick.
 *
 * Extremes Bann-Risiko.
 */
public class CrystalAuraModule extends Module {

    // --- Ziele ------------------------------------------------------------
    public final NumberSetting targetRange = new NumberSetting("Target Range", 10, 4, 16, 1);
    public final ModeSetting targets = new ModeSetting("Targets", 0, "Players", "Players + Mobs");
    public final ModeSetting priority = new ModeSetting("Priority", 2, "Closest", "Lowest Health", "Most Damage");
    public final NumberSetting predict = new NumberSetting("Predict Ticks", 1, 0, 5, 1);

    // --- Setzen -------------------------------------------------------------
    public final BooleanSetting place = new BooleanSetting("Place", true);
    public final NumberSetting placeRange = new NumberSetting("Place Range", 4.5, 2, 5.5, 0.1);
    public final NumberSetting wallRange = new NumberSetting("Wall Range", 3.5, 0, 5.5, 0.1);
    public final NumberSetting placeDelay = new NumberSetting("Place Delay", 1, 0, 10, 1);
    public final NumberSetting minDamage = new NumberSetting("Min Damage", 6, 1, 20, 0.5);

    // --- Sprengen -----------------------------------------------------------
    public final BooleanSetting breakIt = new BooleanSetting("Break", true);
    public final ModeSetting breakMode = new ModeSetting("Break Which", 0, "Smart", "Own Only", "All");
    public final NumberSetting breakRange = new NumberSetting("Break Range", 4.5, 2, 6, 0.1);
    public final NumberSetting breakDelay = new NumberSetting("Break Delay", 0, 0, 10, 1);
    public final BooleanSetting instantBreak = new BooleanSetting("Instant Break", true);
    public final NumberSetting inhibit = new NumberSetting("Inhibit Ticks", 3, 0, 10, 1);
    public final BooleanSetting antiWeakness = new BooleanSetting("Anti Weakness", true);

    // --- Sicherheit -------------------------------------------------------------
    public final NumberSetting maxSelfDamage = new NumberSetting("Max Self Damage", 8, 0, 36, 0.5);
    public final BooleanSetting antiSuicide = new BooleanSetting("Anti Suicide", true);
    public final NumberSetting pauseHealth = new NumberSetting("Pause Below Health", 0, 0, 20, 1);
    public final BooleanSetting pauseEating = new BooleanSetting("Pause While Eating", true);
    public final BooleanSetting pauseMining = new BooleanSetting("Pause While Mining", true);

    // --- Face Place ---------------------------------------------------------------
    public final NumberSetting facePlaceHealth = new NumberSetting("Face Place Health", 8, 0, 36, 1);
    public final NumberSetting facePlaceArmor = new NumberSetting("Face Place Armor %", 15, 0, 100, 5);
    public final NumberSetting facePlaceDamage = new NumberSetting("Face Place Min Damage", 1.5, 0.5, 6, 0.5);

    // --- Hand und Anzeige -------------------------------------------------------------
    public final ModeSetting switchMode = new ModeSetting("Switch", 2, "None", "Normal", "Silent");
    public final BooleanSetting rotate = new BooleanSetting("Rotate", false);
    public final BooleanSetting swing = new BooleanSetting("Swing", true);
    public final BooleanSetting render = new BooleanSetting("Render", true);
    public final ColorSetting color = new ColorSetting("Render Color", 0xFFB050FF);

    public CrystalAuraModule() {
        super("Crystal Aura", Category.CHEATS);
        addSetting(targetRange); addSetting(targets); addSetting(priority); addSetting(predict);
        addSetting(place); addSetting(placeRange); addSetting(wallRange); addSetting(placeDelay); addSetting(minDamage);
        addSetting(breakIt); addSetting(breakMode); addSetting(breakRange); addSetting(breakDelay);
        addSetting(instantBreak); addSetting(inhibit); addSetting(antiWeakness);
        addSetting(maxSelfDamage); addSetting(antiSuicide); addSetting(pauseHealth);
        addSetting(pauseEating); addSetting(pauseMining);
        addSetting(facePlaceHealth); addSetting(facePlaceArmor); addSetting(facePlaceDamage);
        addSetting(switchMode); addSetting(rotate); addSetting(swing); addSetting(render); addSetting(color);
    }

    @Override
    protected void onDisable() {
        com.vortex.client.cheat.CrystalAura.aus();
    }
}
