package com.vortex.client.module.modules;

import com.vortex.client.core.setting.BooleanSetting;
import com.vortex.client.module.Module;

/**
 * Criticals: jeder voll aufgeladene Schlag wird ein kritischer Treffer (x1.5
 * Schaden) -- ohne zu springen.
 *
 * WARUM NICHT "SCHNELLER VOLL AUFLADEN": Die Aufladung zaehlt der Server
 * selbst (attackStrengthTicker) -- kein Client kann sie beschleunigen. Den
 * kritischen Treffer entscheidet der Server dagegen aus Fallhoehe > 0 und
 * "nicht am Boden", und beides meldet der Client. Direkt vor dem Schlag meldet
 * dieses Modul einen Mini-Hopser (1/16 Block hoch und wieder runter).
 *
 *   Stop Sprint  Beim Sprinten gibt es in Vanilla keinen Crit. An: vor dem
 *                Schlag kurz "nicht mehr sprinten" melden, danach wieder
 *                sprinten -- Crit statt Sprint-Rueckstoss.
 *
 * Beim Streitkolben mit Mace Kill uebernimmt Mace Kill (der ist staerker).
 * ACHTUNG: Anticheats erkennen Crits ohne echten Sprung.
 */
public class CriticalsModule extends Module {

    public final BooleanSetting stopSprint = new BooleanSetting("Stop Sprint", true);

    public CriticalsModule() {
        super("Criticals", Category.CHEATS);
        addSetting(stopSprint);
    }
}
