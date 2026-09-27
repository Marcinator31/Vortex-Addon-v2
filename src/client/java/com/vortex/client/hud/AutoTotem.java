package com.vortex.client.hud;

import com.vortex.client.module.Module;
import com.vortex.client.module.ModuleManager;
import com.vortex.client.module.modules.AutoTotemModule;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.client.Minecraft;
import net.minecraft.world.inventory.ContainerInput;

/**
 * Logik fuer Auto Totem: sobald die Off-Hand leer ist und ein Totem im Inventar
 * liegt, wird es per Slot-Klick in die Off-Hand gelegt.
 *
 * Slot-Mechanik im Spieler-Inventar (PlayerScreenHandler):
 *   - Off-Hand-Slot = 45
 *   - Inventar-Klicks laufen ueber interactionManager.clickSlot mit syncId 0.
 *
 * Wir nutzen PICKUP (Links-Klick) in zwei Schritten: Totem-Slot anklicken
 * (Totem auf den Cursor), Off-Hand-Slot anklicken (Totem ablegen), und -- falls
 * danach noch etwas am Cursor haengt -- zurueck auf den Ursprungsslot legen. Das
 * funktioniert auch bei geschlossenem Inventar.
 *
 * Ein kleiner Cooldown verhindert, dass bei einem kurzzeitig leeren Slot mehrere
 * Aktionen pro Tick abgefeuert werden.
 */
public final class AutoTotem {

    private static final int OFFHAND_SLOT = 45;
    private static long lastActionTick = 0;

    /** How long to wait this time -- rerolled after every swap. */
    private static int nextDelay = 3;

    /** Set once the warning has been given, cleared when a totem turns up. */
    private static boolean warnedEmpty = false;

    private static final java.util.Random RANDOM = new java.util.Random();
    private static long tickCounter = 0;

    private AutoTotem() {}

