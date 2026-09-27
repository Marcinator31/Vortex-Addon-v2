package com.vortex.client.cheat;

import com.mojang.blaze3d.vertex.PoseStack;
import com.vortex.client.hud.EspRender;
import com.vortex.client.module.ModuleManager;
import com.vortex.client.module.modules.CrystalAuraModule;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientEntityEvents;
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
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Logik der Crystal Aura (siehe CrystalAuraModule).
 *
 * Je Tick:
 *   1. Pausen pruefen (Essen, Abbauen, wenig Leben)
 *   2. Ziele sammeln (keine Freunde), nach Prioritaet sortieren
 *   3. SPRENGEN: bester Kristall in Reichweite, der dem Ziel genug schadet
 *      und dir nicht zu viel
 *   4. SETZEN: bester Obsidian-/Grundgestein-Block
 *
 * Instant Break: zusaetzlich, sobald der Server einen Kristall meldet
 * (ClientEntityEvents.ENTITY_LOAD), sofort pruefen und sprengen.
 *
 * Alle Schadenswerte kommen aus Sprengung -- dieselbe Formel wie der Server.
 */
public final class CrystalAura {

    private CrystalAura() {}

    private static long tick = 0;
    private static long setzZuletzt = -100;
    private static long sprengZuletzt = -100;
    /** Kristall-ID -> Tick des letzten Schlags (Inhibit). */
    private static final Map<Integer, Long> GESCHLAGEN = new HashMap<>();
    /** Wo wir zuletzt gesetzt haben (Kristall-Block) -> Tick. */
    private static final Map<BlockPos, Long> EIGENE = new HashMap<>();
    private static BlockPos anzeige = null;
    private static long anzeigeBis = 0;
    private static List<LivingEntity> ziele = new ArrayList<>();

    public static void aus() {
        GESCHLAGEN.clear();
        EIGENE.clear();
        anzeige = null;
        ziele = new ArrayList<>();
    }

