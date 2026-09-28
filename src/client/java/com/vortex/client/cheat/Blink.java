package com.vortex.client.cheat;

import com.mojang.blaze3d.vertex.PoseStack;
import com.vortex.client.core.PacketHooks;
import com.vortex.client.hud.EspRender;
import com.vortex.client.module.ModuleManager;
import com.vortex.client.module.modules.BlinkModule;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.world.phys.AABB;

/**
 * Blink (siehe BlinkModule).
 *
 * Nur Bewegungspakete werden zurueckgehalten. Alles andere (Keep-Alive,
 * Chat, Schlaege) geht normal raus -- sonst wirft der Server dich nach 30 s
 * wegen Zeitueberschreitung.
 */
public final class Blink {

    private Blink() {}

    private static final List<Packet<?>> PUFFER = new ArrayList<>();
    private static volatile boolean haelt = false;
    private static volatile boolean durchlassen = false;
    private static volatile boolean zurueckgesetzt = false;
    private static AABB startBox = null;
    private static long startZeit = 0, pulsZeit = 0;

    public static void register() {
        PacketHooks.onSend(Blink::senden);
        PacketHooks.onReceive(p -> {
            if (haelt && p instanceof ClientboundPlayerPositionPacket) zurueckgesetzt = true;
            return false;
        });
        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            try { tick(mc); } catch (Throwable e) { com.vortex.client.core.Errors.report("Blink", e); }
        });
        LevelRenderEvents.AFTER_TRANSLUCENT_FEATURES.register(context -> {
            try {
                BlinkModule m = ModuleManager.INSTANCE.get(BlinkModule.class);
                AABB box = startBox;
                if (m == null || !m.isEnabled() || !m.render.get() || box == null || !haelt) return;
                Minecraft mc = Minecraft.getInstance();
                PoseStack ms = context.poseStack();
                SubmitNodeCollector col = context.submitNodeCollector();
                if (ms == null || col == null || mc.player == null) return;
                float td = mc.getDeltaTracker().getGameTimeDeltaPartialTick(false);
                int f = m.color.get();
                if ((f >>> 24) == 0) f |= 0xFF000000;
                EspRender.submitBox(col, ms, box, EspRender.cameraOffset(mc, td), f, 2.0f);
            } catch (Throwable e) {
                com.vortex.client.core.Errors.report("Blink.render", e);
            }
        });
    }

    private static boolean senden(Packet<?> p) {
        if (!haelt || durchlassen) return false;
        if (!(p instanceof ServerboundMovePlayerPacket)) return false;
        synchronized (PUFFER) {
            PUFFER.add(p);
        }
        return true;
    }

    /** Vom Modul (onEnable). */
    public static void start() {
        Minecraft mc = Minecraft.getInstance();
        synchronized (PUFFER) { PUFFER.clear(); }
        zurueckgesetzt = false;
        if (mc.player == null) { haelt = false; return; }
        startBox = mc.player.getBoundingBox();
        startZeit = pulsZeit = System.currentTimeMillis();
        haelt = true;
    }

    /** Vom Modul (onDisable): alles auf einmal senden. */
    public static void loslassen() {
        haelt = false;
        senden();
        startBox = null;
    }

    private static void verwerfen() {
        haelt = false;
        synchronized (PUFFER) { PUFFER.clear(); }
        startBox = null;
    }

    private static void senden() {
        Minecraft mc = Minecraft.getInstance();
        var net = mc.getConnection();
        List<Packet<?>> kopie;
        synchronized (PUFFER) {
            kopie = new ArrayList<>(PUFFER);
            PUFFER.clear();
        }
        if (net == null) return;
        durchlassen = true;
        try {
            for (Packet<?> p : kopie) net.send(p);
        } finally {
            durchlassen = false;
        }
    }

    private static void tick(Minecraft mc) {
        BlinkModule m = ModuleManager.INSTANCE.get(BlinkModule.class);
        if (m == null || !m.isEnabled()) return;
        if (mc.player == null || mc.getConnection() == null) {
            verwerfen();
            m.setEnabled(false);
            return;
        }
        if (!haelt) start();
        if (zurueckgesetzt) {
            verwerfen();
            m.setEnabled(false);
            mc.player.sendOverlayMessage(Component.literal("§dBlink: the server moved you back -- stopped."));
            return;
        }
        long jetzt = System.currentTimeMillis();
        if (jetzt - startZeit > m.maxSeconds.get() * 1000) {
            m.setEnabled(false);          // loest loslassen() aus
            mc.player.sendOverlayMessage(Component.literal("§dBlink: released after " + m.maxSeconds.getInt() + " s."));
            return;
        }
        if (m.pulse.get() > 0 && jetzt - pulsZeit > m.pulse.get() * 1000) {
            senden();
            pulsZeit = startZeit = jetzt;
            startBox = mc.player.getBoundingBox();
        }
        int n;
        synchronized (PUFFER) { n = PUFFER.size(); }
        mc.player.sendOverlayMessage(Component.literal("§dBlink §7" + n + " packets held"));
    }
}
