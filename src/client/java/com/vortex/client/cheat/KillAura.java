package com.vortex.client.cheat;

import com.vortex.client.module.ModuleManager;
import com.vortex.client.module.modules.KillAuraModule;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Logik fuer Kill Aura (siehe KillAuraModule).
 *
 * Pro Tick: naechstes gueltiges Ziel in Reichweite suchen -> bei Bedarf den
 * Streitkolben nehmen -> sobald der Schlag aufgeladen ist, angreifen.
 * Angegriffen wird ueber MultiPlayerGameMode.attack -- derselbe Weg wie ein
 * echter Klick. Dadurch greift auch Mace Kill (haengt genau dort dran).
 */
public final class KillAura {

    private KillAura() {}

    /** Platz vor dem Wechsel zum Streitkolben (-1 = nicht gewechselt). */
    private static int vorherPlatz = -1;
    /** Ticks ohne Ziel -- erst nach einer kurzen Pause zurueckwechseln. */
    private static int ohneZiel = 0;

    public static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            try { tick(mc); } catch (Throwable e) { com.vortex.client.core.Errors.report("KillAura", e); }
        });
    }

    private static void tick(Minecraft mc) {
        KillAuraModule m = ModuleManager.INSTANCE.get(KillAuraModule.class);
        LocalPlayer p = mc.player;
        if (m == null || !m.isEnabled() || p == null || mc.level == null || mc.gameMode == null) return;
        if (p.isSpectator() || !p.isAlive()) return;
        if (mc.gui.screen() != null) return;          // nicht in Menues
        if (p.isUsingItem()) return;                   // Essen/Blocken nicht unterbrechen

        LivingEntity ziel = findeZiel(mc, p, m);
        if (ziel == null) {
            if (++ohneZiel > 20) slotZurueck();
            return;
        }
        ohneZiel = 0;

        if (m.autoMace.get()) nimmStreitkolben(p);

        if (p.getAttackStrengthScale(0.0f) < m.minCharge.get()) return;
        mc.gameMode.attack(p, ziel);
        p.swing(InteractionHand.MAIN_HAND);
    }

    private static LivingEntity findeZiel(Minecraft mc, LocalPlayer p, KillAuraModule m) {
        double weite = m.range.get();
        Vec3 auge = p.getEyePosition();
        LivingEntity best = null;
        double bestD = Double.MAX_VALUE;
        for (Entity e : mc.level.entitiesForRendering()) {
            if (e == p || !(e instanceof LivingEntity le) || !le.isAlive() || le.isDeadOrDying()) continue;
            if (e instanceof ArmorStand) continue;
            if (m.playersOnly.get() && !(e instanceof Player)) continue;
            if (e instanceof Player pl && (pl.isCreative() || pl.isSpectator())) continue;
            if (com.vortex.client.core.Friends.schuetzt(e)) continue;
            if (e == p.getVehicle()) continue;
            double d = abstand(auge, e.getBoundingBox());
            if (d > weite || d >= bestD) continue;
            if (!m.throughWalls.get() && !sichtbar(mc, p, auge, e)) continue;
            best = le;
            bestD = d;
        }
        return best;
    }

    /** Abstand Augen -> naechster Punkt der Hitbox (so misst auch der Server). */
    private static double abstand(Vec3 auge, AABB box) {
        double x = Math.max(box.minX, Math.min(auge.x, box.maxX));
        double y = Math.max(box.minY, Math.min(auge.y, box.maxY));
        double z = Math.max(box.minZ, Math.min(auge.z, box.maxZ));
        return auge.distanceTo(new Vec3(x, y, z));
    }

    private static boolean sichtbar(Minecraft mc, LocalPlayer p, Vec3 auge, Entity e) {
        Vec3[] punkte = { e.getEyePosition(), e.getBoundingBox().getCenter(), e.position().add(0, 0.2, 0) };
        for (Vec3 z : punkte) {
            HitResult h = mc.level.clip(new ClipContext(auge, z, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, p));
            if (h.getType() == HitResult.Type.MISS) return true;
        }
        return false;
    }

    private static void nimmStreitkolben(LocalPlayer p) {
        var inv = p.getInventory();
        if (p.getMainHandItem().is(Items.MACE)) return;
        for (int i = 0; i < 9; i++) {
            if (inv.getItem(i).is(Items.MACE)) {
                if (vorherPlatz < 0) vorherPlatz = inv.getSelectedSlot();
                inv.setSelectedSlot(i);
                return;
            }
        }
    }

    /** Nach dem Kampf wieder das vorige Item nehmen. */
    public static void slotZurueck() {
        LocalPlayer p = Minecraft.getInstance().player;
        if (vorherPlatz >= 0 && p != null) p.getInventory().setSelectedSlot(vorherPlatz);
        vorherPlatz = -1;
        ohneZiel = 0;
    }
}
