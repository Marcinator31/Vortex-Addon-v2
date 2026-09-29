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
import net.minecraft.world.item.ItemStack;
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
 *   0. einlagern (Truhenfenster offen) / essen, wenn hungrig
 *   1. laufender Abbau zu Ende bringen
 *   2. Inventar voll -> zur naechsten Truhe, Ernte einlagern
 *   3. abgeerntete Stellen in Reichweite neu bepflanzen
 *   4. reife Pflanze in Reichweite ernten -- sonst hinlaufen (mit Weg)
 *   5. Ernte am Boden einsammeln
 *   6. zu abgeernteten Stellen ausser Reichweite laufen
 */
public final class CropFarmer {

    private CropFarmer() {}

    private static final BotMotor MOTOR = new BotMotor();
    private static final BotEssen ESSEN = new BotEssen("Crop Farmer");
    private static final BotLager LAGER = new BotLager("Crop Farmer");
    private static long tick = 0;
    private static int geerntet = 0;
    private static long startZeit = 0;
    private static String status = "Idle";
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
    private static boolean abbauIstErnte = false;
    /** Ziel, zu dem gerade gelaufen wird (bleibt, bis es erledigt ist -- sonst neue Wege im Takt). */
    private static BlockPos laufZiel = null;
    private static ItemEntity sammelZiel = null;
    private static long letzteAktion = -100;
    private static boolean voll = false;

