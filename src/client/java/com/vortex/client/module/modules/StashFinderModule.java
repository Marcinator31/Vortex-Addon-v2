package com.vortex.client.module.modules;

import com.vortex.client.core.setting.BooleanSetting;
import com.vortex.client.core.setting.ColorSetting;
import com.vortex.client.core.setting.NumberSetting;
import com.vortex.client.module.Module;

/**
 * Stash Finder: findet versteckte Lager anhand von Truhen, Faessern und
 * Shulkern, die dicht beieinander stehen.
 *
 * WAS SICH GEAENDERT HAT (2.28)
 *  - Gezaehlt wird nur echtes Lager (Truhen, Faesser, Shulker). Vorher zaehlte
 *    alles mit Inventar -- Oefen, Trichter, Kruege, Plattenspieler, Braustaende.
 *    Trial Chambers und Doerfer loesten deshalb Fehlalarme aus.
 *  - Nicht mehr "pro Chunk", sondern als GRUPPE: Container, die hoechstens
 *    "Cluster Radius" Bloecke auseinander stehen, gehoeren zusammen. Ein Lager
 *    genau auf einer Chunkgrenze wurde vorher halbiert und oft nicht erkannt.
 *  - Tracer und Box zeigen auf die echte Mitte, auch unter der Erde (vorher
 *    immer auf Y 64).
 *  - Gefundene Stashes bleiben gemerkt, auch wenn der Chunk wieder entladen
 *    wird: Liste mit /stashes, Log-Datei, auf Wunsch als Waypoint.
 *
 * Es werden nur GELADENE Chunks ausgewertet (Sichtweite) -- man fliegt die
 * Welt ab und der Finder schlaegt an, sobald ein Lager in Reichweite kommt.
 */
public class StashFinderModule extends Module {

    /** Ab wie vielen Lager-Containern (in einer Gruppe) es ein Stash ist. */
    public final NumberSetting threshold =
            new NumberSetting("Threshold", 10, 4, 64, 1);
    /** Wie weit Container auseinander stehen duerfen, um zusammen zu zaehlen. */
    public final NumberSetting clusterRadius =
            new NumberSetting("Cluster Radius", 12, 4, 32, 1);
    /** Shulker kommen nie natuerlich vor -- sie zaehlen doppelt. */
    public final BooleanSetting shulkerDouble =
            new BooleanSetting("Shulkers Count Double", true);
    public final BooleanSetting tracer = new BooleanSetting("Tracers", true);
    public final ColorSetting tracerColor = new ColorSetting("Tracer Color", 0xFFFF00FF);
    /** Rahmen um das gefundene Lager. */
    public final BooleanSetting box = new BooleanSetting("Box", true);
    /** Filtert Stashes nach einem bestimmten Item (z.B. "diamond"). Nur Stashes, die dieses Item enthalten, werden angezeigt. */
    public final com.vortex.client.core.setting.StringSetting contentFilter = new com.vortex.client.core.setting.StringSetting("Content Filter", "");
    /** Tracer auch zu gemerkten Stashes, die nicht mehr geladen sind. */
    public final BooleanSetting remember = new BooleanSetting("Tracers To Remembered", true);

    public final BooleanSetting notify = new BooleanSetting("Chat Message", true);
    public final BooleanSetting sound = new BooleanSetting("Sound", true);
    /** Fuer jeden neuen Stash einen Waypoint (Art "Lager") anlegen. */
    public final BooleanSetting waypoint = new BooleanSetting("Add Waypoint", false);
    /** Funde in vortexclient/stashes.txt mitschreiben. */
    public final BooleanSetting logFile = new BooleanSetting("Log To File", true);

    public StashFinderModule() {
        super("Stash Finder", Category.CHEATS);
        addSetting(threshold);
        addSetting(clusterRadius);
        addSetting(shulkerDouble);
        addSetting(tracer);
        addSetting(tracerColor);
        addSetting(box);
        addSetting(contentFilter);
        addSetting(remember);
        addSetting(notify);
        addSetting(sound);
        addSetting(waypoint);
        addSetting(logFile);
    }

    public int getThreshold() { return threshold.getInt(); }
    public boolean tracerEnabled() { return tracer.get(); }
    public int getTracerColor() { return tracerColor.get(); }
    public boolean notifyEnabled() { return notify.get(); }
}
