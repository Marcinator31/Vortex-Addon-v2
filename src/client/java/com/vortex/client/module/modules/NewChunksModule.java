package com.vortex.client.module.modules;

import com.vortex.client.core.setting.BooleanSetting;
import com.vortex.client.core.setting.ColorSetting;
import com.vortex.client.core.setting.NumberSetting;
import com.vortex.client.module.Module;

/**
 * New Chunks: zeigt, welche Chunks gerade zum ersten Mal erzeugt wurden (rot)
 * und welche es schon vorher gab (gruen).
 *
 * Seit 2.40 ueber die Blockpalette, die der Server mit jedem Chunk schickt
 * (siehe ChunkPalette) -- im Test 1 Fehler bei 437 alten Chunks, alle neuen
 * weit draussen erkannt. Bis 2.39 ging es ueber fliessendes Wasser: das traf
 * nur Chunks mit Wasser oder Lava und hielt dabei viele neue fuer alt.
 */
public class NewChunksModule extends Module {

    public final BooleanSetting showNew = new BooleanSetting("Show New", true);
    public final BooleanSetting showOld = new BooleanSetting("Show Old", false);
    public final ColorSetting newColor = new ColorSetting("New Color", 0xFFFF3030);
    public final ColorSetting oldColor = new ColorSetting("Old Color", 0xFF30FF60);
    public final BooleanSetting followPlayer = new BooleanSetting("Draw At My Height", true);
    public final NumberSetting renderY = new NumberSetting("Draw Height", 63, -64, 320, 1);
    /** Wie kraeftig die Flaechen gefuellt sind (0 = nur Umriss). */
    public final NumberSetting fillOpacity = new NumberSetting("Fill Opacity", 25, 0, 80, 5);

    public NewChunksModule() {
        super("New Chunks", Category.CHEATS);
        addSetting(showNew);
        addSetting(showOld);
        addSetting(newColor);
        addSetting(oldColor);
        addSetting(followPlayer);
        addSetting(renderY);
        addSetting(fillOpacity);
    }
}
