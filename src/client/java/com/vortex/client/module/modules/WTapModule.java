package com.vortex.client.module.modules;

import com.vortex.client.core.setting.BooleanSetting;
import com.vortex.client.core.setting.ModeSetting;
import com.vortex.client.core.setting.NumberSetting;
import com.vortex.client.module.Module;

/**
 * W-Tap: jeder Schlag mit vollem Sprint-Rueckstoss.
 *
 * Der Server gibt Extra-Rueckstoss nur, wenn du beim Schlag sprintest -- und
 * setzt Sprinten nach so einem Schlag selbst auf "aus" (Player.attack). Ohne
 * W-Tap hat darum nur der erste Schlag den Bonus.
 *
 *   Packet: vor dem Schlag Sprint kurz aus und wieder an melden. Der Server
 *           sieht dich sofort wieder sprinten. Kein Tempoverlust.
 *   Legit:  nach dem Schlag die Vorwaerts-Taste ein paar Ticks loslassen,
 *           wie ein echter W-Tap. Sieht am natuerlichsten aus.
 *
 * Criticals hat Vorrang: ein Krit braucht NICHT-Sprinten.
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
