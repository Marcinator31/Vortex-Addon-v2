package com.vortex.client.module.modules;

import com.vortex.client.core.setting.BooleanSetting;
import com.vortex.client.core.setting.ModeSetting;
import com.vortex.client.core.setting.NumberSetting;
import com.vortex.client.module.Module;

/**
 * Surround: Obsidian rund um die Fuesse (seit Addon 2.41).
 *
 * Ein End-Kristall braucht Obsidian oder Grundgestein als Unterlage und Platz
 * daneben. Stehen um deine Fuesse vier Obsidianbloecke, kann direkt neben dir
 * keiner mehr hin -- und Kristalle weiter weg treffen dich deutlich schwaecher,
 * weil der Block die Explosion abschirmt. Zerstoert jemand einen Block, wird er
 * sofort wieder gesetzt.
 *
 * Die Logik steht in cheat/Surround.
 */
public class SurroundModule extends Module {

    /** Welche Bloecke: nur Obsidian oder alles, was Explosionen aushaelt. */
    public final ModeSetting blocks = new ModeSetting("Blocks", 1, "Obsidian", "Any Blast-Proof");
    /** Hoechstens so viele Bloecke je Tick. */
    public final NumberSetting perTick = new NumberSetting("Blocks Per Tick", 4, 1, 8, 1);
    /** Beim Einschalten in die Blockmitte ruecken, damit vier Bloecke reichen. */
    public final BooleanSetting center = new BooleanSetting("Center", true);
    /** Ausschalten, sobald du den Block verlaesst oder springst. */
    public final BooleanSetting offWhenMoving = new BooleanSetting("Turn Off When Moving", true);
    /** Auch auf Kopfhoehe und oben drueber (komplett eingemauert, gegen Face Place). */
    public final BooleanSetting head = new BooleanSetting("Protect Head", false);
    /** Steht ein Kristall auf einem Platz, ihn zerschlagen (nur wenn er dich nicht toetet). */
    public final BooleanSetting breakCrystals = new BooleanSetting("Break Crystals In The Way", true);
    public final BooleanSetting swing = new BooleanSetting("Swing", true);

    /**
     * Restock (seit 2.42): fehlt in der Hotbar Nachschub (unter 16 Stueck),
     * wird er aus dem Inventar geholt -- siehe cheat/Nachschub.
     */
    public final BooleanSetting restock = new BooleanSetting("Restock", true);

    public SurroundModule() {
        super("Surround", Category.CHEATS);
        addSetting(blocks);
        addSetting(perTick);
        addSetting(center);
        addSetting(offWhenMoving);
        addSetting(head);
        addSetting(breakCrystals);
        addSetting(swing);
        addSetting(restock);
    }

    @Override
    protected void onEnable() {
        com.vortex.client.cheat.Surround.start(this);
    }
}
