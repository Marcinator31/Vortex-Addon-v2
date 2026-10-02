package com.vortex.client.cheat;

import com.vortex.client.module.ModuleManager;
import com.vortex.client.module.modules.AntiAnchorModule;
import java.util.HashMap;
import java.util.Map;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Anti Anchor (siehe AntiAnchorModule). Hoechstens eine Aktion pro Tick:
 *   1. fremder Anker in Reichweite: Schild setzen, dann laden + zuenden
 *   2. sonst: Block ueber den eigenen Kopf
 */
public final class AntiAnker {

    private AntiAnker() {}

    private static long tick = 0, zuletzt = -100;

    public static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            tick++;
            try {
                lauf(mc);
            } catch (Throwable e) {
                com.vortex.client.core.Errors.report("AntiAnchor", e);
            }
        });
    }

    private static void lauf(Minecraft mc) {
        AntiAnchorModule m = ModuleManager.INSTANCE.get(AntiAnchorModule.class);
        if (m == null || !m.isEnabled()) return;
        LocalPlayer p = mc.player;
        if (p == null || mc.level == null || mc.gameMode == null || mc.gui.screen() != null) return;
        if (tick - zuletzt < 1) return;
        Player gegner = gegner(mc, p, m.enemyRange.get());
        if (gegner == null) return;
        boolean ankerExplodieren = mc.level.dimension() != Level.NETHER;

        // 1. Fremde Anker bei mir
        if (ankerExplodieren && (m.shield.get() || m.detonate.get())) {
            BlockPos anker = fremderAnker(mc, p, gegner, m.range.get());
            if (anker != null && behandle(mc, p, anker, m)) {
                zuletzt = tick;
                return;
            }
        }
        // 2. Kopf blocken
        if (m.blockHead.get() && p.onGround()) {
            BlockPos kopf = BlockPos.containing(p.getX(), p.getBoundingBox().maxY + 0.01, p.getZ());
            BlockState st = mc.level.getBlockState(kopf);
            if (st.canBeReplaced() && mc.level.getEntities((net.minecraft.world.entity.Entity) null, new AABB(kopf), e -> e.isAlive() && e.blocksBuilding).isEmpty()) {
                int platz = Inv.hotbar(p, s -> s.is(Items.OBSIDIAN) || s.is(Items.CRYING_OBSIDIAN));
                if (platz >= 0 && setze(mc, p, kopf, null, platz, m.swing.get())) zuletzt = tick;
            }
        }
    }

    /** Schild, laden oder zuenden -- ein Schritt. @return true, wenn etwas getan wurde. */
    private static boolean behandle(Minecraft mc, LocalPlayer p, BlockPos anker, AntiAnchorModule m) {
        int glow = Inv.hotbar(p, s -> s.is(Items.GLOWSTONE));
        // a) Schild auf die Seite, die zu mir zeigt
        if (m.shield.get()) {
            BlockPos schild = schildPlatz(mc, p, anker);
            if (schild != null && glow >= 0) {
                return setze(mc, p, schild, anker, glow, m.swing.get());
            }
        }
        if (!m.detonate.get()) return false;
        // b) Lohnt/geht das Zuenden? Mein Schaden mit allem, was jetzt steht.
        Map<BlockPos, BlockState> ersetze = new HashMap<>();
        ersetze.put(anker, Blocks.AIR.defaultBlockState());
        float selbst = Sprengung.schaden(p, Vec3.atCenterOf(anker), Sprengung.ANKER, ersetze, 0);
        if (selbst > m.maxSelfDamage.get() || selbst >= Sprengung.leben(p) - 1f) return false;
        BlockState st = mc.level.getBlockState(anker);
        int ladung = st.getValue(BlockStateProperties.RESPAWN_ANCHOR_CHARGES);
        if (ladung == 0) {
            if (glow < 0) return false;
            klick(mc, p, anker, glow, m.swing.get());
            return true;
        }
        if (p.getOffhandItem().is(Items.GLOWSTONE)) return false;
        int frei = -1;
        for (int i = 0; i < 9; i++) if (p.getInventory().getItem(i).isEmpty()) { frei = i; break; }
        if (frei < 0) frei = Inv.hotbar(p, s -> !s.is(Items.GLOWSTONE) && !(s.getItem() instanceof BlockItem));
        if (frei < 0) return false;
        klick(mc, p, anker, frei, m.swing.get());
        return true;
    }

    /** Naechster Anker in Reichweite, der naeher bei mir steht als beim Gegner. */
    private static BlockPos fremderAnker(Minecraft mc, LocalPlayer p, Player gegner, double reichweite) {
        BlockPos mitte = p.blockPosition();
        Vec3 auge = p.getEyePosition();
        int r = (int) Math.ceil(reichweite);
        BlockPos best = null;
        double bestD = Double.MAX_VALUE;
        for (int dx = -r; dx <= r; dx++) {
            for (int dy = -2; dy <= 3; dy++) {
                for (int dz = -r; dz <= r; dz++) {
                    BlockPos q = mitte.offset(dx, dy, dz);
                    if (!mc.level.getBlockState(q).is(Blocks.RESPAWN_ANCHOR)) continue;
                    Vec3 c = Vec3.atCenterOf(q);
                    double d = c.distanceTo(auge);
                    if (d > reichweite) continue;
                    double zuMir = c.distanceTo(p.position().add(0, 1, 0));
                    double zuIhm = c.distanceTo(gegner.position().add(0, 1, 0));
                    if (zuMir > zuIhm) continue;          // eher seiner zum Zuenden auf ihn: nicht meiner Sorge
                    if (d < bestD) { bestD = d; best = q; }
                }
            }
        }
        return best;
    }

    /** Nachbar des Ankers Richtung meiner Augen -- frei, nicht in einem Spieler. Sonst null. */
    private static BlockPos schildPlatz(Minecraft mc, LocalPlayer p, BlockPos a) {
        Vec3 d = p.getEyePosition().subtract(Vec3.atCenterOf(a));
        Direction richtung = Direction.getApproximateNearest(d.x, d.y, d.z);
        BlockPos s = a.relative(richtung);
        BlockState st = mc.level.getBlockState(s);
        if (!st.canBeReplaced()) return null;              // dort steht schon etwas, das abschirmt
        if (!mc.level.getEntities((net.minecraft.world.entity.Entity) null, new AABB(s), e -> e.isAlive() && e.blocksBuilding).isEmpty()) return null;
        if (Vec3.atCenterOf(s).distanceTo(p.getEyePosition()) > 5.5) return null;
        return s;
    }

    private static Player gegner(Minecraft mc, LocalPlayer p, double weite) {
        Player best = null;
        double bestD = weite * weite;
        for (Player o : mc.level.players()) {
            if (o == p || !o.isAlive() || o.isSpectator()) continue;
            if (com.vortex.client.core.Friends.schuetzt(o)) continue;
            double d = o.distanceToSqr(p);
            if (d <= bestD) { bestD = d; best = o; }
        }
        return best;
    }

    /**
     * Block setzen: angelehnt an einen Nachbarn (nie an "ausser" -- ein Klick
     * auf den Anker wuerde ihn laden), sonst direkt in die Luft.
     */
    private static boolean setze(Minecraft mc, LocalPlayer p, BlockPos pos, BlockPos ausser, int platz, boolean schwingen) {
        int vorher = p.getInventory().getSelectedSlot();
        p.getInventory().setSelectedSlot(platz);
        try {
            for (Direction d : Direction.values()) {
                BlockPos n = pos.relative(d);
                if (n.equals(ausser)) continue;
                BlockState st = mc.level.getBlockState(n);
                if (st.isAir() || st.canBeReplaced()) continue;
                if (st.is(Blocks.RESPAWN_ANCHOR)) continue;
                Direction seite = d.getOpposite();
                Vec3 treffer = Vec3.atCenterOf(n).add(seite.getStepX() * 0.5, seite.getStepY() * 0.5, seite.getStepZ() * 0.5);
                mc.gameMode.useItemOn(p, InteractionHand.MAIN_HAND, new BlockHitResult(treffer, seite, n, false));
                if (schwingen) p.swing(InteractionHand.MAIN_HAND);
                return true;
            }
            mc.gameMode.useItemOn(p, InteractionHand.MAIN_HAND, new BlockHitResult(Vec3.atCenterOf(pos), Direction.UP, pos, false));
            if (schwingen) p.swing(InteractionHand.MAIN_HAND);
            return true;
        } finally {
            p.getInventory().setSelectedSlot(vorher);
        }
    }

    private static void klick(Minecraft mc, LocalPlayer p, BlockPos pos, int platz, boolean schwingen) {
        int vorher = p.getInventory().getSelectedSlot();
        p.getInventory().setSelectedSlot(platz);
        try {
            mc.gameMode.useItemOn(p, InteractionHand.MAIN_HAND, new BlockHitResult(Vec3.atCenterOf(pos), Direction.UP, pos, false));
            if (schwingen) p.swing(InteractionHand.MAIN_HAND);
        } finally {
            p.getInventory().setSelectedSlot(vorher);
        }
    }
}
