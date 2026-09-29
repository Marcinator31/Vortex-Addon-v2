package com.vortex.client.bot;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.BarrelBlock;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Einlagern in Truhen fuer die Lauf-Bots.
 *
 * Ist das Inventar (fast) voll, laeuft der Bot zur naechsten Truhe oder zum
 * naechsten Fass in der Naehe, oeffnet sie, raeumt die Ernte hinein (Shift-
 * Klick, ein paar Stapel je Tick) und macht weiter. Behalten wird, was der
 * Bot selbst braucht: je ein Stapel Saatgut bzw. Setzlinge, Werkzeug, Essen.
 *
 * Eine volle Truhe merkt er sich und nimmt die naechste. Gibt es keine
 * (mehr), meldet er das einmal -- der Bot arbeitet dann ohne Einsammeln weiter.
 */
final class BotLager {

    private enum Schritt { AUS, HIN, OEFFNEN, RAEUMEN }

    private final String name;
    private Schritt schritt = Schritt.AUS;
    private long seit = 0;
    private BlockPos truhe = null;
    private int fensterId = -1;
    private int gelagert = 0;
    private boolean keineGemeldet = false;
    private long naechsteSuche = 0;
    /** Volle oder unerreichbare Truhen -> gesperrt bis Tick. */
    private final Map<BlockPos, Long> gesperrt = new HashMap<>();

    BotLager(String name) { this.name = name; }

    boolean aktiv() { return schritt != Schritt.AUS; }

    /** Wie viele Stapel bisher eingelagert wurden (fuer die Statuszeile). */
    int gelagert() { return gelagert; }

    void aus(Minecraft mc, LocalPlayer p) {
        if (schritt == Schritt.RAEUMEN || schritt == Schritt.OEFFNEN) schliessen(p);
        schritt = Schritt.AUS;
        truhe = null;
        fensterId = -1;
    }

    /** Vollstaendig zuruecksetzen (Bot aus). */
    void zuruecksetzen(Minecraft mc, LocalPlayer p) {
        aus(mc, p);
        gesperrt.clear();
        keineGemeldet = false;
        naechsteSuche = 0;
    }

    /**
     * Soll eingelagert werden? Startet den Ablauf, wenn es eine Truhe gibt.
     *
     * @return true, wenn eingelagert wird
     */
    boolean starten(Minecraft mc, LocalPlayer p, long tick, int radius, Predicate<ItemStack> einlagern, Set<Item> behalten) {
        if (aktiv()) return true;
        gesperrt.values().removeIf(bis -> bis < tick);
        if (!hatEtwas(p, einlagern, behalten)) return false;
        if (tick < naechsteSuche) return false;
        BlockPos t = sucheTruhe(mc, p, radius);
        if (t == null) {
            naechsteSuche = tick + 100;            // nicht jeden Tick den ganzen Bereich absuchen
            if (!keineGemeldet) {
                keineGemeldet = true;
                p.sendSystemMessage(Component.literal("§d[" + name + "]§r Inventory full and no free chest or barrel within "
                        + radius + " blocks -- place one next to the farm."));
            }
            return false;
        }
        keineGemeldet = false;
        truhe = t;
        schritt = Schritt.HIN;
        seit = tick;
        return true;
    }

