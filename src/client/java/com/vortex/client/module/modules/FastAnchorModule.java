package com.vortex.client.module.modules;

import com.vortex.client.core.setting.BooleanSetting;
import com.vortex.client.core.setting.ModeSetting;
import com.vortex.client.core.setting.NumberSetting;
import com.vortex.client.module.Module;

/**
 * Fast Anchor: Seelenanker setzen, laden und zuenden mit EINEM Rechtsklick.
 *
 * Du zielst wie gewohnt: auf einen Block (mit Anker in der Hand) oder auf
 * einen liegenden Anker (mit Anker oder Glowstone in der Hand). Der Rest
 * passiert automatisch:
 *
 *   1. Anker setzen (falls noch keiner da ist)
 *   2. Glowstone-Schild: ein Glowstone-Block zwischen dich und den Anker
 *   3. Laden (1x Glowstone reicht -- die Explosion ist immer gleich stark)
 *   4. Zuenden (mit leerem Platz, Schwert oder Werkzeug)
 *
 * Warum der Schild wirkt: der Server rechnet den Schaden an Spielern, BEVOR
 * die Explosion die Bloecke zerstoert. Der Glowstone steht in dem Moment
 * noch und deckt dich ab. Wie viel er bringt, rechnet das Modul vorher aus.
 *
 * Instant = alles in einem Tick (am schnellsten). Fast = ein Schritt pro Tick
 * (fuer Server, die Instant ablehnen).
 *
 * Nur in Oberwelt und End -- im Nether explodiert ein Anker nicht.
 */
public class FastAnchorModule extends Module {

    public final ModeSetting speed = new ModeSetting("Speed", 0, "Instant", "Fast");
    public final BooleanSetting onlyHolding = new BooleanSetting("Only Holding Anchor/Glowstone", true);
    public final BooleanSetting repeat = new BooleanSetting("Repeat While Held", true);
    public final NumberSetting repeatDelay = new NumberSetting("Repeat Delay", 4, 1, 20, 1);
    public final BooleanSetting shield = new BooleanSetting("Glowstone Shield", true);
    public final BooleanSetting antiSuicide = new BooleanSetting("Anti Suicide", true);
    public final NumberSetting maxSelfDamage = new NumberSetting("Max Self Damage", 10, 0, 36, 0.5);
    public final BooleanSetting detonate = new BooleanSetting("Detonate", true);
    public final BooleanSetting switchBack = new BooleanSetting("Switch Back", true);
    public final BooleanSetting swing = new BooleanSetting("Swing", true);
    public final BooleanSetting showDamage = new BooleanSetting("Damage In Chat", false);

    public FastAnchorModule() {
        super("Fast Anchor", Category.CHEATS);
        addSetting(speed);
        addSetting(onlyHolding);
        addSetting(repeat);
        addSetting(repeatDelay);
        addSetting(shield);
        addSetting(antiSuicide);
        addSetting(maxSelfDamage);
        addSetting(detonate);
        addSetting(switchBack);
        addSetting(swing);
        addSetting(showDamage);
    }
}
