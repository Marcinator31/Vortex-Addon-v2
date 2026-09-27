package com.vortex.client.cheat;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.Difficulty;
import net.minecraft.world.damagesource.CombatRules;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.Map;

/**
 * Explosionsschaden vorausberechnen -- so, wie der Server ihn rechnet.
 *
 * GEPRUEFT an 26.x (ExplosionDamageCalculator, ServerExplosion, DamageTypes):
 *   abstand = |Mitte - Wesen| / (Staerke * 2)          (nur wenn <= 1)
 *   wirkung = (1 - abstand) * sichtbarAnteil
 *   schaden = (wirkung^2 + wirkung) / 2 * 7 * (Staerke * 2) + 1
 *   Spieler: Schwierigkeit (Leicht: min(s/2+1, s), Schwer: s*1.5) -- alle
 *            Explosionsarten skalieren IMMER (DamageScaling.ALWAYS)
 *   dann Ruestung + Haerte, Resistenz, Schutz/Explosionsschutz (max. 20 Punkte)
 *
 * Der Schaden an Wesen wird VOR dem Zerstoeren der Bloecke berechnet
 * (ServerExplosion.explode: hurtEntities vor interactWithBlocks). Ein Block
 * zwischen dir und der Explosion schuetzt also -- darauf baut der
 * Glowstone-Schild von Fast Anchor.
 *
 * Kristall: Staerke 6, Mitte = Kristallposition (Blockoberkante + 0.5).
 * Seelenanker: Staerke 5, Mitte = Blockmitte; der Anker selbst wird VORHER
 * entfernt (RespawnAnchorBlock.explode) -- deshalb "ersetze" mit Luft.
 */
public final class Sprengung {

    private Sprengung() {}

    public static final float KRISTALL = 6.0f;
    public static final float ANKER = 5.0f;

    /**
     * @param ersetze Bloecke, die fuer die Rechnung anders sein sollen als in
     *                der Welt (Anker -> Luft, geplanter Schild -> Glowstone).
     *                Darf null sein.
     * @param vorhersage Ticks, um die das Wesen seiner Bewegung nach
     *                   verschoben wird (0 = wo es jetzt ist).
     */
    public static float schaden(LivingEntity e, Vec3 mitte, float staerke,
                                Map<BlockPos, BlockState> ersetze, int vorhersage) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || e == null) return 0f;
        AABB box = e.getBoundingBox();
        if (vorhersage > 0) {
            Vec3 v = e.getDeltaMovement();
            box = box.move(v.x * vorhersage, 0, v.z * vorhersage);
        }
        Vec3 fuss = new Vec3((box.minX + box.maxX) / 2, box.minY, (box.minZ + box.maxZ) / 2);
        double durchmesser = staerke * 2.0;
        double abstand = Math.sqrt(fuss.distanceToSqr(mitte)) / durchmesser;
        if (abstand > 1.0) return 0f;
        double sicht = sichtbar(mc, mitte, box, e, ersetze);
        double wirkung = (1.0 - abstand) * sicht;
        float s = (float) ((wirkung * wirkung + wirkung) / 2.0 * 7.0 * durchmesser + 1.0);
        if (wirkung <= 0) return 0f;

        if (e instanceof Player) {
            Difficulty d = mc.level.getDifficulty();
            if (d == Difficulty.PEACEFUL) s = 0f;
            else if (d == Difficulty.EASY) s = Math.min(s / 2f + 1f, s);
            else if (d == Difficulty.HARD) s = s * 1.5f;
        }
        // Ruestung und Haerte
        float ruestung = e.getArmorValue();
        float haerte = (float) e.getAttributeValue(Attributes.ARMOR_TOUGHNESS);
        s = CombatRules.getDamageAfterAbsorb(e, s, mc.level.damageSources().explosion(null, null), ruestung, haerte);
        // Resistenz
        MobEffectInstance res = e.getEffect(MobEffects.RESISTANCE);
        if (res != null) {
            int stufe = res.getAmplifier() + 1;
            s = Math.max(s * (25 - stufe * 5) / 25f, 0f);
        }
        // Schutz-Verzauberungen: Schutz 1 je Stufe, Explosionsschutz 2 je Stufe
        int punkte = 0;
        for (EquipmentSlot slot : new EquipmentSlot[]{EquipmentSlot.HEAD, EquipmentSlot.CHEST,
                EquipmentSlot.LEGS, EquipmentSlot.FEET}) {
            ItemStack st = e.getItemBySlot(slot);
            if (st.isEmpty()) continue;
            for (var en : st.getEnchantments().entrySet()) {
                if (en.getKey().is(Enchantments.PROTECTION)) punkte += en.getIntValue();
                else if (en.getKey().is(Enchantments.BLAST_PROTECTION)) punkte += 2 * en.getIntValue();
            }
        }
        if (punkte > 0) s = CombatRules.getDamageAfterMagicAbsorb(s, punkte);
        return Math.max(s, 0f);
    }

    /** Leben + Absorption. */
    public static float leben(LivingEntity e) {
        return e.getHealth() + e.getAbsorptionAmount();
    }

    /** Wie ServerExplosion.getSeenPercent -- mit ersetzbaren Bloecken. */
    private static double sichtbar(Minecraft mc, Vec3 mitte, AABB box, LivingEntity e,
                                   Map<BlockPos, BlockState> ersetze) {
        double sx = 1.0 / ((box.maxX - box.minX) * 2.0 + 1.0);
        double sy = 1.0 / ((box.maxY - box.minY) * 2.0 + 1.0);
        double sz = 1.0 / ((box.maxZ - box.minZ) * 2.0 + 1.0);
        double ox = (1.0 - Math.floor(1.0 / sx) * sx) / 2.0;
        double oz = (1.0 - Math.floor(1.0 / sz) * sz) / 2.0;
        if (sx < 0 || sy < 0 || sz < 0) return 0;
        BlockGetter welt = (ersetze == null || ersetze.isEmpty()) ? mc.level : new Ersatz(mc, ersetze);
        int frei = 0, alle = 0;
        for (double a = 0; a <= 1; a += sx) {
            for (double b = 0; b <= 1; b += sy) {
                for (double c = 0; c <= 1; c += sz) {
                    Vec3 punkt = new Vec3(Mth.lerp(a, box.minX, box.maxX) + ox,
                            Mth.lerp(b, box.minY, box.maxY),
                            Mth.lerp(c, box.minZ, box.maxZ) + oz);
                    HitResult hr = welt.clip(new ClipContext(punkt, mitte,
                            ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, e));
                    if (hr.getType() == HitResult.Type.MISS) frei++;
                    alle++;
                }
            }
        }
        return alle == 0 ? 0 : (double) frei / alle;
    }

    /** Die Welt, mit ein paar Bloecken anders. */
    private record Ersatz(Minecraft mc, Map<BlockPos, BlockState> ersetze) implements BlockGetter {
        @Override public BlockEntity getBlockEntity(BlockPos pos) { return mc.level.getBlockEntity(pos); }
        @Override public BlockState getBlockState(BlockPos pos) {
            BlockState st = ersetze.get(pos);
            return st != null ? st : mc.level.getBlockState(pos);
        }
        @Override public FluidState getFluidState(BlockPos pos) {
            BlockState st = ersetze.get(pos);
            return st != null ? st.getFluidState() : mc.level.getFluidState(pos);
        }
        @Override public int getHeight() { return mc.level.getHeight(); }
        @Override public int getMinY() { return mc.level.getMinY(); }
    }
}
