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
 * raeumt den Turm danach wieder ab. Seit 2.33 mit Wegfindung, eigenem Essen,
 * Einlagern in Truhen und geduldigem Einsammeln der Setzlinge; fehlt beim
 * Faellen ein Setzling, wird spaeter nachgepflanzt.
 */
public class TreeFarmerModule extends Module {

    public final NumberSetting range = new NumberSetting("Range", 16, 4, 32, 1);
    public final BooleanSetting replant = new BooleanSetting("Replant", true);
    public final BooleanSetting collect = new BooleanSetting("Collect Drops", true);
    public final BooleanSetting useAxe = new BooleanSetting("Use Axe", true);
    public final BooleanSetting pillar = new BooleanSetting("Pillar Up For Tall Trees", true);
    public final BooleanSetting boneMeal = new BooleanSetting("Use Bone Meal", false);
    public final BooleanSetting store = new BooleanSetting("Store In Chests", true);
    public final BooleanSetting eat = new BooleanSetting("Eat When Hungry", true);
    public final BooleanSetting stayNearStart = new BooleanSetting("Stay Near Start", true);
    public final BooleanSetting defend = new BooleanSetting("Defend Yourself", true);
    public final NumberSetting stopHealth = new NumberSetting("Stop Below Health", 6, 0, 18, 1);
    public final NumberSetting playerPause = new NumberSetting("Pause If Player Within", 0, 0, 64, 4);

    public TreeFarmerModule() {
        super("Tree Farmer", Category.BOTS);
        addSetting(range);
        addSetting(replant);
        addSetting(collect);
        addSetting(useAxe);
        addSetting(pillar);
        addSetting(boneMeal);
        addSetting(store);
        addSetting(eat);
        addSetting(stayNearStart);
        addSetting(defend);
        addSetting(stopHealth);
        addSetting(playerPause);
    }

    /** Statuszeile auf der Bot-Seite. */
    public String getStatus() {
        return com.vortex.client.bot.TreeFarmer.status();
    }
}
