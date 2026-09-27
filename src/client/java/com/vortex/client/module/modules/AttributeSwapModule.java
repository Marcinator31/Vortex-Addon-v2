package com.vortex.client.module.modules;

import com.vortex.client.core.setting.BooleanSetting;
import com.vortex.client.core.setting.ModeSetting;
import com.vortex.client.module.Module;

/**
 * Attribute Swap: Aufladung und Grundschaden kommen vom Item, das du HAELTST --
 * die Wirkung (Smash, Verzauberungen, Schild-Brechen) von der Waffe, auf die
 * nur fuer das Angriffspaket gewechselt wird.
 *
 * WARUM DAS GEHT (geprueft an Player.attack / LivingEntity, 26.x): Beim
 * Slot-Wechsel uebernimmt der Server Angriffstempo und Grundschaden der neuen
 * Waffe erst im naechsten Server-Tick (detectEquipmentUpdates). Smash-Bonus,
 * Verzauberungen und "Schild ausschalten" liest er dagegen sofort vom Item in
 * der Hand. Das Modul wechselt fuer das Angriffspaket auf die Waffe und im
 * selben Moment zurueck -- der Server sieht nie einen Tick mit ihr in der Hand.
 *
 * SWORD ATTRIBUTE SWAP (Schwert halten):
 *   - Swap To "Mace": Schwert-Grundschaden (Netherit 8) + Schwert-Tempo
 *     (voll nach 12.5 Ticks) + Smash-Bonus, Density und Breach vom
 *     Streitkolben. Mit Criticals: Krit x1.5 auf ALLES. Staerkster Einzelschlag.
 *   - Swap To "Axe": Schwert-Schaden und -Tempo, aber der Schlag schaltet
 *     einen blockenden Schild 5 Sekunden aus wie eine Axt.
 *   - "Axe vs Shield": blockt das Ziel, wird automatisch die Axt genommen.
 *   - "Return To Sword": nach dem Schlag landest du wieder auf dem Schwert,
 *     auch wenn du vorher etwas anderes hattest.
 *
 * LEERE HAND halten + Swap To "Mace": voll aufgeladen alle 5 Ticks, aber nur
 * Grundschaden 1 -- mehr Krit-Smashes, jeder etwas schwaecher.
 *
 * GRENZEN: Das Ziel hat nach einem Treffer 10 Ticks Unverwundbarkeit -- voller
 * Schaden hoechstens 2x pro Sekunde pro Gegner. Mehr als 4 volle Schlaege pro
 * Sekunde gibt es nicht. Anticheats (Grim u. a.) pruefen genau diese
 * Slot-Wechsel -- hohes Bann-Risiko.
 */
public class AttributeSwapModule extends Module {

    public final ModeSetting mode = new ModeSetting("Swap To", 0, "Mace", "Axe", "Sword");
    public final BooleanSetting axeVsShield = new BooleanSetting("Axe vs Shield", true);
    public final BooleanSetting returnToSword = new BooleanSetting("Return To Sword", true);

    public AttributeSwapModule() {
        super("Attribute Swap", Category.CHEATS);
        addSetting(mode);
        addSetting(axeVsShield);
        addSetting(returnToSword);
    }
}
