package com.vortex.client.module.modules;

import com.vortex.client.core.setting.BooleanSetting;
import com.vortex.client.core.setting.ModeSetting;
import com.vortex.client.core.setting.NumberSetting;
import com.vortex.client.module.Module;

/**
 * Auto Pot: wirft Wurftraenke (Splash) unter dich.
 *
 * Geworfen wird mit Blick nach unten -- der Server bekommt die Richtung mit
 * dem Wurf-Paket, deine Kamera bleibt, wo sie ist. Der Trank landet auf dir.
 *
 * Heilung nach Leben, Buffs (Staerke, Tempo, Feuerschutz, Regeneration),
 * sobald sie fehlen oder bald auslaufen. Feuerschutz optional nur, wenn du
 * brennst oder in Lava bist.
 */
public class AutoPotModule extends Module {

    // --- Heilung -----------------------------------------------------------
    public final BooleanSetting heal = new BooleanSetting("Healing", true);
    public final NumberSetting healBelow = new NumberSetting("Heal Below HP", 10, 2, 19, 1);
    public final NumberSetting healDelay = new NumberSetting("Heal Delay Ticks", 6, 1, 40, 1);
    public final NumberSetting healCount = new NumberSetting("Pots Per Heal", 1, 1, 3, 1);
    public final BooleanSetting regen = new BooleanSetting("Regeneration", false);
    public final NumberSetting regenBelow = new NumberSetting("Regen Below HP", 14, 2, 19, 1);

    // --- Buffs -------------------------------------------------------------
    public final BooleanSetting strength = new BooleanSetting("Strength", true);
    public final BooleanSetting speed = new BooleanSetting("Speed", true);
    public final BooleanSetting fireRes = new BooleanSetting("Fire Resistance", true);
    public final ModeSetting fireResWhen = new ModeSetting("Fire Res When", 0, "Burning", "Always");
    public final NumberSetting refreshSeconds = new NumberSetting("Refresh Below Seconds", 5, 0, 60, 1);

    // --- Wann ----------------------------------------------------------------
    public final BooleanSetting onlyInCombat = new BooleanSetting("Buffs Only In Combat", true);
    public final NumberSetting combatRange = new NumberSetting("Combat Range", 12, 4, 32, 1);
    public final BooleanSetting onlyOnGround = new BooleanSetting("Only On Ground", false);
    public final BooleanSetting pauseEating = new BooleanSetting("Pause While Eating", true);
    public final NumberSetting minDelay = new NumberSetting("Delay Between Pots", 3, 1, 20, 1);

    // --- Inventar --------------------------------------------------------------
    public final BooleanSetting fromInventory = new BooleanSetting("Pull From Inventory", true);
    public final NumberSetting potSlot = new NumberSetting("Hotbar Slot", 8, 1, 9, 1);

    public AutoPotModule() {
        super("Auto Pot", Category.CHEATS);
        addSetting(heal);
        addSetting(healBelow);
        addSetting(healDelay);
        addSetting(healCount);
        addSetting(regen);
        addSetting(regenBelow);
        addSetting(strength);
        addSetting(speed);
        addSetting(fireRes);
        addSetting(fireResWhen);
        addSetting(refreshSeconds);
        addSetting(onlyInCombat);
        addSetting(combatRange);
        addSetting(onlyOnGround);
        addSetting(pauseEating);
        addSetting(minDelay);
        addSetting(fromInventory);
        addSetting(potSlot);
    }
}
