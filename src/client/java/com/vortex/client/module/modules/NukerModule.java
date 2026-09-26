package com.vortex.client.module.modules;

import com.vortex.client.core.setting.BooleanSetting;
import com.vortex.client.core.setting.ModeSetting;
import com.vortex.client.core.setting.NumberSetting;
import com.vortex.client.module.Module;

/**
 * Baut alles in Reichweite ab, naechste Bloecke zuerst.
 *
 * "All": alles. "Flatten": nur auf Fusshoehe und darueber -- der Boden
 * bleibt. "Instant": nur Bloecke, die sofort brechen (Gras, Blumen, mit
 * Effizienz auch Stein), dafuer mehrere pro Tick. Unzerstoerbares und
 * Fluessigkeiten werden ausgelassen.
 *
 * Extremes Bann-Risiko.
 */
public class NukerModule extends Module {

    public final ModeSetting mode = new ModeSetting("Mode", 1, "All", "Flatten", "Instant");
    public final NumberSetting range = new NumberSetting("Range", 4.0, 1.0, 6.0, 0.5);
    public final NumberSetting perTick = new NumberSetting("Instant Per Tick", 2, 1, 8, 1);
    public final BooleanSetting swing = new BooleanSetting("Swing", true);

    public NukerModule() {
        super("Nuker", Category.CHEATS);
        addSetting(mode);
        addSetting(range);
        addSetting(perTick);
        addSetting(swing);
    }
}
