package com.vortex.client.module.modules;

import com.vortex.client.core.setting.NumberSetting;
import com.vortex.client.module.Module;

/**
 * Reach: groessere Reichweite fuer Schlaege und Bloecke.
 *
 * Der Client zielt mit entityInteractionRange (normal 3) und
 * blockInteractionRange (normal 4.5). Der Server (Vanilla/Paper 26.x) laesst
 * dabei Toleranz zu: Angriffe bis 3 + 3 = 6 Bloecke, Bloecke bis
 * 4.5 + 1 = 5.5 Bloecke (gemessen ab den Augen bis zur Hitbox bzw. zum Block).
 * Mehr als das lehnt der Server ab -- deshalb sind die Regler dort gedeckelt.
 *
 * ACHTUNG: Anticheats pruefen Reichweite ueber 3 Bloecke sehr genau. Schon
 * 3.5 faellt auf guten Anticheats auf.
 */
public class ReachCheatModule extends Module {

    public final NumberSetting entityReach = new NumberSetting("Attack Reach", 4.5, 3.0, 6.0, 0.1);
    public final NumberSetting blockReach = new NumberSetting("Block Reach", 5.5, 4.5, 5.5, 0.1);

    public ReachCheatModule() {
        super("Reach", Category.CHEATS);
        addSetting(entityReach);
        addSetting(blockReach);
    }
}
