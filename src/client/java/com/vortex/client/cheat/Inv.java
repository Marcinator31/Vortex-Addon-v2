package com.vortex.client.cheat;

import java.util.function.Predicate;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Gemeinsame Handgriffe der Cheat-Module: Gegenstaende finden, umlagern,
 * Bloecke setzen und kurz "unsichtbar" in eine Richtung schauen.
 *
 * Alles ueber dieselben Aufrufe, die der Netherite-Bot seit Monaten benutzt
 * (handleContainerInput, useItemOn, useItem) -- keine neuen Wege.
 */
public final class Inv {

    private Inv() {}

    /** Hotbar-Platz 0..8 mit passendem Gegenstand, sonst -1. */
    public static int hotbar(LocalPlayer p, Predicate<ItemStack> passt) {
        for (int i = 0; i < 9; i++) {
            ItemStack st = p.getInventory().getItem(i);
            if (st != null && !st.isEmpty() && passt.test(st)) return i;
        }
        return -1;
    }

    /** Platz im Hauptinventar 9..35 mit passendem Gegenstand, sonst -1. */
    public static int inventar(LocalPlayer p, Predicate<ItemStack> passt) {
        int n = Math.min(36, p.getInventory().getContainerSize());
        for (int i = 9; i < n; i++) {
            ItemStack st = p.getInventory().getItem(i);
            if (st != null && !st.isEmpty() && passt.test(st)) return i;
        }
        return -1;
    }

    /** Inventar-Index (0..35) -> Platznummer im Inventarfenster. */
    public static int fensterPlatz(int index) {
        if (index >= 0 && index <= 8) return 36 + index;
        if (index >= 9 && index <= 35) return index;
        return -1;
    }

    /** Linksklick auf einen Platz im Inventarfenster. */
    public static void klick(Minecraft mc, int platz) {
        LocalPlayer p = mc.player;
        if (p == null || mc.gameMode == null) return;
        mc.gameMode.handleContainerInput(p.inventoryMenu.containerId, platz, 0, ContainerInput.PICKUP, p);
    }

    /** Shift-Klick auf einen Platz in einem beliebigen Fenster. */
    public static void shiftKlick(Minecraft mc, int containerId, int platz) {
        LocalPlayer p = mc.player;
        if (p == null || mc.gameMode == null) return;
        mc.gameMode.handleContainerInput(containerId, platz, 0, ContainerInput.QUICK_MOVE, p);
    }

    /**
     * Tauscht zwei Plaetze im Inventarfenster: aufnehmen, ablegen, und was
     * dort lag, zurueck an den Ursprung.
     */
    public static void tausche(Minecraft mc, int von, int nach) {
        LocalPlayer p = mc.player;
        if (p == null) return;
        klick(mc, von);
        klick(mc, nach);
        if (!p.inventoryMenu.getCarried().isEmpty()) klick(mc, von);
    }

    /**
     * Setzt einen Block an pos, angelehnt an einen festen Nachbarn.
     *
     * @return true, wenn ein Nachbar gefunden und gesetzt wurde
     */
    public static boolean setze(Minecraft mc, BlockPos pos, boolean schwingen) {
        LocalPlayer p = mc.player;
        if (p == null || mc.level == null || mc.gameMode == null) return false;
        for (Direction d : Direction.values()) {
            BlockPos nachbar = pos.relative(d);
            BlockState st = mc.level.getBlockState(nachbar);
            if (st.isAir() || st.canBeReplaced()) continue;
            Direction seite = d.getOpposite();
            Vec3 treffer = Vec3.atCenterOf(nachbar).add(
                    seite.getStepX() * 0.5, seite.getStepY() * 0.5, seite.getStepZ() * 0.5);
            mc.gameMode.useItemOn(p, InteractionHand.MAIN_HAND,
                    new BlockHitResult(treffer, seite, nachbar, false));
            if (schwingen) p.swing(InteractionHand.MAIN_HAND);
            return true;
        }
        return false;
    }

    /** Hat pos einen festen Nachbarn, an den man anlehnen kann? */
    public static boolean hatNachbar(Minecraft mc, BlockPos pos) {
        for (Direction d : Direction.values()) {
            BlockState st = mc.level.getBlockState(pos.relative(d));
            if (!st.isAir() && !st.canBeReplaced()) return true;
        }
        return false;
    }

    /**
     * Benutzt den Gegenstand aus Hotbar-Platz "platz" und schaut dabei kurz
     * in die angegebene Richtung. Der Server bekommt die Richtung mit dem
     * Benutzen-Paket, die Kamera springt nicht mit.
     */
    public static void benutzeMitBlick(Minecraft mc, int platz, Float pitch) {
        LocalPlayer p = mc.player;
        if (p == null || mc.gameMode == null || platz < 0 || platz > 8) return;
        int vorher = p.getInventory().getSelectedSlot();
        float alterPitch = p.getXRot();
        try {
            p.getInventory().setSelectedSlot(platz);
            if (pitch != null) p.setXRot(pitch);
            mc.gameMode.useItem(p, InteractionHand.MAIN_HAND);
        } finally {
            if (pitch != null) p.setXRot(alterPitch);
            p.getInventory().setSelectedSlot(vorher);
        }
    }

    /** Blickwinkel zu einem Punkt (yaw, pitch). */
    public static float[] blickZu(LocalPlayer p, Vec3 ziel) {
        Vec3 auge = p.getEyePosition();
        double dx = ziel.x - auge.x, dy = ziel.y - auge.y, dz = ziel.z - auge.z;
        double flach = Math.sqrt(dx * dx + dz * dz);
        float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, flach));
        return new float[]{yaw, pitch};
    }
}
