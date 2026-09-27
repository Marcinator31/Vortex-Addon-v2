package com.vortex.client.hud;

import com.mojang.blaze3d.vertex.PoseStack;
import com.vortex.client.module.ModuleManager;
import com.vortex.client.module.modules.LogoutSpotsModule;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Logout Spots.
 *
 * WIE ES ERKANNT WIRD: Ein Spieler verschwindet aus der Welt. Das passiert
 * auch, wenn er nur aus der Sichtweite laeuft oder stirbt. Ausgeloggt ist er
 * erst, wenn er AUCH aus der Tab-Liste verschwindet -- der Server schickt
 * beides kurz nacheinander. Deshalb wartet ein verschwundener Spieler bis zu
 * 2 Sekunden auf "auch aus der Tab-Liste weg".
 *
 * Kommt er zurueck (wieder in der Tab-Liste), wird die Markierung geloescht.
 */
public final class LogoutSpots {

    private LogoutSpots() {}

    private record Spot(String name, AABB box, Vec3 pos, float leben) {}

    /** Letzter Stand jedes sichtbaren Spielers. */
    private static final Map<UUID, Spot> SICHTBAR = new HashMap<>();
    /** Verschwunden, aber noch in der Tab-Liste: Zeitpunkt des Verschwindens. */
    private static final Map<UUID, Long> WARTET = new HashMap<>();
    private static final Map<UUID, Spot> WARTET_SPOT = new HashMap<>();
    /** Die Markierungen. */
    private static final Map<UUID, Spot> SPOTS = new LinkedHashMap<>();

    private static Object welt = null;
    private static long tick = 0;

    public static void leeren() {
        SICHTBAR.clear();
        WARTET.clear();
        WARTET_SPOT.clear();
        SPOTS.clear();
    }

    public static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            try { tick(mc); } catch (Throwable e) { com.vortex.client.core.Errors.report("LogoutSpots.tick", e); }
        });
        LevelRenderEvents.AFTER_TRANSLUCENT_FEATURES.register(context -> {
            try {
                LogoutSpotsModule m = ModuleManager.INSTANCE.get(LogoutSpotsModule.class);
                if (m == null || !m.isEnabled() || SPOTS.isEmpty()) return;
                Minecraft mc = Minecraft.getInstance();
                if (mc.level == null || mc.player == null) return;
                PoseStack matrices = context.poseStack();
                SubmitNodeCollector collector = context.submitNodeCollector();
                if (matrices == null || collector == null) return;
                float td = mc.getDeltaTracker().getGameTimeDeltaPartialTick(false);
                Vec3 cam = EspRender.cameraOffset(mc, td);
                int farbe = m.color.get();
                if ((farbe >>> 24) == 0) farbe |= 0xFF000000;
                Vec3 start = EspRender.tracerStart(mc, cam, td);
                for (Spot s : SPOTS.values()) {
                    EspRender.submitBox(collector, matrices, s.box(), cam, farbe, 2.0f);
                    if (m.tracer.get()) {
                        final Vec3 ziel = s.box().getCenter();
                        final int f = farbe;
                        EspRender.submitLines(collector, matrices,
                                (matrix, lines) -> EspRender.drawTracer(matrix, lines, start, ziel, cam, f, 1.5f));
                    }
                }
            } catch (Throwable e) {
                com.vortex.client.core.Errors.report("LogoutSpots.render", e);
            }
        });
    }

    private static void tick(Minecraft mc) {
        tick++;
        LogoutSpotsModule m = ModuleManager.INSTANCE.get(LogoutSpotsModule.class);
        if (m == null || !m.isEnabled() || mc.level == null || mc.player == null || mc.getConnection() == null) {
            if (mc.level == null) { leeren(); welt = null; }
            return;
        }
        // Neue Welt / neuer Server: alte Markierungen gelten nicht mehr.
        if (welt != mc.level) {
            leeren();
            welt = mc.level;
        }
        Set<UUID> online = new java.util.HashSet<>(mc.getConnection().getOnlinePlayerIds());

        // Wer ist gerade sichtbar?
        Map<UUID, Spot> jetzt = new HashMap<>();
        for (Player o : mc.level.players()) {
            if (o == mc.player) continue;
            jetzt.put(o.getUUID(), new Spot(o.getName().getString(), o.getBoundingBox(),
                    o.position(), o.getHealth()));
        }
        // Verschwunden -> auf die Tab-Liste warten
        for (Map.Entry<UUID, Spot> e : SICHTBAR.entrySet()) {
            if (!jetzt.containsKey(e.getKey()) && !WARTET.containsKey(e.getKey())) {
                WARTET.put(e.getKey(), tick);
                WARTET_SPOT.put(e.getKey(), e.getValue());
            }
        }
        SICHTBAR.clear();
        SICHTBAR.putAll(jetzt);

        // Entscheiden: ausgeloggt oder nur weg?
        List<UUID> fertig = new ArrayList<>();
        for (Map.Entry<UUID, Long> e : WARTET.entrySet()) {
            UUID id = e.getKey();
            if (jetzt.containsKey(id)) { fertig.add(id); continue; }       // wieder da
            if (!online.contains(id)) {
                Spot s = WARTET_SPOT.get(id);
                if (s != null) {
                    SPOTS.put(id, s);
                    if (m.chat.get()) {
                        mc.player.sendSystemMessage(Component.literal(String.format(java.util.Locale.ROOT,
                                "§d[Vortex]§r %s hat sich ausgeloggt bei %d %d %d (%.0f HP)",
                                s.name(), (int) Math.floor(s.pos().x), (int) Math.floor(s.pos().y),
                                (int) Math.floor(s.pos().z), s.leben())));
                    }
                    while (SPOTS.size() > m.maxSpots.getInt()) {
                        SPOTS.remove(SPOTS.keySet().iterator().next());
                    }
                }
                fertig.add(id);
            } else if (tick - e.getValue() > 40) {
                fertig.add(id);                                              // nur aus Sichtweite
            }
        }
        for (UUID id : fertig) { WARTET.remove(id); WARTET_SPOT.remove(id); }

        // Wieder eingeloggt -> Markierung weg
        SPOTS.keySet().removeIf(id -> {
            if (!online.contains(id)) return false;
            if (m.chat.get()) {
                Spot s = SPOTS.get(id);
                mc.player.sendSystemMessage(Component.literal(
                        "§d[Vortex]§r " + (s == null ? "?" : s.name()) + " ist wieder online."));
            }
            return true;
        });
    }
}
