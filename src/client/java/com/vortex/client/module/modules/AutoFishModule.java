package com.vortex.client.module.modules;

import com.vortex.client.core.setting.BooleanSetting;
import com.vortex.client.core.setting.NumberSetting;
import com.vortex.client.module.Module;

/**
 * Auto Fish: zieht die Angel ein, sobald ein Fisch anbeisst, und wirft neu aus.
 *
 * Erkannt wird der Biss am selben Signal, mit dem der Server dem Client den
 * Biss meldet (die Pose-Daten des Schwimmers) -- kein Raten ueber Geraeusche.
 */
public class AutoFishModule extends Module {

    public final NumberSetting reelDelay = new NumberSetting("Reel Delay Ticks", 2, 0, 10, 1);
    public final NumberSetting recastDelay = new NumberSetting("Recast Delay Ticks", 12, 4, 60, 1);
    public final BooleanSetting autoCast = new BooleanSetting("Auto Cast", true);
    public final BooleanSetting pickRod = new BooleanSetting("Select Rod", true);
    public final BooleanSetting saveRod = new BooleanSetting("Stop Before Rod Breaks", true);
    public final BooleanSetting antiAfk = new BooleanSetting("Anti AFK Turn", false);

    public AutoFishModule() {
        super("Auto Fish", Category.CHEATS);
        addSetting(reelDelay);
        addSetting(recastDelay);
        addSetting(autoCast);
        addSetting(pickRod);
        addSetting(saveRod);
        addSetting(antiAfk);
    }
}
