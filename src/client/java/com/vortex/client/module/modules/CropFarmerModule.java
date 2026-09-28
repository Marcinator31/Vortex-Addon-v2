package com.vortex.client.module.modules;

import com.vortex.client.core.setting.BooleanSetting;
import com.vortex.client.core.setting.NumberSetting;
import com.vortex.client.module.Module;

/**
 * Crop Farmer: erntet reife Pflanzen in der Umgebung und pflanzt nach.
 *
 * Weizen, Karotten, Kartoffeln, Rote Bete und Netherwarzen werden geerntet und
 * mit Saatgut aus dem Inventar neu gesetzt. Melonen/Kuerbisse nur, wenn ein
 * Stiel daran haengt; Zuckerrohr ab dem zweiten Block. Der Bot laeuft gerade
 * Linien (kein Pfadfinder) -- am besten auf einer offenen, ebenen Farm.
 * Er springt nie auf Ackerboden (der wuerde sonst zertreten).
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
    }
}
