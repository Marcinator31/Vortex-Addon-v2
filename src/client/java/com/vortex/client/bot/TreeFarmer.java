package com.vortex.client.bot;

import com.vortex.client.cheat.Inv;
import com.vortex.client.module.ModuleManager;
import com.vortex.client.module.modules.TreeFarmerModule;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Tree Farmer (siehe TreeFarmerModule).
 *
 *   SUCHEN     naechsten Baum finden (Stamm auf Erde + natuerliches Laub)
 *   HINGEHEN   an den Stamm laufen
 *   FAELLEN    Stammbloecke in Reichweite abbauen, von unten nach oben; danach
 *              in die Stammspalte treten, fuer hohe Baeume hochbauen
 *   ABSTEIGEN  den eigenen Turm wieder abbauen
 *   PFLANZEN   einen Schritt zur Seite, Setzling setzen
 *   SAMMELN    Holz und Setzlinge am Boden einsammeln
 */
public final class TreeFarmer {

    private TreeFarmer() {}

    private enum Phase { SUCHEN, HINGEHEN, FAELLEN, ABSTEIGEN, PFLANZEN, SAMMELN }

    private static final BotMotor MOTOR = new BotMotor();
    private static long tick = 0;
    private static boolean warAn = false;
    private static Phase phase = Phase.SUCHEN;
    private static long phaseSeit = 0;

    private static BlockPos basis = null;
    private static Set<BlockPos> stamm = new HashSet<>();
    private static Item setzling = null;
    private static BlockPos abbau = null;
    private static int standY = Integer.MIN_VALUE;
    private static boolean keinTurmGemeldet = false;
    private static final Map<BlockPos, Long> GESPERRT = new HashMap<>();

