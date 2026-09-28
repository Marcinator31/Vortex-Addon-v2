package com.vortex.client.module.modules;

import com.vortex.client.core.setting.BooleanSetting;
import com.vortex.client.core.setting.ModeSetting;
import com.vortex.client.core.setting.NumberSetting;
import com.vortex.client.module.Module;

/**
 * W-Tap: jeder Schlag mit vollem Sprint-Rueckstoss.
 *
 * Der Server gibt Extra-Rueckstoss nur, wenn du beim Schlag sprintest und voll
 * aufgeladen hast -- und setzt Sprinten nach so einem Schlag auf "aus"
 * (Player.causeExtraKnockback). Ohne W-Tap hat darum nur der erste Schlag den Bonus.
 *
 *   Packet: direkt vor jedem Schlag wieder "sprintet" melden. Jeder voll
 *           aufgeladene Schlag hat den Bonus, kein Tempoverlust.
 *   Legit:  nach dem Schlag ein paar Ticks nicht sprinten, dann von selbst
 *           wieder -- wie ein echter W-Tap. Sieht am natuerlichsten aus.
 *
 * Nur, wenn Sprinten gerade moeglich ist (vorwaerts, nicht schleichen, genug
 * Hunger, nicht blind). Criticals hat Vorrang: ein Krit braucht NICHT-Sprinten.
 */
public class WTapModule extends Module {

    public final ModeSetting mode = new ModeSetting("Mode", 0, "Packet", "Legit");
    public final NumberSetting releaseTicks = new NumberSetting("Release Ticks", 2, 1, 6, 1);
    public final BooleanSetting onlyPlayers = new BooleanSetting("Only Players", false);

    public WTapModule() {
        super("W-Tap", Category.CHEATS);
        addSetting(mode);
        addSetting(releaseTicks);
        addSetting(onlyPlayers);
    }
}
