package com.vortex.client.module.modules;

import com.vortex.client.core.setting.BooleanSetting;
import com.vortex.client.module.Module;

/**
 * Middle Click Pearl: Mittelklick (die "Block auswaehlen"-Taste) wirft eine
 * Enderperle, ohne dass du den Platz wechseln musst. Danach ist wieder das
 * vorige Item in der Hand.
 */
public class MiddleClickPearlModule extends Module {

    public final BooleanSetting fromInventory = new BooleanSetting("Use Inventory Pearls", true);
    public final BooleanSetting notOnPlayers = new BooleanSetting("Not On Players", true);

    public MiddleClickPearlModule() {
        super("Middle Click Pearl", Category.CHEATS);
        addSetting(fromInventory);
        addSetting(notOnPlayers);
    }
}
