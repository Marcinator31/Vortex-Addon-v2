package com.vortex.client.cheat;

import com.vortex.client.module.ModuleManager;
import com.vortex.client.module.modules.MaceKillModule;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;

/**
 * Logik fuer Mace Kill (siehe MaceKillModule). Wird direkt vor jedem Angriff
 * aufgerufen (MaceKillMixin -> MultiPlayerGameMode.attack, HEAD).
 *
 * Server-Regeln (Vanilla 26.x, ServerGamePacketListenerImpl.handleMovePlayer):
 *  - "moved too quickly": Abstand zum Tick-Anfang^2 > 100 * Pakete-in-diesem-
 *    Tick (hoechstens 5). -> Hoehe h braucht ceil(h^2/100) Pakete bis zum
 *    Hoch-Paket; davor also Status-Pakete ohne Bewegung.
 *  - neue Kollision -> zurueckgesetzt. -> Die Saeule ueber dir muss frei sein.
 *  - Fallhoehe steigt nur beim Sinken (checkFallDamage: dy < 0) und wird erst
 *    am Boden zurueckgesetzt -> hoch (kein Abzug), runter (+h), dann Schlag.
 */
public final class MaceKill {

    private MaceKill() {}

    public static void vorDemSchlag(Player spieler, Entity ziel) {
        zurueck = -1;
        MaceKillModule m = ModuleManager.INSTANCE.get(MaceKillModule.class);
        if (m == null || !m.isEnabled()) return;
        Minecraft mc = Minecraft.getInstance();
        if (!(spieler instanceof LocalPlayer p) || p != mc.player || mc.level == null) return;
        ClientPacketListener net = mc.getConnection();
        if (net == null) return;
        if (!(ziel instanceof LivingEntity) || !ziel.isAlive()) return;
        if (m.playersOnly.get() && !(ziel instanceof Player)) return;

        // Keinen Streitkolben in der Hand? Einen aus der Hotbar nehmen -- der
        // Wechsel geht im selben Schlag mit (Minecraft meldet den Slot direkt
        // vor dem Angriffspaket).
        if (!p.getMainHandItem().is(Items.MACE) && m.autoMace.get()) {
            var inv = p.getInventory();
            for (int i = 0; i < 9; i++) {
                if (inv.getItem(i).is(Items.MACE)) { zurueck = inv.getSelectedSlot(); inv.setSelectedSlot(i); break; }
            }
        }
        if (!p.getMainHandItem().is(Items.MACE)) { info(mc, m, "no mace in hand", false); return; }
        if (p.isFallFlying()) { info(mc, m, "not while gliding", false); return; }
        if (p.isInWater() || p.isInLava()) { info(mc, m, "not in water/lava", false); return; }
        if (p.isPassenger()) { info(mc, m, "not while riding", false); return; }
        if (p.onClimbable()) { info(mc, m, "not on ladders/vines", false); return; }

        double h = freieHoehe(mc, p, m.height.get());
        if (h < 2.0) { info(mc, m, "no free space above you", false); return; }

        int noetig = (int) Math.ceil(h * h / 100.0);     // Pakete bis inkl. Hoch-Paket
        int status = Math.max(0, Math.min(4, noetig - 1));
        boolean hc = p.horizontalCollision;
        double x = p.getX(), y = p.getY(), z = p.getZ();

        for (int i = 0; i < status; i++) net.send(new ServerboundMovePlayerPacket.StatusOnly(false, hc));
        net.send(new ServerboundMovePlayerPacket.Pos(x, y + h, z, false, hc));
        net.send(new ServerboundMovePlayerPacket.Pos(x, y, z, false, hc));
        info(mc, m, (int) h + " blocks", true);
    }

    /** Slot vor "Auto Mace" (-1 = nicht gewechselt). */
    private static int zurueck = -1;

    /** Nach dem Schlag: wieder das vorige Item. true = Slot geaendert, sofort melden. */
    public static boolean nachDemSchlag(Player spieler) {
        if (zurueck < 0) return false;
        int alt = zurueck;
        zurueck = -1;
        if (!(spieler instanceof LocalPlayer p)) return false;
        p.getInventory().setSelectedSlot(alt);
        return true;
    }

    private static long letzteInfo = 0;

    /**
     * Kurze Rueckmeldung in der Aktionsleiste: WARUM Mace Kill nicht ausgeloest
     * hat -- oder mit welcher Hoehe. Ohne das sah "geht nicht" immer gleich aus,
     * egal ob der Streitkolben fehlte, eine Decke im Weg war oder der Server es
     * blockt (dann steht hier die Hoehe, aber der Schaden bleibt normal).
     */
    private static void info(Minecraft mc, MaceKillModule m, String text, boolean ok) {
        if (!m.showInfo.get() || mc.player == null) return;
        long jetzt = System.currentTimeMillis();
        if (!ok && jetzt - letzteInfo < 1500) return;
        letzteInfo = jetzt;
        mc.player.sendOverlayMessage(net.minecraft.network.chat.Component.literal(
                (ok ? "\u00a7dMace Kill \u00a7f" : "\u00a7dMace Kill \u00a77skipped: ") + text));
    }

    /** Groesste freie Hoehe ueber dem Spieler (in halben Bloecken geprueft). */
    private static double freieHoehe(Minecraft mc, LocalPlayer p, double wunsch) {
        AABB box = p.getBoundingBox();
        double frei = 0;
        for (double dy = 0.5; dy <= wunsch + 1e-6; dy += 0.5) {
            if (!mc.level.noCollision(p, box.move(0, dy, 0))) break;
            frei = dy;
        }
        return Math.floor(Math.min(frei, 22.0));
    }
}