    /**
     * Einen Tick einlagern. Muss VOR der Pruefung "Bildschirm offen?" des Bots
     * laufen -- waehrend des Einraeumens ist das Truhenfenster offen.
     *
     * @param behalten je ein Stapel dieser Gegenstaende bleibt im Inventar
     */
    void tick(Minecraft mc, LocalPlayer p, BotMotor motor, long tick,
              Predicate<ItemStack> einlagern, Set<Item> behalten) {
        if (truhe == null || !istTruhe(mc.level.getBlockState(truhe))) { aus(mc, p); return; }
        if (tick - seit > 20 * 40) {                            // 40 s: aufgeben
            gesperrt.put(truhe, tick + 20 * 120);
            aus(mc, p);
            return;
        }
        double reichweite = p.blockInteractionRange() - 0.5;
        switch (schritt) {
            case HIN -> {
                if (mc.gui.screen() != null) { motor.anhalten(mc); return; }
                Vec3 mitte = Vec3.atCenterOf(truhe);
                if (p.getEyePosition().distanceToSqr(mitte) <= reichweite * reichweite && p.onGround()) {
                    motor.anhalten(mc);
                    motor.vergiss();
                    oeffnen(mc, p, motor);
                    schritt = Schritt.OEFFNEN;
                    seit = tick - 20 * 30;                      // 10 s fuers Oeffnen
                    return;
                }
                // Etwas naeher heran als noetig -- die Wegsuche rechnet mit der Feldmitte.
                BotMotor.Lauf l = motor.laufe(mc, p, BotWeg.inReichweite(mitte.x, mitte.y, mitte.z, reichweite - 0.9, 1.62), truhe, tick);
                if (l == BotMotor.Lauf.DA && p.getEyePosition().distanceToSqr(mitte) <= (reichweite + 0.4) * (reichweite + 0.4)) {
                    motor.anhalten(mc);
                    motor.vergiss();
                    oeffnen(mc, p, motor);
                    schritt = Schritt.OEFFNEN;
                    seit = tick - 20 * 30;
                    return;
                }
                if (l == BotMotor.Lauf.UNERREICHBAR) {
                    gesperrt.put(truhe, tick + 20 * 120);
                    aus(mc, p);
                }
            }
            case OEFFNEN -> {
                motor.anhalten(mc);
                AbstractContainerMenu menu = p.containerMenu;
                if (menu != null && menu != p.inventoryMenu) {
                    fensterId = menu.containerId;
                    schritt = Schritt.RAEUMEN;
                } else if ((tick - seit) % 40 == 0) {
                    oeffnen(mc, p, motor);                      // noch einmal versuchen
                }
            }
            case RAEUMEN -> {
                AbstractContainerMenu menu = p.containerMenu;
                if (menu == null || menu == p.inventoryMenu || menu.containerId != fensterId) { aus(mc, p); return; }
                Set<Item> gesehen = new HashSet<>();
                int bewegt = 0;
                boolean uebrig = false;
                for (int i = 0; i < menu.slots.size(); i++) {
                    Slot s = menu.slots.get(i);
                    if (s.container != p.getInventory() || s.getContainerSlot() > 35) continue;
                    ItemStack st = s.getItem();
                    if (st.isEmpty() || !einlagern.test(st)) continue;
                    if (behalten.contains(st.getItem()) && gesehen.add(st.getItem())) continue;   // ersten Stapel behalten
                    if (bewegt >= 3) { uebrig = true; break; }                                  // Rest naechster Tick
                    int vorher = st.getCount();
                    mc.gameMode.handleContainerInput(menu.containerId, i, 0,
                            net.minecraft.world.inventory.ContainerInput.QUICK_MOVE, p);
                    bewegt++;
                    if (!s.getItem().isEmpty() && s.getItem().getCount() >= vorher) {
                        // Nichts bewegt: die Truhe ist voll.
                        gesperrt.put(truhe, tick + 20 * 300);
                        p.sendSystemMessage(Component.literal("§d[" + name + "]§r Chest at " + truhe.toShortString() + " is full."));
                        aus(mc, p);
                        return;
                    }
                    gelagert++;
                }
                if (!uebrig) aus(mc, p);
            }
            default -> aus(mc, p);
        }
    }

    private void oeffnen(Minecraft mc, LocalPlayer p, BotMotor motor) {
        Vec3 mitte = Vec3.atCenterOf(truhe);
        Direction seite = Direction.getApproximateNearest(p.getEyePosition().subtract(mitte));
        Vec3 treffer = mitte.add(seite.getStepX() * 0.5, seite.getStepY() * 0.5, seite.getStepZ() * 0.5);
        motor.blicke(p, treffer);
        mc.gameMode.useItemOn(p, InteractionHand.MAIN_HAND, new BlockHitResult(treffer, seite, truhe, false));
        p.swing(InteractionHand.MAIN_HAND);
    }

    private static void schliessen(LocalPlayer p) {
        try {
            if (p != null && p.containerMenu != null && p.containerMenu != p.inventoryMenu) p.closeContainer();
        } catch (Throwable ignored) { }
    }

    private BlockPos sucheTruhe(Minecraft mc, LocalPlayer p, int radius) {
        BlockPos mitte = p.blockPosition();
        BlockPos best = null;
        double bestD = Double.MAX_VALUE;
        for (BlockPos b : BlockPos.betweenClosed(mitte.offset(-radius, -4, -radius), mitte.offset(radius, 4, radius))) {
            if (!istTruhe(mc.level.getBlockState(b)) || gesperrt.containsKey(b)) continue;
            // Truhe mit festem Block darueber laesst sich nicht oeffnen
            BlockState oben = mc.level.getBlockState(b.above());
            if (mc.level.getBlockState(b).getBlock() instanceof ChestBlock && oben.isRedstoneConductor(mc.level, b.above())) continue;
            double d = p.distanceToSqr(Vec3.atCenterOf(b));
            if (d < bestD) { bestD = d; best = b.immutable(); }
        }
        return best;
    }

    private static boolean istTruhe(BlockState st) {
        return st.getBlock() instanceof ChestBlock || st.getBlock() instanceof BarrelBlock;
    }

    /** Liegt etwas Einzulagerndes im Inventar (ueber die behaltenen Stapel hinaus)? */
    static boolean hatEtwas(LocalPlayer p, Predicate<ItemStack> einlagern, Set<Item> behalten) {
        Set<Item> gesehen = new HashSet<>();
        for (int i = 0; i < 36; i++) {
            ItemStack st = p.getInventory().getItem(i);
            if (st.isEmpty() || !einlagern.test(st)) continue;
            if (behalten.contains(st.getItem()) && gesehen.add(st.getItem())) continue;
            return true;
        }
        return false;
    }
}
