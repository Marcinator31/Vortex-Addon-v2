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
        MaceKillModule m = ModuleManager.INSTANCE.get(MaceKillModule.class);
        if (m == null || !m.isEnabled()) return;
        Minecraft mc = Minecraft.getInstance();
        if (!(spieler instanceof LocalPlayer p) || p != mc.player || mc.level == null) return;
        ClientPacketListener net = mc.getConnection();
        if (net == null) return;
        if (!p.getMainHandItem().is(Items.MACE)) return;
        if (!(ziel instanceof LivingEntity) || !ziel.isAlive()) return;
        if (m.playersOnly.get() && !(ziel instanceof Player)) return;
        if (p.isFallFlying() || p.isInWater() || p.isInLava() || p.isPassenger() || p.onClimbable()) return;

        double h = freieHoehe(mc, p, m.height.get());
        if (h < 2.0) return;                              // Smash braucht > 1.5 Bloecke

        int noetig = (int) Math.ceil(h * h / 100.0);     // Pakete bis inkl. Hoch-Paket
        int status = Math.max(0, Math.min(4, noetig - 1));
        boolean hc = p.horizontalCollision;
        double x = p.getX(), y = p.getY(), z = p.getZ();

        for (int i = 0; i < status; i++) net.send(new ServerboundMovePlayerPacket.StatusOnly(false, hc));
        net.send(new ServerboundMovePlayerPacket.Pos(x, y + h, z, false, hc));
        net.send(new ServerboundMovePlayerPacket.Pos(x, y, z, false, hc));
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
