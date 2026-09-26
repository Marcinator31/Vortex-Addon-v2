package com.vortex.client.module.modules;

import com.vortex.client.core.setting.BooleanSetting;
import com.vortex.client.core.setting.ModeSetting;
import com.vortex.client.core.setting.NumberSetting;
import com.vortex.client.module.Module;

/**
 * Steigt ganze Bloecke hoch wie Stufen, ohne zu springen.
 *
 * Setzt die Stufenhoehe des Spielers (normal 0,6 Bloecke). Beim Ausschalten
 * gilt wieder der alte Wert.
 */
public class StepModule extends Module {

    public final NumberSetting height = new NumberSetting("Height", 1.0, 0.6, 3.0, 0.1);

    public StepModule() {
        super("Step", Category.CHEATS);
        addSetting(height);
    }

    @Override
    protected void onDisable() {
        com.vortex.client.cheat.MoveCheats.stepAus();
    }
}
