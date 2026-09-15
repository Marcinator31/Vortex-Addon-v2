package com.vortex.client.module.modules;

import com.vortex.client.core.setting.NumberSetting;
import com.vortex.client.module.Module;

/**
 * Sus Chunks: markiert Chunks mit viel Spieler-Aktivitaet als "verdaechtig"
 * (suspicious) -- ein klassisches Base-Hunting-Werkzeug.
 *
 * Idee: Von Menschen genutzte Chunks enthalten Dinge, die natuerlich generierte
 * Chunks praktisch nie in der Menge haben -- vor allem Container (Truhen,
 * Shulker, Faesser) und andere Block-Entities (Oefen, Schilder, Crafting-
 * Stationen). Der Finder berechnet daraus pro geladenem Chunk einen
 * Aktivitaets-Score und zeichnet eine farbige Chunk-Saeule als Heatmap:
 * niedrige Aktivitaet dezent, hohe Aktivitaet kraeftig -- so sieht man auf einen
 * Blick, wo wahrscheinlich eine Base ist.
 *
 * Es werden nur GELADENE Chunks ausgewertet (Render-Distanz), kein Server-
 * Exploit -- man fliegt die Welt ab und auffaellige Chunks leuchten auf.
 */
public class SusChunksModule extends Module {

    // Ab welchem Aktivitaets-Score ein Chunk ueberhaupt markiert wird.
    public final NumberSetting minScore = new NumberSetting("Min Score", 8, 1, 100, 1);
    // Score, ab dem ein Chunk als "maximal verdaechtig" (volle Farbe) gilt.
    public final NumberSetting maxScore = new NumberSetting("Max Score", 60, 10, 300, 5);

    public SusChunksModule() {
        super("Sus Chunks", Category.CHEATS);
        addSetting(minScore);
        addSetting(maxScore);
    }

    public int getMinScore() { return minScore.getInt(); }
    public int getMaxScore() { return maxScore.getInt(); }
}
