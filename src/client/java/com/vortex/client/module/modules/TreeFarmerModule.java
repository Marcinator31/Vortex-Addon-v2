package com.vortex.client.module.modules;

import com.vortex.client.core.setting.BooleanSetting;
import com.vortex.client.core.setting.NumberSetting;
import com.vortex.client.module.Module;

/**
 * Tree Farmer: faellt Baeume in der Umgebung, pflanzt Setzlinge nach und
 * sammelt das Holz ein.
 *
 * Nur echte Baeume (mit natuerlichem Laub) -- Holzhaeuser bleiben stehen.
 * Nur Baeume mit einem Stamm (keine 2x2-Riesen). Ist der Stamm hoeher als
 * die Reichweite, baut der Bot sich mit Bloecken aus der Hotbar hoch und
 * raeumt den Turm danach wieder ab.
 */
public class TreeFarmerModule extends Module {

    public final NumberSetting range = new NumberSetting("Range", 16, 4, 32, 1);
    public final BooleanSetting replant = new BooleanSetting("Replant", true);
    public final BooleanSetting collect = new BooleanSetting("Collect Drops", true);
    public final BooleanSetting useAxe = new BooleanSetting("Use Axe", true);
    public final BooleanSetting pillar = new BooleanSetting("Pillar Up For Tall Trees", true);

    public TreeFarmerModule() {
        super("Tree Farmer", Category.BOTS);
        addSetting(range);
        addSetting(replant);
        addSetting(collect);
        addSetting(useAxe);
        addSetting(pillar);
    }
}
