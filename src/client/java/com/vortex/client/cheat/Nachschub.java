package com.vortex.client.cheat;

import com.vortex.client.module.Module;
import com.vortex.client.module.ModuleManager;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Restock (seit Addon 2.42): holt Anker, Glowstone, Kristalle und Obsidian
 * aus dem Inventar in die Hotbar, sobald dort zu wenig ist.
 *
 * Jedes PvP-Modul hat dafuer die Einstellung "Restock". Ablauf:
 *   - liegt in der Hotbar schon ein Stapel, wird mit Shift-Klick aus dem
 *     Inventar aufgefuellt (Minecraft legt ihn zum vorhandenen Stapel)
 *   - liegt dort keiner, kommt einer per Tausch-Klick auf einen freien
 *     Hotbar-Platz -- gibt es keinen, auf einen Platz mit etwas
 *     Unwichtigem (nie Totem, Waffe, Kristalle, Anker, Glowstone, Essen ...)
 * Hoechstens ein Klick alle zwei Ticks, nie mit etwas am Mauszeiger und nur
 * ohne offenes Fenster (oder im eigenen Inventar).
 */
public final class Nachschub {

    private Nachschub() {}

    /** Unter so vielen Stueck in der Hotbar wird aufgefuellt. */
    private static final int MINDESTENS = 16;

    private static long tick = 0, zuletzt = -100;

    public static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            tick++;
            try {
                lauf(mc);
            } catch (Throwable e) {
                com.vortex.client.core.Errors.report("Restock", e);
            }
        });
    }

    private static boolean an(Class<? extends Module> c, java.util.function.Function<Module, Boolean> restock) {
        Module m = ModuleManager.INSTANCE.get(c);
        return m != null && m.isEnabled() && restock.apply(m);
    }

    private static void lauf(Minecraft mc) {
        LocalPlayer p = mc.player;
        if (p == null || mc.gameMode == null || p.isCreative()) return;
        if (tick - zuletzt < 2) return;
        var screen = mc.gui.screen();
        if (screen != null && !(screen instanceof net.minecraft.client.gui.screens.inventory.InventoryScreen)) return;
        if (!p.inventoryMenu.getCarried().isEmpty()) return;

        List<Item> brauche = new ArrayList<>();
        if (an(com.vortex.client.module.modules.AutoAnchorModule.class, m -> ((com.vortex.client.module.modules.AutoAnchorModule) m).restock.get())
                || an(com.vortex.client.module.modules.FastAnchorModule.class, m -> ((com.vortex.client.module.modules.FastAnchorModule) m).restock.get())) {
            brauche.add(Items.RESPAWN_ANCHOR);
            brauche.add(Items.GLOWSTONE);
        }
        if (an(com.vortex.client.module.modules.AntiAnchorModule.class, m -> ((com.vortex.client.module.modules.AntiAnchorModule) m).restock.get())) {
            brauche.add(Items.GLOWSTONE);
            brauche.add(Items.OBSIDIAN);
        }
        if (an(com.vortex.client.module.modules.SurroundModule.class, m -> ((com.vortex.client.module.modules.SurroundModule) m).restock.get())) {
            brauche.add(Items.OBSIDIAN);
        }
        boolean auraKristalle = an(com.vortex.client.module.modules.CrystalAuraModule.class,
                m -> ((com.vortex.client.module.modules.CrystalAuraModule) m).restock.get())
                && !p.getOffhandItem().is(Items.END_CRYSTAL);
        if (auraKristalle || an(com.vortex.client.module.modules.CrystalMacroModule.class,
                m -> ((com.vortex.client.module.modules.CrystalMacroModule) m).restock.get())) {
            brauche.add(Items.END_CRYSTAL);
        }
        for (Item item : brauche) {
            if (auffuellen(mc, p, item)) {
                zuletzt = tick;
                return;
            }
        }
    }

    /** @return true, wenn geklickt wurde */
    static boolean auffuellen(Minecraft mc, LocalPlayer p, Item item) {
        int inHotbar = 0, stapel = -1;
        for (int i = 0; i < 9; i++) {
            ItemStack st = p.getInventory().getItem(i);
            if (st.is(item)) { inHotbar += st.getCount(); stapel = i; }
        }
        if (inHotbar >= MINDESTENS) return false;
        // groesster Stapel im Inventar
        int quelle = -1, menge = 0;
        for (int i = 9; i < Math.min(36, p.getInventory().getContainerSize()); i++) {
            ItemStack st = p.getInventory().getItem(i);
            if (st.is(item) && st.getCount() > menge) { quelle = i; menge = st.getCount(); }
        }
        if (quelle < 0) return false;
        int id = p.inventoryMenu.containerId;
        if (stapel >= 0) {
            // Shift-Klick: Minecraft legt es zum Stapel in der Hotbar
            mc.gameMode.handleContainerInput(id, Inv.fensterPlatz(quelle), 0, ContainerInput.QUICK_MOVE, p);
            return true;
        }
        int ziel = zielPlatz(p);
        if (ziel < 0) return false;
        mc.gameMode.handleContainerInput(id, Inv.fensterPlatz(quelle), ziel, ContainerInput.SWAP, p);
        return true;
    }

    /** Freier Hotbar-Platz, sonst einer mit etwas Unwichtigem (von rechts), sonst -1. */
    private static int zielPlatz(LocalPlayer p) {
        int gewaehlt = p.getInventory().getSelectedSlot();
        for (int i = 8; i >= 0; i--) if (i != gewaehlt && p.getInventory().getItem(i).isEmpty()) return i;
        for (int i = 8; i >= 0; i--) {
            if (i == gewaehlt) continue;
            if (!wichtig(p.getInventory().getItem(i))) return i;
        }
        return -1;
    }

    private static boolean wichtig(ItemStack st) {
        if (st.isEmpty()) return false;
        if (st.is(Items.TOTEM_OF_UNDYING) || st.is(Items.END_CRYSTAL) || st.is(Items.RESPAWN_ANCHOR)
                || st.is(Items.GLOWSTONE) || st.is(Items.OBSIDIAN) || st.is(Items.ENDER_PEARL)
                || st.is(Items.GOLDEN_APPLE) || st.is(Items.ENCHANTED_GOLDEN_APPLE) || st.is(Items.EXPERIENCE_BOTTLE)
                || st.is(Items.BOW) || st.is(Items.CROSSBOW) || st.is(Items.TRIDENT) || st.is(Items.MACE)
                || st.is(Items.SHIELD) || st.is(Items.WATER_BUCKET) || st.is(Items.ELYTRA)) return true;
        if (st.is(net.minecraft.tags.ItemTags.SWORDS) || st.is(net.minecraft.tags.ItemTags.AXES)
                || st.is(net.minecraft.tags.ItemTags.PICKAXES)) return true;
        if (st.has(net.minecraft.core.component.DataComponents.FOOD)) return true;
        if (st.has(net.minecraft.core.component.DataComponents.POTION_CONTENTS)) return true;
        return false;
    }
}
