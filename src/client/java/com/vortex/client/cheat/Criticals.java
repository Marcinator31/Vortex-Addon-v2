package com.vortex.client.cheat;

import com.vortex.client.module.ModuleManager;
import com.vortex.client.module.modules.CriticalsModule;
import com.vortex.client.module.modules.MaceKillModule;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Items;

/**
 * Logik fuer Criticals (siehe CriticalsModule). Vor und nach jedem Angriff
 * aufgerufen (MaceKillMixin -> MultiPlayerGameMode.attack HEAD/TAIL).
 *
 * Server-Regeln (Player.canCriticalAttack, 26.x): fallDistance > 0, nicht am
 * Boden, nicht an Leiter/Ranke, nicht im Wasser, nicht festgehalten, nicht im
 * Fahrzeug, nicht sprintend -- und der Schlag muss fast voll geladen sein.
 */
public final class Criticals {

    private Criticals() {}

    private static boolean sprintZurueck = false;

    public static void vorDemSchlag(Player spieler, Entity ziel) {
        sprintZurueck = false;
        CriticalsModule m = ModuleManager.INSTANCE.get(CriticalsModule.class);
        if (m == null || !m.isEnabled()) return;
        Minecraft mc = Minecraft.getInstance();
        if (!(spieler instanceof LocalPlayer p) || p != mc.player || mc.level == null) return;
        ClientPacketListener net = mc.getConnection();
        if (net == null || !(ziel instanceof LivingEntity)) return;
        // Mace Kill macht das schon (mit viel mehr Hoehe)
        MaceKillModule mace = ModuleManager.INSTANCE.get(MaceKillModule.class);
        if (mace != null && mace.isEnabled() && p.getMainHandItem().is(Items.MACE)) return;
        if (!p.onGround() || p.onClimbable() || p.isInWater() || p.isInLava() || p.isPassenger() || p.isFallFlying()) return;
        if (p.getAttackStrengthScale(0.5f) < 0.9f) return;             // kein Crit ohne Aufladung
        if (!mc.level.noCollision(p, p.getBoundingBox().move(0, 0.0625, 0))) return;

        if (p.isSprinting()) {
            if (!m.stopSprint.get()) return;
            net.send(new ServerboundPlayerCommandPacket(p, ServerboundPlayerCommandPacket.Action.STOP_SPRINTING));
            sprintZurueck = true;
        }
        boolean hc = p.horizontalCollision;
        double x = p.getX(), y = p.getY(), z = p.getZ();
        net.send(new ServerboundMovePlayerPacket.Pos(x, y + 0.0625, z, false, hc));
        net.send(new ServerboundMovePlayerPacket.Pos(x, y, z, false, hc));
    }

    public static void nachDemSchlag(Player spieler) {
        if (!sprintZurueck) return;
        sprintZurueck = false;
        Minecraft mc = Minecraft.getInstance();
        ClientPacketListener net = mc.getConnection();
        if (net != null && spieler instanceof LocalPlayer p && p.isSprinting()) {
            net.send(new ServerboundPlayerCommandPacket(p, ServerboundPlayerCommandPacket.Action.START_SPRINTING));
        }
    }
}