    public static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            tick++;
            try { tick(mc); } catch (Throwable e) {
                com.vortex.client.core.Errors.report("TreeFarmer", e);
                MOTOR.aus(mc);
                wechsel(Phase.SUCHEN);
            }
        });
    }

    private static void wechsel(Phase p) {
        phase = p;
        phaseSeit = tick;
        abbau = null;
        MOTOR.fortschrittZuruecksetzen();
    }

    private static void melde(LocalPlayer p, String text) {
        p.sendSystemMessage(Component.literal("§d[Tree Farmer]§r " + text));
    }

    private static void tick(Minecraft mc) {
        TreeFarmerModule m = ModuleManager.INSTANCE.get(TreeFarmerModule.class);
        LocalPlayer p = mc.player;
        boolean an = m != null && m.isEnabled() && p != null && mc.level != null && mc.gameMode != null;
        if (!an) {
            if (warAn) { MOTOR.aus(mc); wechsel(Phase.SUCHEN); basis = null; GESPERRT.clear(); }
            warAn = false;
            return;
        }
        warAn = true;
        if (!p.isAlive() || mc.gui.screen() != null) { MOTOR.anhalten(mc); return; }
        GESPERRT.values().removeIf(bis -> bis < tick);
        try {
            switch (phase) {
                case SUCHEN -> suchen(mc, p, m);
                case HINGEHEN -> hingehen(mc, p);
                case FAELLEN -> faellen(mc, p, m);
                case ABSTEIGEN -> absteigen(mc, p);
                case PFLANZEN -> pflanzen(mc, p, m);
                case SAMMELN -> sammeln(mc, p, m);
            }
        } finally {
            MOTOR.drehen(p, 30f);
        }
    }

    // ------------------------------------------------------------------

    private static void suchen(Minecraft mc, LocalPlayer p, TreeFarmerModule m) {
        MOTOR.anhalten(mc);
        if (tick % 20 != 0) return;
        int r = m.range.getInt();
        BlockPos mitte = p.blockPosition();
        BlockPos best = null;
        Set<BlockPos> bestStamm = null;
        double bestD = Double.MAX_VALUE;
        for (BlockPos b : BlockPos.betweenClosed(mitte.offset(-r, -4, -r), mitte.offset(r, 6, r))) {
            if (GESPERRT.containsKey(b)) continue;
            BlockState st = mc.level.getBlockState(b);
            if (!st.is(BlockTags.LOGS) || !mc.level.getBlockState(b.below()).is(BlockTags.DIRT)) continue;
            double d = p.distanceToSqr(Vec3.atCenterOf(b));
            if (d >= bestD) continue;
            Set<BlockPos> s = baum(mc, b.immutable());
            if (s == null) { GESPERRT.put(b.immutable(), tick + 6000); continue; }
            best = b.immutable();
            bestStamm = s;
            bestD = d;
        }
        if (best == null) return;
        basis = best;
        stamm = bestStamm;
        setzling = passenderSetzling(mc.level.getBlockState(best));
        standY = Integer.MIN_VALUE;
        keinTurmGemeldet = false;
        wechsel(Phase.HINGEHEN);
    }

    /** Alle Stammbloecke eines echten Baums ab basis -- oder null (kein Baum / zu gross). */
    private static Set<BlockPos> baum(Minecraft mc, BlockPos basis) {
        // 2x2-Stamm? Dann auslassen.
        for (Direction d : Direction.Plane.HORIZONTAL) {
            BlockPos n = basis.relative(d);
            if (mc.level.getBlockState(n).is(BlockTags.LOGS) && mc.level.getBlockState(n.below()).is(BlockTags.DIRT)) return null;
        }
        Set<BlockPos> s = new HashSet<>();
        ArrayDeque<BlockPos> offen = new ArrayDeque<>();
        offen.add(basis);
        s.add(basis);
        boolean laub = false;
        while (!offen.isEmpty()) {
            BlockPos b = offen.poll();
            for (int dx = -1; dx <= 1; dx++) for (int dy = 0; dy <= 1; dy++) for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dy == 0 && dz == 0) continue;
                BlockPos n = b.offset(dx, dy, dz);
                if (Math.abs(n.getX() - basis.getX()) > 6 || Math.abs(n.getZ() - basis.getZ()) > 6 || n.getY() - basis.getY() > 32) continue;
                BlockState st = mc.level.getBlockState(n);
                if (st.getBlock() instanceof LeavesBlock && !st.getValue(LeavesBlock.PERSISTENT)) laub = true;
                if (st.is(BlockTags.LOGS) && s.add(n)) {
                    if (s.size() > 80) return null;
                    offen.add(n);
                }
            }
        }
        return laub ? s : null;
    }

    private static Item passenderSetzling(BlockState stammSt) {
        try {
            String id = BuiltInRegistries.BLOCK.getKey(stammSt.getBlock()).getPath();
            String art = id.replace("stripped_", "").replace("_log", "").replace("_wood", "").replace("_stem", "");
            var rl = net.minecraft.resources.Identifier.tryParse("minecraft:" + art + "_sapling");
            if (art.equals("mangrove")) rl = net.minecraft.resources.Identifier.tryParse("minecraft:mangrove_propagule");
            if (rl == null) return null;
            return BuiltInRegistries.ITEM.getOptional(rl).orElse(null);
        } catch (Throwable e) {
            return null;
        }
    }

    private static void hingehen(Minecraft mc, LocalPlayer p) {
        if (basis == null || !mc.level.getBlockState(basis).is(BlockTags.LOGS)) { wechsel(Phase.SUCHEN); return; }
        double reichweite = p.blockInteractionRange() - 0.5;
        if (p.getEyePosition().distanceToSqr(Vec3.atCenterOf(basis)) <= reichweite * reichweite && p.onGround()) {
            MOTOR.anhalten(mc);
            wechsel(Phase.FAELLEN);
            return;
        }
        MOTOR.gehe(mc, p, Vec3.atBottomCenterOf(basis), true);
        if (MOTOR.steckt(p, tick) || tick - phaseSeit > 600) {
            GESPERRT.put(basis, tick + 2400);
            MOTOR.anhalten(mc);
            wechsel(Phase.SUCHEN);
        }
    }

    private static void faellen(Minecraft mc, LocalPlayer p, TreeFarmerModule m) {
        if (tick - phaseSeit > 20 * 120) { melde(p, "Tree took too long -- skipping it."); wechsel(Phase.ABSTEIGEN); return; }
        stamm.removeIf(b -> !mc.level.getBlockState(b).is(BlockTags.LOGS));
        if (stamm.isEmpty()) { MOTOR.anhalten(mc); wechsel(Phase.ABSTEIGEN); return; }

        if (abbau != null) {
            MOTOR.anhalten(mc);
            if (m.useAxe.get()) axt(p);
            if (MOTOR.abbauen(mc, p, abbau, tick)) abbau = null;
            return;
        }
        double reichweite = p.blockInteractionRange() - 0.3;
        Vec3 auge = p.getEyePosition();
        BlockPos ziel = null;
        for (BlockPos b : stamm) {
            if (auge.distanceToSqr(Vec3.atCenterOf(b)) > reichweite * reichweite) continue;
            if (ziel == null || b.getY() < ziel.getY()
                    || (b.getY() == ziel.getY() && auge.distanceToSqr(Vec3.atCenterOf(b)) < auge.distanceToSqr(Vec3.atCenterOf(ziel)))) ziel = b;
        }
        if (ziel != null) {
            MOTOR.anhalten(mc);
            if (m.useAxe.get()) axt(p);
            abbau = ziel;
            if (MOTOR.abbauen(mc, p, abbau, tick)) abbau = null;
            return;
        }
        // Nichts in Reichweite: in die Stammspalte treten, dann hochbauen.
        boolean inSpalte = Math.abs(p.getX() - (basis.getX() + 0.5)) < 0.3 && Math.abs(p.getZ() - (basis.getZ() + 0.5)) < 0.3;
        if (!inSpalte) {
            if (!mc.level.getBlockState(basis).isAir() || !mc.level.getBlockState(basis.above()).isAir()) {
                wechsel(Phase.ABSTEIGEN);
                return;
            }
            double d = MOTOR.gehe(mc, p, Vec3.atBottomCenterOf(basis), false);
            if (d < 0.25) MOTOR.anhalten(mc);
            if (MOTOR.steckt(p, tick)) wechsel(Phase.ABSTEIGEN);
            return;
        }
        MOTOR.anhalten(mc);
        if (!m.pillar.get()) { wechsel(Phase.ABSTEIGEN); return; }
        hochbauen(mc, p);
    }

    /** Springen und im hoechsten Punkt einen Block unter die Fuesse setzen. */
    private static void hochbauen(Minecraft mc, LocalPlayer p) {
        int slot = Inv.hotbar(p, st -> st.getItem() instanceof BlockItem bi
                && bi.getBlock().defaultBlockState().isCollisionShapeFullBlock(mc.level, BlockPos.ZERO)
                && !st.is(ItemTags.SAPLINGS));
        if (slot < 0) {
            if (!keinTurmGemeldet) { keinTurmGemeldet = true; melde(p, "Tree is taller than my reach and I have no blocks to build up -- leaving the top."); }
            wechsel(Phase.ABSTEIGEN);
            return;
        }
        if (p.onGround()) {
            standY = (int) Math.floor(p.getY() + 0.01);
            MOTOR.blickeRichtung(p.getYRot(), 90f);
            MOTOR.springen(mc, true);
            return;
        }
        MOTOR.springen(mc, false);
        BlockPos fuss = new BlockPos(basis.getX(), standY, basis.getZ());
        if (p.getY() >= standY + 1.0 && mc.level.getBlockState(fuss).canBeReplaced()) {
            BlockPos unten = fuss.below();
            Vec3 treffer = new Vec3(unten.getX() + 0.5, unten.getY() + 1.0, unten.getZ() + 0.5);
            int vorher = p.getInventory().getSelectedSlot();
            try {
                p.getInventory().setSelectedSlot(slot);
                mc.gameMode.useItemOn(p, InteractionHand.MAIN_HAND, new BlockHitResult(treffer, Direction.UP, unten, false));
                p.swing(InteractionHand.MAIN_HAND);
            } finally {
                p.getInventory().setSelectedSlot(vorher);
            }
        }
    }

    private static void absteigen(Minecraft mc, LocalPlayer p) {
        MOTOR.anhalten(mc);
        if (basis == null) { wechsel(Phase.SUCHEN); return; }
        if (tick - phaseSeit > 20 * 60) { wechsel(Phase.PFLANZEN); return; }
        // Nur den eigenen Turm in der Stammspalte abbauen -- steht der Bot
        // woanders (z. B. hangaufwaerts), wird NICHT in den Boden gegraben.
        boolean inSpalte = p.blockPosition().getX() == basis.getX() && p.blockPosition().getZ() == basis.getZ();
        if (!inSpalte) { wechsel(Phase.PFLANZEN); return; }
        if (p.getY() <= basis.getY() + 0.1) {
            if (p.onGround()) wechsel(Phase.PFLANZEN);
            return;
        }
        if (abbau == null) {
            if (!p.onGround()) return;
            BlockPos unter = p.blockPosition().below();
            if (unter.getY() < basis.getY()) { wechsel(Phase.PFLANZEN); return; }
            abbau = unter;
        }
        if (MOTOR.abbauen(mc, p, abbau, tick)) abbau = null;
    }

    private static void pflanzen(Minecraft mc, LocalPlayer p, TreeFarmerModule m) {
        if (!m.replant.get() || basis == null || !mc.level.getBlockState(basis).isAir()
                || !mc.level.getBlockState(basis.below()).is(BlockTags.DIRT)) {
            wechsel(Phase.SAMMELN);
            return;
        }
        Item wunsch = setzling;
        int slot = wunsch == null ? -1 : Inv.hotbar(p, st -> st.is(wunsch));
        if (slot < 0) slot = Inv.hotbar(p, st -> st.is(ItemTags.SAPLINGS));
        if (slot < 0) { wechsel(Phase.SAMMELN); return; }
        // Steht der Bot noch in der Stammspalte, erst einen Schritt zur Seite.
        if (p.getBoundingBox().intersects(new net.minecraft.world.phys.AABB(basis))) {
            BlockPos seite = null;
            for (Direction d : Direction.Plane.HORIZONTAL) {
                BlockPos n = basis.relative(d);
                if (mc.level.getBlockState(n).getCollisionShape(mc.level, n).isEmpty()
                        && mc.level.getBlockState(n.above()).getCollisionShape(mc.level, n.above()).isEmpty()) { seite = n; break; }
            }
            if (seite == null || tick - phaseSeit > 100) { wechsel(Phase.SAMMELN); return; }
            MOTOR.gehe(mc, p, Vec3.atBottomCenterOf(seite), false);
            return;
        }
        MOTOR.anhalten(mc);
        BlockPos boden = basis.below();
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
        wechsel(Phase.SAMMELN);
    }

    private static void sammeln(Minecraft mc, LocalPlayer p, TreeFarmerModule m) {
        if (!m.collect.get() || basis == null || tick - phaseSeit > 200) { MOTOR.anhalten(mc); wechsel(Phase.SUCHEN); return; }
        ItemEntity naechstes = null;
        for (ItemEntity ie : mc.level.getEntitiesOfClass(ItemEntity.class, new net.minecraft.world.phys.AABB(basis).inflate(7, 4, 7))) {
            ItemStack st = ie.getItem();
            if (!(st.is(ItemTags.LOGS) || st.is(ItemTags.SAPLINGS) || st.is(net.minecraft.world.item.Items.APPLE)
                    || st.is(net.minecraft.world.item.Items.STICK))) continue;
            if (naechstes == null || ie.distanceToSqr(p) < naechstes.distanceToSqr(p)) naechstes = ie;
        }
        if (naechstes == null) { MOTOR.anhalten(mc); wechsel(Phase.SUCHEN); return; }
        MOTOR.gehe(mc, p, naechstes.position(), true);
        if (MOTOR.steckt(p, tick)) { MOTOR.anhalten(mc); wechsel(Phase.SUCHEN); }
    }

    /** Beste Axt aus der Hotbar in die Hand (echt gewechselt -- der Server rechnet mit dem gehaltenen Werkzeug). */
    private static void axt(LocalPlayer p) {
        int best = -1;
        float bestTempo = 0;
        for (int i = 0; i < 9; i++) {
            ItemStack st = p.getInventory().getItem(i);
            if (!st.is(ItemTags.AXES)) continue;
            if (st.isDamageableItem() && st.getMaxDamage() - st.getDamageValue() <= 3) continue;
            float tempo = st.getDestroySpeed(net.minecraft.world.level.block.Blocks.OAK_LOG.defaultBlockState());
            if (tempo > bestTempo) { bestTempo = tempo; best = i; }
        }
        if (best >= 0 && p.getInventory().getSelectedSlot() != best) p.getInventory().setSelectedSlot(best);
    }
}
