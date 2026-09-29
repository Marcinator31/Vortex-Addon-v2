package com.vortex.client.bot;

import com.vortex.client.cheat.Inv;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.NeutralMob;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Sicherheit der Farm-Bots: wehrt Monster ab, haelt bei fremden Spielern an
 * und stoppt, wenn das Leben knapp wird.
 *
 * Nachts laufen Zombies und Skelette ueber jede offene Farm. Bis 2.33 hat der
 * Bot einfach weitergeerntet, bis er tot war -- mit dem ganzen Inventar.
 *
 *   KAMPF  Monster (keine neutralen wie Enderman/Zombie-Piglin) in Schlag-
 *          weite und Sichtlinie: bestes Schwert (sonst Axt) nehmen, sobald der
 *          Schlag aufgeladen ist zuschlagen. Danach die alte Hand zurueck.
 *   PAUSE  fremder Spieler (kein Freund) in der Naehe -- der Bot steht still.
 *   STOP   Leben auf oder unter der Grenze: Bot aus, Meldung.
 */
final class BotSchutz {

    enum Lage { OK, KAMPF, PAUSE, STOP }

    private final String name;
    private int vorherPlatz = -1;
    private long kampfZuletzt = -1000;
    private String pauseGrund = null;
    /** Ziele, die nicht zu besiegen sind (Creaking, geschuetzte Region ...) -> ignoriert bis Tick. */
    private final java.util.Map<Integer, Long> ignoriert = new java.util.HashMap<>();
    private int zielId = -1, schlaege = 0;
    private float zielLeben = -1;
    private boolean pauseGemeldet = false;

    BotSchutz(String name) { this.name = name; }

    String pauseGrund() { return pauseGrund; }

    void zuruecksetzen(LocalPlayer p) {
        handZurueck(p);
        pauseGrund = null;
        pauseGemeldet = false;
        ignoriert.clear();
        zielId = -1;
    }

    /**
     * @param stopLeben   0 = aus; sonst Lebenspunkte (2 = ein Herz)
     * @param spielerWeite 0 = aus; sonst Abstand in Bloecken
     */
    Lage tick(Minecraft mc, LocalPlayer p, BotMotor motor, long tick, boolean verteidigen, int stopLeben, int spielerWeite) {
        if (stopLeben > 0 && p.getHealth() <= stopLeben && !p.isCreative()) {
            p.sendSystemMessage(Component.literal("§d[" + name + "]§r §cHealth low (" + Math.round(p.getHealth())
                    + ") -- stopped to keep you alive."));
            handZurueck(p);
            return Lage.STOP;
        }

        if (verteidigen) {
            ignoriert.values().removeIf(bis -> bis < tick);
            LivingEntity feind = feind(mc, p, ignoriert.keySet());
            if (feind != null) {
                motor.anhalten(mc);
                kampfZuletzt = tick;
                waffe(p);
                motor.blicke(p, feind.getBoundingBox().getCenter());
                if (p.getAttackStrengthScale(0.0f) >= 0.9f) {
                    // Nach 6 vollen Schlaegen ohne Wirkung: Ziel eine Minute ignorieren
                    if (feind.getId() != zielId) { zielId = feind.getId(); schlaege = 0; zielLeben = feind.getHealth(); }
                    else if (feind.getHealth() < zielLeben) { zielLeben = feind.getHealth(); schlaege = 0; }
                    if (++schlaege > 6) {
                        ignoriert.put(feind.getId(), tick + 1200);
                        zielId = -1;
                        return Lage.OK;
                    }
                    mc.gameMode.attack(p, feind);
                    p.swing(InteractionHand.MAIN_HAND);
                }
                return Lage.KAMPF;
            }
            // Eine Sekunde nach dem Kampf die alte Hand zurueck
            if (vorherPlatz >= 0 && tick - kampfZuletzt > 20) handZurueck(p);
        }

        if (spielerWeite > 0) {
            Player fremd = fremderSpieler(mc, p, spielerWeite);
            if (fremd != null) {
                motor.anhalten(mc);
                pauseGrund = fremd.getName().getString();
                if (!pauseGemeldet) {
                    pauseGemeldet = true;
                    p.sendSystemMessage(Component.literal("§d[" + name + "]§r Paused: " + pauseGrund + " is nearby."));
                }
                return Lage.PAUSE;
            }
            if (pauseGemeldet) {
                p.sendSystemMessage(Component.literal("§d[" + name + "]§r Continuing -- nobody nearby."));
            }
            pauseGemeldet = false;
            pauseGrund = null;
        }
        return Lage.OK;
    }

