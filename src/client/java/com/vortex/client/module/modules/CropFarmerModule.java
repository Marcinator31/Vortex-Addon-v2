package com.vortex.client.module.modules;

import com.vortex.client.core.setting.BooleanSetting;
import com.vortex.client.core.setting.NumberSetting;
import com.vortex.client.module.Module;

/**
 * Crop Farmer: erntet reife Pflanzen in der Umgebung und pflanzt nach.
 *
 * Weizen, Karotten, Kartoffeln, Rote Bete und Netherwarzen werden geerntet und
 * mit Saatgut aus dem Inventar neu gesetzt. Melonen/Kuerbisse nur, wenn ein
 * Stiel daran haengt; Zuckerrohr ab dem zweiten Block. Seit 2.33 mit
 * Wegfindung (um Zaeune und Wasserrinnen herum), eigenem Essen und Einlagern
 * in Truhen, wenn das Inventar voll ist. Er springt nie auf Ackerboden.
 */
public class CropFarmerModule extends Module {

    public final NumberSetting range = new NumberSetting("Range", 12, 3, 32, 1);
    public final BooleanSetting walk = new BooleanSetting("Walk To Crops", true);
    public final BooleanSetting replant = new BooleanSetting("Replant", true);
    public final BooleanSetting collect = new BooleanSetting("Collect Drops", true);
    public final BooleanSetting wheat = new BooleanSetting("Wheat/Carrots/Potatoes/Beetroot", true);
    public final BooleanSetting netherWart = new BooleanSetting("Nether Wart", true);
    public final BooleanSetting melons = new BooleanSetting("Melons & Pumpkins", true);
    public final BooleanSetting sugarCane = new BooleanSetting("Sugar Cane", true);
    public final NumberSetting delay = new NumberSetting("Delay Ticks", 2, 0, 10, 1);
    public final BooleanSetting cocoa = new BooleanSetting("Cocoa", true);
    public final BooleanSetting berries = new BooleanSetting("Sweet Berries", true);
    public final BooleanSetting boneMeal = new BooleanSetting("Use Bone Meal", false);
    public final BooleanSetting store = new BooleanSetting("Store In Chests", true);
    public final BooleanSetting eat = new BooleanSetting("Eat When Hungry", true);
    public final BooleanSetting stayNearStart = new BooleanSetting("Stay Near Start", true);
    public final BooleanSetting defend = new BooleanSetting("Defend Yourself", true);
    public final NumberSetting stopHealth = new NumberSetting("Stop Below Health", 6, 0, 18, 1);
    public final NumberSetting playerPause = new NumberSetting("Pause If Player Within", 0, 0, 64, 4);

    public CropFarmerModule() {
        super("Crop Farmer", Category.BOTS);
        addSetting(range);
        addSetting(walk);
        addSetting(replant);
        addSetting(collect);
        addSetting(wheat);
        addSetting(netherWart);
        addSetting(melons);
        addSetting(sugarCane);
        addSetting(delay);
        addSetting(cocoa);
        addSetting(berries);
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
        return com.vortex.client.bot.CropFarmer.status();
    }
}