    public static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            long pvpT0 = System.nanoTime();
            try {
            tickCounter++;
            AutoTotemModule mod = (AutoTotemModule) find(AutoTotemModule.class);
            if (mod == null || !mod.isEnabled()) return;
            if (client.player == null || client.gameMode == null) return;

            LocalPlayer player = client.player;

            // --- Modus Hover / Inventory Open (4.6.1) ------------------------
            int modus = mod.mode.getIndex();
            if (modus == 1) { hover(client, player, mod); return; }
            if (modus == 2) { inventarOffen(client, player, mod); return; }

            // --- Modus Normal ------------------------------------------------
            // Nur handeln, wenn die Off-Hand wirklich leer ist.
            if (!player.getOffhandItem().isEmpty()) return;

            // Only below a certain health, if that is what was asked for.
            // Twenty means the check is off, since that is full health.
            int below = mod.healthBelow.getInt();
            if (below < 20 && player.getHealth() > below) return;

            // Only while holding a weapon, if that is what was asked for.
            //
            // Swapping the off hand while you are placing blocks is rarely what
            // anyone meant, and it costs the slot you were using.
            if (mod.onlyWithWeapon.get() && !holdingWeapon(player)) return;

            // Kleiner Cooldown (ein paar Ticks), damit nicht mehrfach geklickt
            // wird, waehrend der Wechsel noch synchronisiert.
            // Delay from the setting, plus the spread that was rolled last time.
            if (tickCounter - lastActionTick < nextDelay) return;

            // Ein Totem im Inventar suchen (Hauptinventar + Hotbar).
            int totemSlot = findTotemInventorySlot(player);
            if (totemSlot < 0) {
                // Out of totems -- worth knowing before you find out the hard
                // way. Said once, not every tick.
                if (mod.warnEmpty.get() && !warnedEmpty) {
                    warnedEmpty = true;
                    player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                            "\u00a7c[Auto Totem] No totems left."));
                }
            } else {
                warnedEmpty = false;
            }
            if (totemSlot < 0) return; // kein Totem vorhanden -> nichts tun

            try {
                int syncId = player.inventoryMenu.containerId;
                // Inventar-Index -> Screen-Slot-Nummer umrechnen.
                int screenSlot = inventoryIndexToScreenSlot(totemSlot);
                if (screenSlot < 0) return;

                // 1. Totem aufnehmen (auf den Cursor).
                client.gameMode.handleContainerInput(
                        syncId, screenSlot, 0, ContainerInput.PICKUP, player);
                // 2. In die Off-Hand legen.
                client.gameMode.handleContainerInput(
                        syncId, OFFHAND_SLOT, 0, ContainerInput.PICKUP, player);
                // 3. Falls noch etwas am Cursor ist (Off-Hand war doch belegt),
                //    zurueck auf den Ursprungsslot legen.
                if (!player.inventoryMenu.getCarried().isEmpty()) {
                    client.gameMode.handleContainerInput(
                            syncId, screenSlot, 0, ContainerInput.PICKUP, player);
                }

                lastActionTick = tickCounter;
                // Roll the next wait now, so each swap has its own.
                int base = mod.delay.getInt();
                int spread = mod.jitter.getInt();
                nextDelay = base + ((spread > 0) ? RANDOM.nextInt(spread + 1) : 0);
            } catch (Throwable pvpErr) {
                com.vortex.client.core.Errors.report("AutoTotem", pvpErr);
            }
                    } finally {
                com.vortex.client.core.Profiler.record("AutoTotem",
                        System.nanoTime() - pvpT0);
            }
        });
    }

    /**
     * Sucht einen Totem-Stack im Spieler-Inventar und gibt den Inventar-Index
     * (0..35) zurueck, oder -1 wenn keiner da ist.
     */
    /**
     * Is a weapon in the main hand?
     *
     * Decided by the item's id rather than its class. SwordItem no longer
     * exists in this version -- the classes were merged -- and the tags that
     * replaced it are not reliably reachable from here. Matching on the name
     * is crude, but it is stable across versions and cannot fail to compile,
     * which the alternatives just did.
     */
    private static boolean holdingWeapon(LocalPlayer player) {
        try {
            ItemStack held = player.getMainHandItem();
            if (held == null || held.isEmpty()) return false;
            var id = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(held.getItem());
            if (id == null) return false;
            String path = id.getPath();
            return path.endsWith("_sword") || path.endsWith("_axe")
                    || path.equals("trident") || path.equals("mace");
        } catch (Throwable pvpErr) {
            com.vortex.client.core.Errors.report("AutoTotem.weapon", pvpErr);
            return true;   // in doubt, do not block the swap
        }
    }

    private static int findTotemInventorySlot(LocalPlayer player) {
        // getInventory().size() deckt Hauptinventar + Hotbar + Ruestung + Off-Hand
        // ab; wir interessieren uns fuer die normalen Slots 0..35.
        int size = Math.min(player.getInventory().getContainerSize(), 36);
        for (int i = 0; i < size; i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (!stack.isEmpty() && stack.is(Items.TOTEM_OF_UNDYING)) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Rechnet einen PlayerInventory-Index (0..35) in die Slot-Nummer des
     * PlayerScreenHandler um.
     *
     * PlayerScreenHandler-Layout:
     *   Slot 9..35  = Hauptinventar (Inventar-Index 9..35)
     *   Slot 36..44 = Hotbar        (Inventar-Index 0..8)
     */
    private static int inventoryIndexToScreenSlot(int invIndex) {
        if (invIndex >= 0 && invIndex <= 8) {
            return 36 + invIndex;      // Hotbar
        } else if (invIndex >= 9 && invIndex <= 35) {
            return invIndex;           // Hauptinventar (gleiche Nummer)
        }
        return -1;
    }

    // ======================================================================
    // Hover und Inventory Open
    // ======================================================================

    /** Button 40 bei SWAP = mit der Off-Hand tauschen (wie die F-Taste). */
    private static final int OFFHAND_TASTE = 40;

    private static net.minecraft.world.inventory.Slot hoverPlatz = null;
    private static long hoverSeit = 0;

    /**
     * HOVER: Inventar offen, Maus ueber einem Totem -> ab in die Off-Hand.
     *
     * Genau das, was ein Spieler mit "Maus drauf + F" tut: EIN Tausch-Klick
     * auf den Platz unter dem Zeiger. Die Verzoegerung zaehlt ab dem Moment,
     * in dem der Zeiger auf dem Totem landet -- wer nur darueberwischt, loest
     * bei ein paar Ticks Verzoegerung nichts aus.
     */
    private static void hover(net.minecraft.client.Minecraft client, LocalPlayer player, AutoTotemModule mod) {
        var screen = client.gui.screen();
        if (!(screen instanceof net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<?>)
                || istKreativ(screen)) {
            hoverPlatz = null;
            return;
        }
        net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<?> acs =
                (net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<?>) screen;
        if (player.getOffhandItem().is(Items.TOTEM_OF_UNDYING)) { hoverPlatz = null; return; }
        if (!acs.getMenu().getCarried().isEmpty()) return;
        net.minecraft.world.inventory.Slot platz =
                ((com.vortex.client.mixin.client.HoveredSlotAccessor) acs).vortex$hoveredSlot();
        if (platz == null || !platz.hasItem() || !platz.getItem().is(Items.TOTEM_OF_UNDYING)
                || platz.container != player.getInventory()) {
            hoverPlatz = null;
            return;
        }
        if (platz != hoverPlatz) {
            hoverPlatz = platz;
            hoverSeit = tickCounter;
        }
        if (tickCounter - hoverSeit < nextDelay || tickCounter - lastActionTick < nextDelay) return;
        try {
            client.gameMode.handleContainerInput(acs.getMenu().containerId, platz.index,
                    OFFHAND_TASTE, ContainerInput.SWAP, player);
            fertig(mod);
        } catch (Throwable pvpErr) {
            com.vortex.client.core.Errors.report("AutoTotem.hover", pvpErr);
        }
    }

    /**
     * INVENTORY OPEN: sobald das eigene Inventar offen ist und die Off-Hand
     * kein Totem hat, kommt eines hinein -- ein einziger Tausch-Klick.
     */
    private static void inventarOffen(net.minecraft.client.Minecraft client, LocalPlayer player,
                                      AutoTotemModule mod) {
        var screen = client.gui.screen();
        if (!(screen instanceof net.minecraft.client.gui.screens.inventory.InventoryScreen)) return;
        if (player.getOffhandItem().is(Items.TOTEM_OF_UNDYING)) return;
        if (!player.inventoryMenu.getCarried().isEmpty()) return;
        if (tickCounter - lastActionTick < nextDelay) return;
        int totem = findTotemInventorySlot(player);
        if (totem < 0) {
            if (mod.warnEmpty.get() && !warnedEmpty) {
                warnedEmpty = true;
                player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                        "\u00a7c[Auto Totem] No totems left."));
            }
            return;
        }
        warnedEmpty = false;
        int platz = inventoryIndexToScreenSlot(totem);
        if (platz < 0) return;
        try {
            client.gameMode.handleContainerInput(player.inventoryMenu.containerId, platz,
                    OFFHAND_TASTE, ContainerInput.SWAP, player);
            fertig(mod);
        } catch (Throwable pvpErr) {
            com.vortex.client.core.Errors.report("AutoTotem.inventory", pvpErr);
        }
    }

    /** Nach einem Tausch: Zeitpunkt merken, naechste Wartezeit wuerfeln. */
    private static void fertig(AutoTotemModule mod) {
        lastActionTick = tickCounter;
        int base = mod.delay.getInt();
        int spread = mod.jitter.getInt();
        nextDelay = base + ((spread > 0) ? RANDOM.nextInt(spread + 1) : 0);
    }

    /** Im Kreativ-Inventar sind die Plaetze anders verdrahtet -- dort nicht. */
    private static boolean istKreativ(Object screen) {
        return screen.getClass().getSimpleName().contains("Creative");
    }

    private static Module find(Class<? extends Module> type) {
        // Konstante Laufzeit statt die ganze Liste zu durchlaufen.
        return ModuleManager.INSTANCE.get(type);
    }
}