    /** Naechstes feindliches Monster in Schlagweite mit freier Sicht, sonst null. */
    private static LivingEntity feind(Minecraft mc, LocalPlayer p, java.util.Set<Integer> ignoriert) {
        double weite = Math.min(3.0, p.entityInteractionRange());
        Vec3 auge = p.getEyePosition();
        LivingEntity best = null;
        double bestD = Double.MAX_VALUE;
        for (Entity e : mc.level.getEntities(p, p.getBoundingBox().inflate(weite + 1))) {
            if (!(e instanceof Enemy) || !(e instanceof LivingEntity le) || !le.isAlive() || le.isDeadOrDying()) continue;
            if (e instanceof NeutralMob) continue;                 // Enderman, Zombie-Piglin: nicht reizen
            // Piglins (ein Schlag macht die ganze Gruppe wuetend), Creaking (unverwundbar),
            // Warden (hoffnungslos) -- nicht angreifen.
            if (e instanceof net.minecraft.world.entity.monster.piglin.AbstractPiglin
                    || e instanceof net.minecraft.world.entity.monster.creaking.Creaking
                    || e instanceof net.minecraft.world.entity.monster.warden.Warden) continue;
            // Spinnen sind tagsueber friedlich: nur, wenn sie schon angreifen (Spieler gerade getroffen).
            if (e instanceof net.minecraft.world.entity.monster.spider.Spider && p.hurtTime == 0) continue;
            if (ignoriert.contains(e.getId())) continue;
            double d = abstand(auge, e.getBoundingBox());
            if (d > weite || d >= bestD) continue;
            HitResult hr = mc.level.clip(new ClipContext(auge, e.getBoundingBox().getCenter(),
                    ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, p));
            if (hr.getType() == HitResult.Type.BLOCK) continue;    // Wand dazwischen
            best = le;
            bestD = d;
        }
        return best;
    }

    private static double abstand(Vec3 v, AABB b) {
        double x = Math.max(b.minX - v.x, Math.max(0, v.x - b.maxX));
        double y = Math.max(b.minY - v.y, Math.max(0, v.y - b.maxY));
        double z = Math.max(b.minZ - v.z, Math.max(0, v.z - b.maxZ));
        return Math.sqrt(x * x + y * y + z * z);
    }

    private static Player fremderSpieler(Minecraft mc, LocalPlayer p, int weite) {
        for (Player o : mc.level.players()) {
            if (o == p || o.isSpectator()) continue;
            if (com.vortex.client.core.Friends.istFreund(o)) continue;
            if (o.distanceToSqr(p) <= (double) weite * weite) return o;
        }
        return null;
    }

    /** Schwert (sonst Axt) in die Hand; der vorherige Platz wird gemerkt. */
    private void waffe(LocalPlayer p) {
        int platz = Inv.hotbar(p, st -> st.is(ItemTags.SWORDS) && heil(st));
        if (platz < 0) platz = Inv.hotbar(p, st -> st.is(ItemTags.AXES) && heil(st));
        if (platz < 0 || platz == p.getInventory().getSelectedSlot()) return;
        if (vorherPlatz < 0) vorherPlatz = p.getInventory().getSelectedSlot();
        p.getInventory().setSelectedSlot(platz);
    }

    private static boolean heil(ItemStack st) {
        return !st.isDamageableItem() || st.getMaxDamage() - st.getDamageValue() > 3;
    }

    private void handZurueck(LocalPlayer p) {
        if (vorherPlatz >= 0 && p != null) p.getInventory().setSelectedSlot(vorherPlatz);
        vorherPlatz = -1;
    }
}
