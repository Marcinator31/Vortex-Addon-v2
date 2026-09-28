package com.vortex.client.cheat;

import com.mojang.blaze3d.vertex.PoseStack;
import com.vortex.client.hud.EspRender;
import com.vortex.client.module.ModuleManager;
import com.vortex.client.module.modules.BedAuraModule;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Bed Aura (siehe BedAuraModule).
 *
 * GEPRUEFT an 26.x (BedItem/BedBlock):
 *   - Das Bett wird an der angeklickten Stelle ("Fuss") gesetzt, das Kopfteil
 *     liegt in Blickrichtung des Spielers daneben. Deshalb wird vor dem Setzen
 *     die passende Blickrichtung an den Server geschickt.
 *   - Nur das Fussteil muss frei von Wesen sein -- das Kopfteil darf IN dem
 *     Gegner stecken. Dort ist der Schaden am groessten.
 *   - Benutzt man ein Bett, wo man nicht schlafen kann, explodiert es mit
 *     Staerke 5 in der Mitte des ANGEKLICKTEN Teils (beide Teile werden
 *     vorher entfernt). Wir klicken deshalb das Kopfteil.
 *   - Beim Schleichen setzt ein Klick ein weiteres Bett statt zu sprengen --
 *     dann pausiert die Aura.
 */
public final class BedAura {

    private BedAura() {}

    private record Plan(BlockPos kopf, BlockPos fuss, Direction richtung, float ziel, float selbst) {}

    private static long tick = 0;
    private static long zuletzt = -100;
    private static Plan offen = null;
    private static long gesetztAm = 0;
    private static BlockPos anzeige = null;
    private static long anzeigeBis = 0;

