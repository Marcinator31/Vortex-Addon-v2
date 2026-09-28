package com.vortex.client.module.modules;

import com.vortex.client.core.setting.BooleanSetting;
import com.vortex.client.core.setting.NumberSetting;
import com.vortex.client.module.Module;

/**
 * Sus Chunks: markiert Chunks mit Spuren von Spielern -- ein klassisches
 * Base-Hunting-Werkzeug.
 *
 * Jedes Block-Entity im Chunk bekommt ein Gewicht danach, wie sicher es von
 * einem Spieler stammt: Beacon, Shulker, Trichter, Schilder, Banner viel;
 * Truhen, Oefen, Betten wenig (gibt es auch in Doerfern); Sculk, Spawner,
 * Bienennester, Kruege, verdaechtiger Sand gar nicht (natuerlich). Vorher
 * zaehlte jedes Block-Entity mit -- Antike Staedte und Bienenwaelder
 * leuchteten heller als echte Basen.
 *
 * Die Markierung sitzt auf der Hoehe, auf der die Spuren liegen (eine Base
 * unter der Erde ist so sofort als solche zu erkennen), Farbe gruen -> rot.
 *
 * Es werden nur GELADENE Chunks ausgewertet (Sichtweite).
 */
public class SusChunksModule extends Module {

    /** Ab welchem Wert ein Chunk markiert wird (1 Shulker = 8, 1 Schild = 2). */
    public final NumberSetting minScore = new NumberSetting("Min Score", 8, 1, 100, 1);
    /** Ab diesem Wert volle Farbe (rot). */
    public final NumberSetting maxScore = new NumberSetting("Max Score", 60, 10, 300, 5);
    /** Saeule ueber die ganze Welthoehe statt nur um die Spuren. */
    public final BooleanSetting fullHeight = new BooleanSetting("Full Height", false);
    /** Chat-Meldung, wenn ein Chunk "Max Score" erreicht. */
    public final BooleanSetting notify = new BooleanSetting("Chat Message", false);

    public SusChunksModule() {
        super("Sus Chunks", Category.CHEATS);
        addSetting(minScore);
        addSetting(maxScore);
        addSetting(fullHeight);
        addSetting(notify);
    }

    public int getMinScore() { return minScore.getInt(); }
    public int getMaxScore() { return maxScore.getInt(); }
}
