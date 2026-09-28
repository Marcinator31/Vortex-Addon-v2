package com.vortex.client.module.modules;

import com.vortex.client.core.setting.BooleanSetting;
import com.vortex.client.core.setting.ColorSetting;
import com.vortex.client.core.setting.NumberSetting;
import com.vortex.client.module.Module;

/**
 * New Chunks: zeigt, welche Chunks gerade zum ersten Mal erzeugt wurden (rot)
 * und welche schon einmal geladen waren (gruen).
 *
 * Woran man es sieht: in einem frischen Chunk beginnt Wasser/Lava erst zu
 * fliessen, wenn er erzeugt wird -- der Server schickt dann Block-Updates fuer
 * fliessende Fluessigkeit. Ein alter Chunk hat schon fertig geflossene
 * Fluessigkeit. Ohne Wasser oder Lava im Chunk laesst sich nichts sagen.
 */
public class NewChunksModule extends Module {

    public final BooleanSetting showNew = new BooleanSetting("Show New", true);
    public final BooleanSetting showOld = new BooleanSetting("Show Old", false);
    public final ColorSetting newColor = new ColorSetting("New Color", 0xFFFF3030);
    public final ColorSetting oldColor = new ColorSetting("Old Color", 0xFF30FF60);
    public final BooleanSetting followPlayer = new BooleanSetting("Draw At My Height", true);
    public final NumberSetting renderY = new NumberSetting("Draw Height", 63, -64, 320, 1);

    public NewChunksModule() {
        super("New Chunks", Category.CHEATS);
        addSetting(showNew);
        addSetting(showOld);
        addSetting(newColor);
        addSetting(oldColor);
        addSetting(followPlayer);
        addSetting(renderY);
    }
}
