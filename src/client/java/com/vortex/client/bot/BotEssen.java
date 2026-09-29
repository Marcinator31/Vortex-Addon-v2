package com.vortex.client.bot;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Essen fuer die Lauf-Bots.
 *
 * Bis 2.32 aßen Crop und Tree Farmer nie: Laufen und Abbauen kosten Hunger,
 * nach einer Weile heilte der Spieler nicht mehr und nahm schliesslich
 * Hungerschaden. Auto Eat half nicht zuverlaessig -- es wechselt denselben
 * Hotbar-Platz, den der Bot gerade zum Pflanzen oder Faellen braucht.
 *
 * Jetzt isst der Bot selbst: ab "grenze" Hungerpunkten (oder bei wenig Leben)
 * das naehrreichste Essen, notfalls aus dem Rucksack in die Hotbar geholt,
 * bis er satt ist. Solange er isst, tut er nichts anderes. Auto Eat pausiert,
 * solange ein Bot laeuft (siehe CombatCheats.botLaeuft).
 */
final class BotEssen {

    private final String name;
    private boolean isst = false;
    private int platz = -1, vorher = -1;
    private long seit = 0, letzterBiss = -100;
    private boolean ohneGemeldet = false;

    BotEssen(String name) { this.name = name; }

    boolean isst() { return isst; }

    /**
     * Einen Tick essen, wenn noetig.
     *
     * @return true = isst gerade, der Bot soll diesen Tick sonst nichts tun
     */
    boolean tick(Minecraft mc, LocalPlayer p, long tick, int grenze) {
        if (p.isCreative()) { aus(mc, p); return false; }
        if (isst) {
            ItemStack st = p.getInventory().getItem(platz);
            boolean nochEssen = !st.isEmpty() && st.get(DataComponents.FOOD) != null;
            if (!p.getFoodData().needsFood() || p.getFoodData().getFoodLevel() >= 19 || !nochEssen || tick - seit > 60) {
                aus(mc, p);
                return false;
            }
            if (p.getInventory().getSelectedSlot() != platz) p.getInventory().setSelectedSlot(platz);
            if (p.isUsingItem()) {
                // Taste halten, solange gegessen wird -- sonst bricht das Spiel ab.
                seit = tick;
                mc.options.keyUse.setDown(true);
            } else {
                // NICHT die Taste halten, wenn gerade nicht gegessen wird: dann
                // "benutzt" das Spiel den Block im Fadenkreuz (Truhe, Tuer,
                // Dorfbewohner ...). Stattdessen direkt das Essen benutzen.
                mc.options.keyUse.setDown(false);
                if (tick - letzterBiss >= 4) {
                    letzterBiss = tick;
                    mc.gameMode.useItem(p, InteractionHand.MAIN_HAND);
                }
            }
            return true;
        }
        if (!braucht(p, grenze)) return false;
        int s = besteHotbar(p);
        if (s < 0 && BotMotor.holeInHotbar(mc, p, BotEssen::gutesEssen) >= 0) s = besteHotbar(p);
        if (s < 0) {
            if (!ohneGemeldet) {
                ohneGemeldet = true;
                p.sendSystemMessage(Component.literal("§d[" + name + "]§r Hungry, but no food in the inventory."));
            }
            return false;
        }
        ohneGemeldet = false;
        isst = true;
        platz = s;
        vorher = p.getInventory().getSelectedSlot();
        seit = tick;
        letzterBiss = tick;
        p.getInventory().setSelectedSlot(s);
        mc.gameMode.useItem(p, InteractionHand.MAIN_HAND);
        return true;
    }

    /** Essen abbrechen, Taste los, alten Platz zurueck. */
    void aus(Minecraft mc, LocalPlayer p) {
        if (!isst) return;
        isst = false;
        mc.options.keyUse.setDown(false);
        try { if (p != null && p.isUsingItem()) mc.gameMode.releaseUsingItem(p); } catch (Throwable ignored) { }
        if (p != null && vorher >= 0 && vorher <= 8) p.getInventory().setSelectedSlot(vorher);
        platz = vorher = -1;
    }

    private static boolean braucht(LocalPlayer p, int grenze) {
        int hunger = p.getFoodData().getFoodLevel();
        if (hunger <= grenze) return true;
        return p.getHealth() <= 10 && p.getFoodData().needsFood() && hunger < 18;
    }

    /** Bestes Essen in der Hotbar (nach Naehrwert), sonst -1. */
    private static int besteHotbar(LocalPlayer p) {
        int best = -1, bestWert = -1;
        for (int i = 0; i < 9; i++) {
            ItemStack st = p.getInventory().getItem(i);
            if (!gutesEssen(st)) continue;
            FoodProperties f = st.get(DataComponents.FOOD);
            int w = f.nutrition() * 2;
            if (w > bestWert) { bestWert = w; best = i; }
        }
        return best;
    }

    /** Essbar, nicht schaedlich, nicht wertvoll. */
    static boolean gutesEssen(ItemStack st) {
        if (st == null || st.isEmpty() || st.get(DataComponents.FOOD) == null) return false;
        return !(st.is(Items.ROTTEN_FLESH) || st.is(Items.SPIDER_EYE) || st.is(Items.POISONOUS_POTATO)
                || st.is(Items.PUFFERFISH) || st.is(Items.CHORUS_FRUIT) || st.is(Items.SUSPICIOUS_STEW)
                || st.is(Items.GOLDEN_APPLE) || st.is(Items.ENCHANTED_GOLDEN_APPLE));
    }
}
