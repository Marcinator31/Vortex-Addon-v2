package com.vortex.client.module.modules;

import com.vortex.client.core.setting.BooleanSetting;
import com.vortex.client.core.setting.NumberSetting;
import com.vortex.client.module.Module;

/**
 * Moves a totem into the off hand by itself.
 *
 * The delay used to be fixed at three ticks. That is a compromise nobody chose:
 * too slow when it counts, and faster than any hand could manage, which is
 * exactly the sort of thing an anti-cheat looks for. Now it is a number you
 * decide on.
 */
public class AutoTotemModule extends Module {

    /**
     * Ticks to wait before swapping in the next totem.
     *
     * A tick is 50 ms. Zero means the very next tick, which is faster than a
     * person can click and stands out accordingly.
     */
    public final NumberSetting delay = new NumberSetting("Delay (ticks)", 3, 0, 20, 1);

    /**
     * Extra random spread on the delay, in ticks.
     *
     * Identical gaps are the most obvious sign of automation -- no hand
     * produces the same interval twice. This does not make it undetectable,
     * and nothing here is built to.
     */
    public final NumberSetting jitter = new NumberSetting("Random Spread (ticks)", 0, 0, 10, 1);

    /**
     * Only act while holding something in the main hand worth protecting.
     *
     * Swapping a totem in while you are placing blocks is rarely what you
     * meant, and it costs you the off hand slot you were using.
     */
    public final BooleanSetting onlyWithWeapon = new BooleanSetting("Only With a Weapon", false);

    /** Health below which it acts. 20 means always. */
    public final NumberSetting healthBelow = new NumberSetting("Only Below Health", 20, 1, 20, 1);

    /** Say something in chat when the last totem is gone. */
    public final BooleanSetting warnEmpty = new BooleanSetting("Warn When Out", true);

    public AutoTotemModule() {
        super("Auto Totem", Category.CHEATS);
        addSetting(delay);
        addSetting(jitter);
        addSetting(onlyWithWeapon);
        addSetting(healthBelow);
        addSetting(warnEmpty);
    }
}
