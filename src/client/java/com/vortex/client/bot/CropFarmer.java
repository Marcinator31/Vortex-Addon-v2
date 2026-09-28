package com.vortex.client.bot;

import com.vortex.client.cheat.Inv;
import com.vortex.client.module.ModuleManager;
import com.vortex.client.module.modules.CropFarmerModule;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.NetherWartBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Crop Farmer (siehe CropFarmerModule).
 *
 * Ablauf je Tick, in dieser Reihenfolge:
 *   1. laufender Abbau zu Ende bringen
 *   2. abgeerntete Stellen in Reichweite neu bepflanzen
 *   3. reife Pflanze in Reichweite ernten -- sonst hinlaufen
 *   4. Ernte am Boden einsammeln
 *   5. zu abgeernteten Stellen ausser Reichweite laufen
 */
public final class CropFarmer {

    private CropFarmer() {}

    private static final BotMotor MOTOR = new BotMotor();
    private static long tick = 0;
    private static boolean warAn = false;

    /** Pflanze -> Saatgut. */
    private static final Map<Block, Item> SAAT = Map.of(
            Blocks.WHEAT, Items.WHEAT_SEEDS, Blocks.CARROTS, Items.CARROT, Blocks.POTATOES, Items.POTATO,
            Blocks.BEETROOTS, Items.BEETROOT_SEEDS, Blocks.NETHER_WART, Items.NETHER_WART);

    private static final java.util.Set<Item> ERNTE = java.util.Set.of(
            Items.WHEAT, Items.WHEAT_SEEDS, Items.CARROT, Items.POTATO, Items.POISONOUS_POTATO, Items.BEETROOT,
            Items.BEETROOT_SEEDS, Items.NETHER_WART, Items.MELON_SLICE, Items.PUMPKIN, Items.SUGAR_CANE);

    /** Abgeerntete Stellen (Pflanzen-Position) -> Saatgut. */
    private static final Map<BlockPos, Item> NACHPFLANZEN = new LinkedHashMap<>();
    /** Unerreichbare Ziele -> gesperrt bis Tick. */
    private static final Map<BlockPos, Long> GESPERRT = new HashMap<>();
    private static List<BlockPos> reif = new ArrayList<>();
    private static BlockPos abbau = null;
    private static long letzteAktion = -100;
    private static boolean voll = false;

