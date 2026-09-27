package com.vortex.client.module.modules;

import com.vortex.client.core.setting.BooleanSetting;
import com.vortex.client.core.setting.NumberSetting;
import com.vortex.client.module.Module;

/**
 * Parkour: springt im letzten moeglichen Moment an Kanten ab.
 *
 * Der Sprung passiert in der Bewegung selbst, genau in dem Tick, in dem du
 * sonst von der Kante fallen wuerdest -- weiter geht ein Sprung nicht.
 *
 *  - Check Landing: nur springen, wenn vorne ein Landeplatz ist (kein
 *    Abgrund, keine Lava, nicht zu weit).
 *  - Stop At Unsafe Gaps: gibt es keinen, bleibst du an der Kante stehen.
 *  - Jump Up: springt einzelne Bloecke hoch, ohne dass du springen musst.
 *  - Auto Sprint: sprintet waehrenddessen.
 */
public class ParkourModule extends Module {

    public final BooleanSetting edgeJump = new BooleanSetting("Edge Jump", true);
    public final BooleanSetting checkLanding = new BooleanSetting("Check Landing", true);
    public final BooleanSetting stopUnsafe = new BooleanSetting("Stop At Unsafe Gaps", true);
    public final NumberSetting maxGap = new NumberSetting("Max Gap", 4, 1, 4, 1);
    public final NumberSetting maxDrop = new NumberSetting("Max Drop", 3, 0, 10, 1);
    public final BooleanSetting jumpUp = new BooleanSetting("Jump Up Blocks", true);
    public final BooleanSetting autoSprint = new BooleanSetting("Auto Sprint", true);

    public ParkourModule() {
        super("Parkour", Category.CHEATS);
        addSetting(edgeJump);
        addSetting(checkLanding);
        addSetting(stopUnsafe);
        addSetting(maxGap);
        addSetting(maxDrop);
        addSetting(jumpUp);
        addSetting(autoSprint);
    }
}
