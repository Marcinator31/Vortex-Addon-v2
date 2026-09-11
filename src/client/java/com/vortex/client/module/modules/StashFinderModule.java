package com.vortex.client.module.modules;

import com.vortex.client.core.setting.BooleanSetting;
import com.vortex.client.core.setting.ColorSetting;
import com.vortex.client.core.setting.NumberSetting;
import com.vortex.client.module.Module;

/**
 * Stash Finder: erkennt versteckte Lager (Stashes) anhand der CONTAINER-DICHTE.
 *
 * Funktionsweise (verlaesslich, keine Vermutung): Ein Stash besteht aus vielen
 * Containern (Truhen, Shulker, Faesser, Trichter, Spender, ...) auf engem Raum.
 * In der normalen Welt stehen selten viele Container zusammen. Der Finder zaehlt
 * daher die Container-Block-Entities pro geladenem Chunk. Ueberschreitet die Zahl
 * eine Schwelle, gilt der Chunk als Stash und wird gemeldet.
 *
 * Wichtig: Es werden nur GELADENE Chunks ausgewertet (Render-Distanz). Das ist
 * kein Wallhack ueber die Sichtweite hinaus -- man fliegt/laeuft durch die Welt
 * und der Finder schlaegt automatisch an, sobald ein Stash in Reichweite kommt.
 *
 * Bei einem Treffer: einmalige Chat-Benachrichtigung mit Koordinaten + ein
 * Tracer zum Stash (solange das Modul an ist und der Chunk geladen bleibt).
 */
public class StashFinderModule extends Module {

    // Ab wie vielen Containern in einem Chunk es als Stash gilt.
    public final NumberSetting threshold =
            new NumberSetting("Threshold", 10, 4, 64, 1);
    // Tracer zum Stash zeichnen?
    public final BooleanSetting tracer = new BooleanSetting("Tracers", true);
    public final ColorSetting tracerColor = new ColorSetting("Tracer Color", 0xFFFF00FF);
    // Chat-Benachrichtigung bei Fund?
    public final BooleanSetting notify = new BooleanSetting("Chat Message", true);

    public StashFinderModule() {
        super("Stash Finder", Category.CHEATS);
        addSetting(threshold);
        addSetting(tracer);
        addSetting(tracerColor);
        addSetting(notify);
    }

    public int getThreshold() {
        return threshold.getInt();
    }

    public boolean tracerEnabled() {
        return tracer.get();
    }

    public int getTracerColor() {
        return tracerColor.get();
    }

    public boolean notifyEnabled() {
        return notify.get();
    }
}