    public static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            tick++;
            try { tick(mc); } catch (Throwable e) {
                com.vortex.client.core.Errors.report("CropFarmer", e);
                MOTOR.aus(mc);
                abbau = null;
            }
        });
    }

    private static void tick(Minecraft mc) {
        CropFarmerModule m = ModuleManager.INSTANCE.get(CropFarmerModule.class);
        LocalPlayer p = mc.player;
        boolean an = m != null && m.isEnabled() && p != null && mc.level != null && mc.gameMode != null;
        if (!an) {
            if (warAn) {
                MOTOR.aus(mc);
                NACHPFLANZEN.clear();
                GESPERRT.clear();
                abbau = null;
            }
            warAn = false;
            return;
        }
        warAn = true;
        if (!p.isAlive() || mc.gui.screen() != null) { MOTOR.anhalten(mc); return; }
        GESPERRT.values().removeIf(bis -> bis < tick);

        try {
            schritt(mc, p, m);
        } finally {
            MOTOR.drehen(p, 25f);
        }
    }

    private static void schritt(Minecraft mc, LocalPlayer p, CropFarmerModule m) {
        double reichweite = p.blockInteractionRange() - 0.3;
        Vec3 auge = p.getEyePosition();

        // 1. Abbau zu Ende bringen
        if (abbau != null) {
            MOTOR.anhalten(mc);
            if (MOTOR.abbauen(mc, p, abbau, tick)) { abbau = null; letzteAktion = tick; }
            return;
        }
        if (tick - letzteAktion < m.delay.getInt()) { MOTOR.anhalten(mc); return; }

        if (tick % 10 == 0) reif = suchen(mc, p, m);

        // 2. Nachpflanzen in Reichweite
        if (m.replant.get()) {
            for (Iterator<Map.Entry<BlockPos, Item>> it = NACHPFLANZEN.entrySet().iterator(); it.hasNext();) {
                var e = it.next();
                BlockPos pos = e.getKey();
                if (!mc.level.getBlockState(pos).isAir()) { it.remove(); continue; }
                if (auge.distanceToSqr(Vec3.atCenterOf(pos.below())) > reichweite * reichweite) continue;
                int slot = Inv.hotbar(p, st -> st.is(e.getValue()));
                if (slot < 0) slot = holeInHotbar(mc, p, e.getValue());
                if (slot < 0) { it.remove(); continue; }
                pflanzen(mc, p, pos, slot);
                it.remove();
                letzteAktion = tick;
                MOTOR.anhalten(mc);
                return;
            }
        }

        // 3. Reife Pflanze
        BlockPos ziel = null;
        double bestAbstand = Double.MAX_VALUE;
        for (BlockPos b : reif) {
            if (GESPERRT.containsKey(b) || !istReif(mc, b, m)) continue;
            double d = p.position().distanceToSqr(Vec3.atCenterOf(b));
            if (d < bestAbstand) { bestAbstand = d; ziel = b; }
        }
        if (ziel != null) {
            if (auge.distanceToSqr(Vec3.atCenterOf(ziel)) <= reichweite * reichweite) {
                MOTOR.anhalten(mc);
                MOTOR.fortschrittZuruecksetzen();
                BlockState st = mc.level.getBlockState(ziel);
                Item saat = SAAT.get(st.getBlock());
                if (saat != null && m.replant.get()) NACHPFLANZEN.put(ziel.immutable(), saat);
                abbau = ziel.immutable();
                if (MOTOR.abbauen(mc, p, abbau, tick)) { abbau = null; letzteAktion = tick; }
                return;
            }
            if (m.walk.get()) { laufen(mc, p, Vec3.atBottomCenterOf(ziel), ziel); return; }
        }

        // 4. Ernte einsammeln
        if (m.collect.get() && !inventarVoll(mc, p)) {
            ItemEntity naechstes = null;
            double r = m.range.get();
            for (ItemEntity ie : mc.level.getEntitiesOfClass(ItemEntity.class, p.getBoundingBox().inflate(r, 3, r))) {
                if (!ERNTE.contains(ie.getItem().getItem()) || GESPERRT.containsKey(ie.blockPosition())) continue;
                if (naechstes == null || ie.distanceToSqr(p) < naechstes.distanceToSqr(p)) naechstes = ie;
            }
            if (naechstes != null) { laufen(mc, p, naechstes.position(), naechstes.blockPosition()); return; }
        }

        // 5. Abgeerntete Stellen ausser Reichweite
        if (m.replant.get() && m.walk.get() && !NACHPFLANZEN.isEmpty()) {
            BlockPos naechste = null;
            for (BlockPos b : NACHPFLANZEN.keySet()) {
                if (GESPERRT.containsKey(b)) continue;
                if (naechste == null || p.distanceToSqr(Vec3.atCenterOf(b)) < p.distanceToSqr(Vec3.atCenterOf(naechste))) naechste = b;
            }
            if (naechste != null) { laufen(mc, p, Vec3.atBottomCenterOf(naechste), naechste); return; }
        }
        MOTOR.anhalten(mc);
    }

    private static void laufen(Minecraft mc, LocalPlayer p, Vec3 ziel, BlockPos schluessel) {
        boolean acker = mc.level.getBlockState(p.blockPosition()).is(Blocks.FARMLAND)
                || mc.level.getBlockState(p.blockPosition().below()).is(Blocks.FARMLAND);
        MOTOR.gehe(mc, p, ziel, !acker);
        if (MOTOR.steckt(p, tick)) {
            GESPERRT.put(schluessel.immutable(), tick + 1200);   // 1 Minute in Ruhe lassen
            MOTOR.anhalten(mc);
        }
    }

    private static List<BlockPos> suchen(Minecraft mc, LocalPlayer p, CropFarmerModule m) {
        List<BlockPos> out = new ArrayList<>();
        int r = m.range.getInt();
        BlockPos mitte = p.blockPosition();
        for (BlockPos b : BlockPos.betweenClosed(mitte.offset(-r, -3, -r), mitte.offset(r, 3, r))) {
            if (istReif(mc, b, m)) out.add(b.immutable());
            if (out.size() > 512) break;
        }
        return out;
    }

    private static boolean istReif(Minecraft mc, BlockPos b, CropFarmerModule m) {
        BlockState st = mc.level.getBlockState(b);
        Block bl = st.getBlock();
        if (bl instanceof CropBlock c) return m.wheat.get() && SAAT.containsKey(bl) && c.isMaxAge(st);
        if (bl instanceof NetherWartBlock) return m.netherWart.get() && st.getValue(NetherWartBlock.AGE) >= NetherWartBlock.MAX_AGE;
        if (st.is(Blocks.MELON) || st.is(Blocks.PUMPKIN)) {
            if (!m.melons.get()) return false;
            for (Direction d : Direction.Plane.HORIZONTAL) {
                BlockState n = mc.level.getBlockState(b.relative(d));
                if ((n.is(Blocks.ATTACHED_MELON_STEM) || n.is(Blocks.ATTACHED_PUMPKIN_STEM))
                        && n.getValue(net.minecraft.world.level.block.AttachedStemBlock.FACING) == d.getOpposite()) return true;
            }
            return false;
        }
        if (st.is(Blocks.SUGAR_CANE)) {
            return m.sugarCane.get() && mc.level.getBlockState(b.below()).is(Blocks.SUGAR_CANE)
                    && !mc.level.getBlockState(b.below(2)).is(Blocks.SUGAR_CANE);
        }
        return false;
    }

    /** Setzt Saatgut aus Hotbar-Platz "slot" auf den Boden unter pos (Ackerboden / Seelensand). */
    private static void pflanzen(Minecraft mc, LocalPlayer p, BlockPos pos, int slot) {
        BlockPos boden = pos.below();
        Vec3 treffer = new Vec3(boden.getX() + 0.5, boden.getY() + 1.0, boden.getZ() + 0.5);
        MOTOR.blicke(p, treffer);
        int vorher = p.getInventory().getSelectedSlot();
        try {
            p.getInventory().setSelectedSlot(slot);
            mc.gameMode.useItemOn(p, InteractionHand.MAIN_HAND, new BlockHitResult(treffer, Direction.UP, boden, false));
            p.swing(InteractionHand.MAIN_HAND);
        } finally {
            p.getInventory().setSelectedSlot(vorher);
        }
    }

    /** Saatgut aus dem Rucksack in die Hotbar holen; gibt den Hotbar-Platz zurueck oder -1. */
    private static int holeInHotbar(Minecraft mc, LocalPlayer p, Item item) {
        int quelle = Inv.inventar(p, st -> st.is(item));
        if (quelle < 0) return -1;
        int ziel = -1;
        for (int i = 8; i >= 0; i--) if (p.getInventory().getItem(i).isEmpty()) { ziel = i; break; }
        if (ziel < 0) ziel = 8;
        mc.gameMode.handleContainerInput(p.inventoryMenu.containerId, Inv.fensterPlatz(quelle), ziel,
                net.minecraft.world.inventory.ContainerInput.SWAP, p);
        return ziel;
    }

    private static boolean inventarVoll(Minecraft mc, LocalPlayer p) {
        for (int i = 0; i < 36; i++) if (p.getInventory().getItem(i).isEmpty()) { voll = false; return false; }
        if (!voll) {
            voll = true;
            p.sendSystemMessage(Component.literal("§d[Crop Farmer]§r Inventory full -- not collecting any more."));
        }
        return true;
    }
}