    public static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            tick++;
            try { tick(mc); } catch (Throwable e) { com.vortex.client.core.Errors.report("BedAura", e); offen = null; }
        });
        LevelRenderEvents.AFTER_TRANSLUCENT_FEATURES.register(context -> {
            try {
                BedAuraModule m = modul();
                if (m == null || !m.render.get() || anzeige == null || tick > anzeigeBis) return;
                Minecraft mc = Minecraft.getInstance();
                PoseStack ms = context.poseStack();
                SubmitNodeCollector col = context.submitNodeCollector();
                if (ms == null || col == null || mc.level == null) return;
                float td = mc.getDeltaTracker().getGameTimeDeltaPartialTick(false);
                int f = m.color.get();
                if ((f >>> 24) == 0) f |= 0xFF000000;
                EspRender.submitBox(col, ms, new AABB(anzeige), EspRender.cameraOffset(mc, td), f, 2.0f);
            } catch (Throwable e) {
                com.vortex.client.core.Errors.report("BedAura.render", e);
            }
        });
    }

    private static BedAuraModule modul() {
        BedAuraModule m = ModuleManager.INSTANCE.get(BedAuraModule.class);
        return m != null && m.isEnabled() ? m : null;
    }

    /** Explodieren Betten hier? (Nether/End -- ueber die Bett-Regel der Dimension) */
    private static boolean bettExplodiert(Minecraft mc, BlockPos pos) {
        try {
            return mc.level.environmentAttributes()
                    .getValue(net.minecraft.world.attribute.EnvironmentAttributes.BED_RULE, pos).explodes();
        } catch (Throwable e) {
            return mc.level.dimension() != Level.OVERWORLD;
        }
    }

    private static void tick(Minecraft mc) {
        BedAuraModule m = modul();
        LocalPlayer p = mc.player;
        if (m == null || p == null || mc.level == null || mc.gameMode == null) { offen = null; return; }
        if (mc.gui.screen() != null || p.isShiftKeyDown()) return;
        if (m.pauseEating.get() && p.isUsingItem()) return;
        if (m.pauseHealth.getInt() > 0 && Sprengung.leben(p) <= m.pauseHealth.get()) return;
        if (!bettExplodiert(mc, p.blockPosition())) return;

        // 1. Gesetztes Bett sprengen
        if (offen != null) {
            if (tick - gesetztAm < m.explodeDelay.getInt()) return;
            BlockState st = mc.level.getBlockState(offen.kopf());
            if (st.getBlock() instanceof BedBlock) {
                // Noch einmal nachrechnen: das Ziel kann sich bewegt haben.
                if (sicher(p, offen.kopf(), m) >= 0) sprengen(mc, p, m, offen.kopf());
                offen = null;
                zuletzt = tick;
            } else if (tick - gesetztAm > 10) {
                offen = null;                 // Server hat nicht gesetzt
            }
            return;
        }
        if (tick - zuletzt < m.delay.getInt()) return;

        List<LivingEntity> ziele = ziele(mc, p, m);
        if (ziele.isEmpty()) return;

        // 2. Schon stehende Betten nutzen (z. B. vom Gegner)
        BlockPos bett = vorhandenesBett(mc, p, m, ziele);
        if (bett != null) {
            sprengen(mc, p, m, bett);
            zuletzt = tick;
            return;
        }

        // 3. Neues Bett setzen
        int slot = Inv.hotbar(p, st -> st.is(ItemTags.BEDS));
        if (slot < 0) return;
        Plan plan = besterPlan(mc, p, m, ziele);
        if (plan == null) return;
        setzen(mc, p, m, plan, slot);
        offen = plan;
        gesetztAm = tick;
        anzeige = plan.kopf();
        anzeigeBis = tick + 10;
        if (m.explodeDelay.getInt() == 0) {
            sprengen(mc, p, m, plan.kopf());
            offen = null;
            zuletzt = tick;
        }
    }

    private static List<LivingEntity> ziele(Minecraft mc, LocalPlayer p, BedAuraModule m) {
        double r = m.targetRange.get();
        List<LivingEntity> liste = new ArrayList<>();
        Iterable<? extends LivingEntity> alle = m.targets.getIndex() == 0
                ? mc.level.players()
                : mc.level.getEntitiesOfClass(LivingEntity.class, p.getBoundingBox().inflate(r));
        for (LivingEntity e : alle) {
            if (e == p || !e.isAlive() || e.isSpectator()) continue;
            if (e instanceof Player pl && pl.isCreative()) continue;
            if (com.vortex.client.core.Friends.schuetzt(e)) continue;
            if (e.distanceToSqr(p) > r * r) continue;
            liste.add(e);
        }
        liste.sort(Comparator.comparingDouble(e -> e.distanceToSqr(p)));
        return liste.size() > 3 ? new ArrayList<>(liste.subList(0, 3)) : liste;
    }

    /** Eigener Schaden, wenn das Kopfteil hier explodiert; -1 = zu gefaehrlich. */
    private static float sicher(LocalPlayer p, BlockPos kopf, BedAuraModule m) {
        float d = Sprengung.schaden(p, Vec3.atCenterOf(kopf), Sprengung.ANKER, ohneBett(kopf), 0);
        if (d > m.maxSelfDamage.get()) return -1;
        if (m.antiSuicide.get() && d >= Sprengung.leben(p) - 1.0f) return -1;
        return d;
    }

    /**
     * Beim Sprengen sind beide Bett-Teile schon weg -- fuer die Rechnung Luft
     * einsetzen, sonst schirmt das eigene Bett die Explosion scheinbar ab.
     */
    private static java.util.Map<BlockPos, BlockState> ohneBett(BlockPos kopf) {
        Minecraft mc = Minecraft.getInstance();
        BlockState st = mc.level.getBlockState(kopf);
        if (!(st.getBlock() instanceof BedBlock)) return null;
        java.util.Map<BlockPos, BlockState> m = new java.util.HashMap<>();
        BlockState luft = net.minecraft.world.level.block.Blocks.AIR.defaultBlockState();
        m.put(kopf, luft);
        m.put(kopf.relative(BedBlock.getConnectedDirection(st)), luft);
        return m;
    }

    /** Groesster Schaden an einem Ziel, wenn es genug ist (oder toedlich); sonst -1. */
    private static float zielSchaden(List<LivingEntity> ziele, BlockPos kopf, BedAuraModule m) {
        Vec3 mitte = Vec3.atCenterOf(kopf);
        float best = -1;
        for (LivingEntity z : ziele) {
            if (z.position().distanceToSqr(mitte) > 11 * 11) continue;
            float d = Sprengung.schaden(z, mitte, Sprengung.ANKER, ohneBett(kopf), 1);
            if (d < m.minDamage.get() && d < Sprengung.leben(z)) continue;
            if (d > best) best = d;
        }
        return best;
    }

    private static BlockPos vorhandenesBett(Minecraft mc, LocalPlayer p, BedAuraModule m, List<LivingEntity> ziele) {
        double r = m.placeRange.get();
        Vec3 auge = p.getEyePosition();
        BlockPos best = null;
        float bestWert = -1;
        for (LivingEntity z : ziele) {
            BlockPos zb = z.blockPosition();
            for (BlockPos b : BlockPos.betweenClosed(zb.offset(-2, -1, -2), zb.offset(2, 2, 2))) {
                if (!(mc.level.getBlockState(b).getBlock() instanceof BedBlock)) continue;
                if (auge.distanceToSqr(Vec3.atCenterOf(b)) > r * r) continue;
                float s = sicher(p, b, m);
                if (s < 0) continue;
                float d = zielSchaden(ziele, b, m);
                if (d < 0) continue;
                float wert = d - s * 0.5f;
                if (wert > bestWert) { bestWert = wert; best = b.immutable(); }
            }
        }
        return best;
    }

    private static Plan besterPlan(Minecraft mc, LocalPlayer p, BedAuraModule m, List<LivingEntity> ziele) {
        double r = m.placeRange.get();
        Vec3 auge = p.getEyePosition();
        Plan best = null;
        float bestWert = -Float.MAX_VALUE;
        java.util.Set<BlockPos> geprueft = new java.util.HashSet<>();
        for (LivingEntity z : ziele) {
            BlockPos zb = z.blockPosition();
            for (BlockPos k : BlockPos.betweenClosed(zb.offset(-2, -1, -2), zb.offset(2, 1, 2))) {
                BlockPos kopf = k.immutable();
                if (!geprueft.add(kopf)) continue;
                if (!mc.level.getBlockState(kopf).canBeReplaced()) continue;
                if (auge.distanceToSqr(Vec3.atCenterOf(kopf)) > r * r) continue;
                float ziel = zielSchaden(ziele, kopf, m);
                if (ziel < 0) continue;
                float selbst = sicher(p, kopf, m);
                if (selbst < 0) continue;
                for (Direction d : Direction.Plane.HORIZONTAL) {
                    BlockPos fuss = kopf.relative(d.getOpposite());
                    if (!mc.level.getBlockState(fuss).canBeReplaced()) continue;
                    if (auge.distanceToSqr(Vec3.atCenterOf(fuss)) > r * r) continue;
                    // Nur das Fussteil muss frei von Wesen sein (Bett ist 9/16 hoch).
                    AABB platz = new AABB(fuss.getX(), fuss.getY(), fuss.getZ(),
                            fuss.getX() + 1, fuss.getY() + 0.5625, fuss.getZ() + 1);
                    if (!mc.level.getEntities(null, platz).isEmpty()) continue;
                    if (anlehnen(mc, fuss) == null) continue;
                    float wert = ziel - selbst * 0.5f;
                    if (wert > bestWert) {
                        bestWert = wert;
                        best = new Plan(kopf, fuss, d, ziel, selbst);
                    }
                }
            }
        }
        return best;
    }

    /** Ein fester Nachbar des Fussteils zum Anklicken (nicht das Kopfteil). */
    private static Direction anlehnen(Minecraft mc, BlockPos fuss) {
        for (Direction d : new Direction[]{Direction.DOWN, Direction.NORTH, Direction.SOUTH,
                Direction.WEST, Direction.EAST, Direction.UP}) {
            BlockPos n = fuss.relative(d);
            BlockState st = mc.level.getBlockState(n);
            if (st.isAir() || st.canBeReplaced() || st.getBlock() instanceof BedBlock) continue;
            return d;
        }
        return null;
    }

    private static void setzen(Minecraft mc, LocalPlayer p, BedAuraModule m, Plan plan, int slot) {
        Direction nach = anlehnen(mc, plan.fuss());
        if (nach == null) return;
        BlockPos nachbar = plan.fuss().relative(nach);
        Direction seite = nach.getOpposite();
        Vec3 treffer = Vec3.atCenterOf(nachbar).add(seite.getStepX() * 0.5, seite.getStepY() * 0.5, seite.getStepZ() * 0.5);

        float yaw = plan.richtung().toYRot();
        float pitch = Inv.blickZu(p, treffer)[1];
        var net = mc.getConnection();
        if (net != null) net.send(new ServerboundMovePlayerPacket.Rot(yaw, pitch, p.onGround(), p.horizontalCollision));

        int vorher = p.getInventory().getSelectedSlot();
        float alterYaw = p.getYRot(), alterPitch = p.getXRot();
        try {
            // Auch lokal so drehen, damit die Vorhersage des Clients dasselbe Bett setzt.
            p.setYRot(yaw);
            p.setXRot(pitch);
            p.getInventory().setSelectedSlot(slot);
            mc.gameMode.useItemOn(p, InteractionHand.MAIN_HAND, new BlockHitResult(treffer, seite, nachbar, false));
            if (m.swing.get()) p.swing(InteractionHand.MAIN_HAND);
        } finally {
            p.setYRot(alterYaw);
            p.setXRot(alterPitch);
            p.getInventory().setSelectedSlot(vorher);
        }
    }

    private static void sprengen(Minecraft mc, LocalPlayer p, BedAuraModule m, BlockPos bett) {
        Vec3 treffer = new Vec3(bett.getX() + 0.5, bett.getY() + 0.5625, bett.getZ() + 0.5);
        mc.gameMode.useItemOn(p, InteractionHand.MAIN_HAND, new BlockHitResult(treffer, Direction.UP, bett, false));
        if (m.swing.get()) p.swing(InteractionHand.MAIN_HAND);
        anzeige = bett;
        anzeigeBis = tick + 6;
    }
}