    public static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            tick++;
            try { tick(mc); } catch (Throwable e) {
                com.vortex.client.core.Errors.report("CropFarmer", e);
                MOTOR.aus(mc);
                ESSEN.aus(mc, mc.player);
                LAGER.aus(mc, mc.player);
                abbau = null;
            }
        });
    }

    /** Statuszeile fuer die Bot-Seite. */
    public static String status() {
        CropFarmerModule m = ModuleManager.INSTANCE.get(CropFarmerModule.class);
        if (m == null || !m.isEnabled()) return "Idle";
        long min = startZeit == 0 ? 0 : (System.currentTimeMillis() - startZeit) / 60000L;
        return status + "  |  " + geerntet + " harvested" + (LAGER.gelagert() > 0 ? "  |  " + LAGER.gelagert() + " stacks stored" : "")
                + "  |  " + min + " min";
    }

    private static void tick(Minecraft mc) {
        CropFarmerModule m = ModuleManager.INSTANCE.get(CropFarmerModule.class);
        LocalPlayer p = mc.player;
        boolean an = m != null && m.isEnabled() && p != null && mc.level != null && mc.gameMode != null;
        if (!an) {
            if (warAn) {
                MOTOR.aus(mc);
                ESSEN.aus(mc, p);
                LAGER.zuruecksetzen(mc, p);
                NACHPFLANZEN.clear();
                GESPERRT.clear();
                abbau = null;
                laufZiel = null;
                sammelZiel = null;
                if (p != null && geerntet > 0) {
                    long min = (System.currentTimeMillis() - startZeit) / 60000L;
                    p.sendSystemMessage(Component.literal("§d[Crop Farmer]§r Stopped: " + geerntet + " harvested in " + min + " min."));
                }
            }
            warAn = false;
            return;
        }
        if (!warAn) { geerntet = 0; startZeit = System.currentTimeMillis(); }
        warAn = true;
        if (!p.isAlive()) { MOTOR.anhalten(mc); return; }
        GESPERRT.values().removeIf(bis -> bis < tick);

        try {
            // Einlagern laeuft auch mit offenem Truhenfenster
            if (LAGER.aktiv()) {
                status = "Storing in chest";
                LAGER.tick(mc, p, MOTOR, tick, CropFarmer::einlagern, behalten());
                return;
            }
            if (mc.gui.screen() != null) { MOTOR.anhalten(mc); status = "Paused (menu open)"; return; }
            if (!m.eat.get()) ESSEN.aus(mc, p);
            else if (ESSEN.tick(mc, p, tick, 14)) { MOTOR.anhalten(mc); status = "Eating"; return; }
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
            status = "Harvesting";
            if (MOTOR.abbauen(mc, p, abbau, tick)) {
                if (abbauIstErnte && mc.level.getBlockState(abbau).isAir()) geerntet++;
                abbau = null;
                letzteAktion = tick;
            }
            return;
        }
        if (tick - letzteAktion < m.delay.getInt()) { MOTOR.anhalten(mc); return; }

        // 2. Inventar voll -> einlagern
        boolean voll = BotMotor.freiePlaetze(p) <= 1;
        if (voll && m.store.get() && LAGER.starten(mc, p, tick, m.range.getInt() + 8, CropFarmer::einlagern, behalten())) {
            MOTOR.anhalten(mc);
            return;
        }

        if (tick % 10 == 0) reif = suchen(mc, p, m);

        // 3. Nachpflanzen in Reichweite
        if (m.replant.get()) {
            for (Iterator<Map.Entry<BlockPos, Item>> it = NACHPFLANZEN.entrySet().iterator(); it.hasNext();) {
                var e = it.next();
                BlockPos pos = e.getKey();
                if (!mc.level.getBlockState(pos).isAir()) { it.remove(); continue; }
                if (auge.distanceToSqr(Vec3.atCenterOf(pos.below())) > reichweite * reichweite) continue;
                int slot = Inv.hotbar(p, st -> st.is(e.getValue()));
                if (slot < 0) slot = BotMotor.holeInHotbar(mc, p, st -> st.is(e.getValue()));
                if (slot < 0) { it.remove(); continue; }
                pflanzen(mc, p, pos, slot);
                it.remove();
                letzteAktion = tick;
                MOTOR.anhalten(mc);
                status = "Replanting";
                return;
            }
        }

        // 4. Reife Pflanze: erst in Reichweite ernten, sonst zur naechsten laufen
        BlockPos nah = null, fern = null;
        double nahD = Double.MAX_VALUE, fernD = Double.MAX_VALUE;
        for (BlockPos b : reif) {
            if (GESPERRT.containsKey(b) || !istReif(mc, b, m)) continue;
            double a = auge.distanceToSqr(Vec3.atCenterOf(b));
            if (a <= reichweite * reichweite) { if (a < nahD) { nahD = a; nah = b; } }
            else if (a < fernD) { fernD = a; fern = b; }
        }
        if (nah != null) {
            MOTOR.anhalten(mc);
            MOTOR.vergiss();
            laufZiel = null;
            BlockState st = mc.level.getBlockState(nah);
            Item saat = SAAT.get(st.getBlock());
            if (saat != null && m.replant.get()) NACHPFLANZEN.put(nah.immutable(), saat);
            abbau = nah.immutable();
            abbauIstErnte = true;
            status = "Harvesting";
            if (MOTOR.abbauen(mc, p, abbau, tick)) {
                if (mc.level.getBlockState(abbau).isAir()) geerntet++;
                abbau = null;
                letzteAktion = tick;
            }
            return;
        }
        if (laufZiel != null && (GESPERRT.containsKey(laufZiel) || !istReif(mc, laufZiel, m))) laufZiel = null;
        if (laufZiel == null) laufZiel = fern;
        if (laufZiel != null && m.walk.get()) {
            Vec3 c = Vec3.atCenterOf(laufZiel);
            status = "Walking to crops";
            laufen(mc, p, BotWeg.inReichweite(c.x, c.y, c.z, reichweite - 0.4, 1.62), laufZiel);
            return;
        }

        // 5. Ernte einsammeln
        if (m.collect.get() && !inventarVoll(mc, p, m)) {
            ItemEntity naechstes = sammelZiel != null && sammelZiel.isAlive() && !GESPERRT.containsKey(sammelZiel.blockPosition()) ? sammelZiel : null;
            double r = m.range.get();
            if (naechstes == null) {
                for (ItemEntity ie : mc.level.getEntitiesOfClass(ItemEntity.class, p.getBoundingBox().inflate(r, 3, r))) {
                    if (!ERNTE.contains(ie.getItem().getItem()) || GESPERRT.containsKey(ie.blockPosition())) continue;
                    if (naechstes == null || ie.distanceToSqr(p) < naechstes.distanceToSqr(p)) naechstes = ie;
                }
            }
            sammelZiel = naechstes;
            if (naechstes != null && m.walk.get()) {
                BlockPos b = naechstes.blockPosition();
                status = "Collecting drops";
                laufen(mc, p, BotWeg.feld(b.getX(), b.getY(), b.getZ()), naechstes.getUUID());
                return;
            }
        }

        // 6. Abgeerntete Stellen ausser Reichweite
        if (m.replant.get() && m.walk.get() && !NACHPFLANZEN.isEmpty()) {
            BlockPos naechste = null;
            for (BlockPos b : NACHPFLANZEN.keySet()) {
                if (GESPERRT.containsKey(b)) continue;
                if (naechste == null || p.distanceToSqr(Vec3.atCenterOf(b)) < p.distanceToSqr(Vec3.atCenterOf(naechste))) naechste = b;
            }
            if (naechste != null) {
                Vec3 c = Vec3.atCenterOf(naechste.below());
                status = "Walking to replant";
                laufen(mc, p, BotWeg.inReichweite(c.x, c.y + 0.5, c.z, reichweite - 0.4, 1.62), naechste);
                return;
            }
        }
        MOTOR.anhalten(mc);
        status = reif.isEmpty() ? "Waiting for crops to grow" : "Waiting";
    }

    private static void laufen(Minecraft mc, LocalPlayer p, BotWeg.Ziel ziel, Object schluessel) {
        if (MOTOR.laufe(mc, p, ziel, schluessel, tick) == BotMotor.Lauf.UNERREICHBAR) {
            // 1 Minute in Ruhe lassen
            if (schluessel instanceof BlockPos b) GESPERRT.put(b.immutable(), tick + 1200);
            else if (sammelZiel != null) { GESPERRT.put(sammelZiel.blockPosition(), tick + 1200); sammelZiel = null; }
            if (schluessel.equals(laufZiel)) laufZiel = null;
            MOTOR.anhalten(mc);
        }
    }

    /** Was in die Truhe darf: die Ernte. */
    private static boolean einlagern(ItemStack st) {
        return ERNTE.contains(st.getItem()) || st.is(Items.MELON) || st.is(Items.PUMPKIN);
    }

    /** Je ein Stapel Saatgut bleibt zum Nachpflanzen. */
    private static java.util.Set<Item> behalten() {
        return java.util.Set.copyOf(SAAT.values());
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

    private static boolean inventarVoll(Minecraft mc, LocalPlayer p, CropFarmerModule m) {
        if (BotMotor.freiePlaetze(p) > 0) { voll = false; return false; }
        if (!voll) {
            voll = true;
            p.sendSystemMessage(Component.literal("§d[Crop Farmer]§r Inventory full -- not collecting any more"
                    + (m.store.get() ? " (no free chest nearby)." : ".")));
        }
        return true;
    }
}