    public static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            tick++;
            try { tick(mc); } catch (Throwable e) { com.vortex.client.core.Errors.report("CrystalAura", e); }
        });
        ClientEntityEvents.ENTITY_LOAD.register((entity, world) -> {
            try {
                if (entity instanceof EndCrystal k) sofort(k);
            } catch (Throwable e) {
                com.vortex.client.core.Errors.report("CrystalAura.instant", e);
            }
        });
        LevelRenderEvents.AFTER_TRANSLUCENT_FEATURES.register(context -> {
            try {
                CrystalAuraModule m = modul();
                if (m == null || !m.render.get() || anzeige == null || tick > anzeigeBis) return;
                Minecraft mc = Minecraft.getInstance();
                if (mc.level == null) return;
                PoseStack ms = context.poseStack();
                SubmitNodeCollector col = context.submitNodeCollector();
                if (ms == null || col == null) return;
                float td = mc.getDeltaTracker().getGameTimeDeltaPartialTick(false);
                int f = m.color.get();
                if ((f >>> 24) == 0) f |= 0xFF000000;
                EspRender.submitBox(col, ms, new AABB(anzeige), EspRender.cameraOffset(mc, td), f, 2.0f);
            } catch (Throwable e) {
                com.vortex.client.core.Errors.report("CrystalAura.render", e);
            }
        });
    }

    private static CrystalAuraModule modul() {
        CrystalAuraModule m = ModuleManager.INSTANCE.get(CrystalAuraModule.class);
        return (m != null && m.isEnabled()) ? m : null;
    }

    private static boolean pause(Minecraft mc, LocalPlayer p, CrystalAuraModule m) {
        if (mc.gui.screen() != null) return true;
        if (m.pauseEating.get() && p.isUsingItem()) return true;
        if (m.pauseMining.get() && mc.gameMode.isDestroying()) return true;
        return m.pauseHealth.getInt() > 0 && Sprengung.leben(p) <= m.pauseHealth.get();
    }

    private static void tick(Minecraft mc) {
        CrystalAuraModule m = modul();
        LocalPlayer p = mc.player;
        if (m == null || p == null || mc.level == null || mc.gameMode == null) return;
        GESCHLAGEN.values().removeIf(t -> tick - t > 40);
        EIGENE.values().removeIf(t -> tick - t > 60);
        if (pause(mc, p, m)) return;

        ziele = sammleZiele(mc, p, m);
        if (ziele.isEmpty()) return;

        if (m.breakIt.get() && tick - sprengZuletzt >= m.breakDelay.getInt()) {
            EndCrystal k = besterKristall(mc, p, m);
            if (k != null) schlage(mc, p, m, k);
        }
        if (m.place.get() && tick - setzZuletzt >= m.placeDelay.getInt()) {
            setze(mc, p, m);
        }
    }

    // ----------------------------------------------------------------------
    // Ziele
    // ----------------------------------------------------------------------

    private static List<LivingEntity> sammleZiele(Minecraft mc, LocalPlayer p, CrystalAuraModule m) {
        double r = m.targetRange.get();
        List<LivingEntity> liste = new ArrayList<>();
        Iterable<? extends LivingEntity> alle = m.targets.getIndex() == 0
                ? mc.level.players()
                : mc.level.getEntitiesOfClass(LivingEntity.class, p.getBoundingBox().inflate(r));
        for (LivingEntity e : alle) {
            if (e == p || !e.isAlive() || e.isSpectator()) continue;
            if (e instanceof Player pl && (pl.isCreative())) continue;
            if (com.vortex.client.core.Friends.schuetzt(e)) continue;
            if (e.distanceToSqr(p) > r * r) continue;
            liste.add(e);
        }
        Comparator<LivingEntity> nahe = Comparator.comparingDouble(e -> e.distanceToSqr(p));
        if (m.priority.getIndex() == 1) liste.sort(Comparator.comparingDouble(Sprengung::leben).thenComparing(nahe));
        else liste.sort(nahe);
        // "Most Damage": die drei naechsten pruefen, der Schaden entscheidet.
        int n = m.priority.getIndex() == 2 ? 3 : 1;
        return liste.size() > n ? new ArrayList<>(liste.subList(0, n)) : liste;
    }

    /** Wie viel muss ein Kristall diesem Ziel mindestens antun? */
    private static double noetig(LivingEntity z, CrystalAuraModule m) {
        if (Sprengung.leben(z) <= m.facePlaceHealth.get()) return m.facePlaceDamage.get();
        if (m.facePlaceArmor.getInt() > 0 && ruestungProzent(z) <= m.facePlaceArmor.get()) return m.facePlaceDamage.get();
        return m.minDamage.get();
    }

    /** Haltbarkeit des schwaechsten Ruestungsteils in Prozent (100 = keine Ruestung zaehlt nicht). */
    private static double ruestungProzent(LivingEntity z) {
        double min = 100;
        for (EquipmentSlot s : new EquipmentSlot[]{EquipmentSlot.HEAD, EquipmentSlot.CHEST,
                EquipmentSlot.LEGS, EquipmentSlot.FEET}) {
            ItemStack st = z.getItemBySlot(s);
            if (st.isEmpty() || !st.isDamageableItem() || st.getMaxDamage() <= 0) continue;
            min = Math.min(min, 100.0 * (st.getMaxDamage() - st.getDamageValue()) / st.getMaxDamage());
        }
        return min;
    }

    /** Bester Schaden an einem der Ziele, das genug abbekommt; sonst -1. */
    private static float zielSchaden(Vec3 mitte, CrystalAuraModule m, boolean mussReichen) {
        float best = -1;
        for (LivingEntity z : ziele) {
            if (z.position().distanceToSqr(mitte) > 13 * 13) continue;
            float d = Sprengung.schaden(z, mitte, Sprengung.KRISTALL, null, m.predict.getInt());
            if (mussReichen && d < noetig(z, m)) continue;
            if (d > best) best = d;
        }
        return best;
    }

    /** Ist die Explosion fuer mich ertraeglich? */
    private static boolean sicher(LocalPlayer p, Vec3 mitte, CrystalAuraModule m, float[] selbst) {
        float d = Sprengung.schaden(p, mitte, Sprengung.KRISTALL, null, 0);
        selbst[0] = d;
        if (d > m.maxSelfDamage.get()) return false;
        return !(m.antiSuicide.get() && d >= Sprengung.leben(p) - 1.0f);
    }

    // ----------------------------------------------------------------------
    // Sprengen
    // ----------------------------------------------------------------------

    private static EndCrystal besterKristall(Minecraft mc, LocalPlayer p, CrystalAuraModule m) {
        double r = m.breakRange.get();
        EndCrystal best = null;
        float bestWert = -1;
        float[] selbst = new float[1];
        for (EndCrystal k : mc.level.getEntitiesOfClass(EndCrystal.class, p.getBoundingBox().inflate(r + 1))) {
            if (!k.isAlive()) continue;
            if (p.getEyePosition().distanceToSqr(k.position().add(0, 1, 0)) > r * r) continue;
            Long zuletzt = GESCHLAGEN.get(k.getId());
            if (zuletzt != null && tick - zuletzt < m.inhibit.getInt()) continue;
            float wert = kristallWert(p, m, k, selbst);
            if (wert > bestWert) { bestWert = wert; best = k; }
        }
        return bestWert >= 0 ? best : null;
    }

    /** Wert eines Kristalls zum Sprengen; -1 = nicht sprengen. */
    private static float kristallWert(LocalPlayer p, CrystalAuraModule m, EndCrystal k, float[] selbst) {
        Vec3 mitte = k.position();
        if (!sicher(p, mitte, m, selbst)) return -1;
        boolean eigen = EIGENE.containsKey(k.blockPosition().below()) || EIGENE.containsKey(k.blockPosition());
        switch (m.breakMode.getIndex()) {
            case 1: // nur eigene
                return eigen ? Math.max(0, zielSchaden(mitte, m, false)) : -1;
            case 2: // alle
                return Math.max(0, zielSchaden(mitte, m, false));
            default: { // klug: eigene oder solche, die dem Ziel genug schaden
                float d = zielSchaden(mitte, m, true);
                if (d >= 0) return d;
                return eigen ? 0 : -1;
            }
        }
    }

    private static void schlage(Minecraft mc, LocalPlayer p, CrystalAuraModule m, EndCrystal k) {
        int vorher = p.getInventory().getSelectedSlot();
        boolean gewechselt = false;
        // Schwaeche: mit der Hand 0 Schaden -- der Kristall bricht nicht.
        if (m.antiWeakness.get() && p.hasEffect(MobEffects.WEAKNESS) && !p.hasEffect(MobEffects.STRENGTH)) {
            int waffe = Inv.hotbar(p, st -> st.is(ItemTags.SWORDS) || st.is(ItemTags.AXES));
            if (waffe >= 0 && waffe != vorher) { p.getInventory().setSelectedSlot(waffe); gewechselt = true; }
        }
        if (m.rotate.get()) blicke(mc, p, k.getBoundingBox().getCenter());
        mc.gameMode.attack(p, k);
        if (m.swing.get()) p.swing(InteractionHand.MAIN_HAND);
        if (gewechselt) p.getInventory().setSelectedSlot(vorher);
        GESCHLAGEN.put(k.getId(), tick);
        sprengZuletzt = tick;
    }

    /** Instant Break: der Server hat gerade einen Kristall gemeldet. */
    private static void sofort(EndCrystal k) {
        CrystalAuraModule m = modul();
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        if (m == null || !m.instantBreak.get() || !m.breakIt.get() || p == null || mc.gameMode == null) return;
        if (ziele.isEmpty() || pause(mc, p, m)) return;
        double r = m.breakRange.get();
        if (p.getEyePosition().distanceToSqr(k.position().add(0, 1, 0)) > r * r) return;
        if (kristallWert(p, m, k, new float[1]) < 0) return;
        schlage(mc, p, m, k);
    }

    // ----------------------------------------------------------------------
    // Setzen
    // ----------------------------------------------------------------------

    private static void setze(Minecraft mc, LocalPlayer p, CrystalAuraModule m) {
        boolean links = p.getOffhandItem().is(Items.END_CRYSTAL);
        int slot = links ? -1 : Inv.hotbar(p, st -> st.is(Items.END_CRYSTAL));
        if (!links && slot < 0) return;
        int vorher = p.getInventory().getSelectedSlot();
        if (!links && m.switchMode.getIndex() == 0 && slot != vorher) return;   // None: nur wenn schon in der Hand

        BlockPos best = null;
        float bestWert = -Float.MAX_VALUE;
        float[] selbst = new float[1];
        double r = m.placeRange.get();
        double wand = m.wallRange.get();
        Vec3 auge = p.getEyePosition();
        BlockPos mitte = p.blockPosition();
        int ri = (int) Math.ceil(r);
        for (int dx = -ri; dx <= ri; dx++) {
            for (int dy = -ri; dy <= ri; dy++) {
                for (int dz = -ri; dz <= ri; dz++) {
                    BlockPos b = mitte.offset(dx, dy, dz);
                    BlockState st = mc.level.getBlockState(b);
                    if (!st.is(Blocks.OBSIDIAN) && !st.is(Blocks.BEDROCK)) continue;
                    BlockPos oben = b.above();
                    if (!mc.level.getBlockState(oben).isAir()) continue;
                    Vec3 flaeche = new Vec3(b.getX() + 0.5, b.getY() + 1.0, b.getZ() + 0.5);
                    double d2 = auge.distanceToSqr(flaeche);
                    if (d2 > r * r) continue;
                    if (d2 > wand * wand && !sichtbar(mc, p, auge, flaeche, b)) continue;
                    // Kein Wesen im Platz des Kristalls (so prueft EndCrystalItem)
                    AABB platz = new AABB(oben.getX(), oben.getY(), oben.getZ(),
                            oben.getX() + 1, oben.getY() + 2, oben.getZ() + 1);
                    if (!mc.level.getEntities(null, platz).isEmpty()) continue;

                    Vec3 knall = flaeche;   // Kristall steht auf der Oberkante
                    float ziel = zielSchaden(knall, m, true);
                    if (ziel < 0) continue;
                    if (!sicher(p, knall, m, selbst)) continue;
                    float wert = ziel - selbst[0] * 0.5f;
                    if (wert > bestWert) { bestWert = wert; best = b; }
                }
            }
        }
        if (best == null) return;

        InteractionHand hand = links ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND;
        if (!links && slot != vorher) p.getInventory().setSelectedSlot(slot);
        Vec3 treffer = new Vec3(best.getX() + 0.5, best.getY() + 1.0, best.getZ() + 0.5);
        if (m.rotate.get()) blicke(mc, p, treffer);
        mc.gameMode.useItemOn(p, hand, new BlockHitResult(treffer, Direction.UP, best, false));
        if (m.swing.get()) p.swing(hand);
        if (!links && m.switchMode.getIndex() == 2 && slot != vorher) p.getInventory().setSelectedSlot(vorher);

        EIGENE.put(best.above(), tick);
        anzeige = best;
        anzeigeBis = tick + 10;
        setzZuletzt = tick;
    }

    /** Sieht das Auge die Oberseite des Blocks (keine Wand dazwischen)? */
    private static boolean sichtbar(Minecraft mc, LocalPlayer p, Vec3 auge, Vec3 ziel, BlockPos b) {
        BlockHitResult hr = mc.level.clip(new ClipContext(auge, ziel.add(0, -0.05, 0),
                ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, p));
        return hr.getType() == HitResult.Type.MISS || hr.getBlockPos().equals(b);
    }

    /** Blickrichtung nur an den Server melden -- die Kamera bleibt. */
    private static void blicke(Minecraft mc, LocalPlayer p, Vec3 ziel) {
        var net = mc.getConnection();
        if (net == null) return;
        float[] r = Inv.blickZu(p, ziel);
        net.send(new ServerboundMovePlayerPacket.Rot(r[0], r[1], p.onGround(), p.horizontalCollision));
    }
}
