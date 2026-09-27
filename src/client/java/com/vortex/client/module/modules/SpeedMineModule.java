package com.vortex.client.module.modules;

import com.vortex.client.core.setting.BooleanSetting;
import com.vortex.client.core.setting.NumberSetting;
import com.vortex.client.module.Module;

/**
 * Speed Mine: Bloecke schneller abbauen -- nur so schnell, wie der Server es
 * annimmt, damit keine Geisterbloecke entstehen.
 *
 * GEPRUEFT (ServerPlayerGameMode, 26.x): der Server nimmt einen Block ab 70 %
 * seines eigenen Abbaufortschritts als abgebaut an. "Break At 0.7" meldet
 * genau dann fertig = 30 % weniger Zeit pro Block.
 *
 * Dazu "No Break Delay": Minecraft wartet nach jedem Block 5 Ticks, bevor der
 * naechste beginnt. Der Server prueft diese Pause nicht. Beim Tunnelgraben
 * ist das oft mehr Gewinn als die 30 %.
 *
 * Schneller als 0.7 geht NICHT: dann bricht der Block nur bei dir, der Server
 * setzt ihn zurueck.
 */
public class SpeedMineModule extends Module {

    public final NumberSetting breakAt = new NumberSetting("Break At", 0.7, 0.7, 1.0, 0.05);
    public final BooleanSetting noDelay = new BooleanSetting("No Break Delay", true);

    public SpeedMineModule() {
        super("Speed Mine", Category.CHEATS);
        addSetting(breakAt);
        addSetting(noDelay);
    }
}
