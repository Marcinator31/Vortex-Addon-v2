package com.vortex.client.hud;

import com.mojang.blaze3d.vertex.PoseStack;
import com.vortex.client.module.ModuleManager;
import com.vortex.client.module.modules.SusChunksModule;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Sus Chunks: faerbt Chunks als Heatmap nach Spieler-Spuren.
 *
 * Der Wert je Chunk kommt fertig aus WorldScan (gewichtete Block-Entities,
 * siehe WorldScan.susGewicht). Hier wird nur noch in Rahmen umgesetzt -- und
 * nur, wenn WorldScan eine neue Runde fertig hat. Frueher lief dafuer ein
 * eigener Thread ununterbrochen alle 0,4 Sekunden.
 */
public final class SusChunks {

    private SusChunks() {}

    private record Mark(AABB box, int color) {}

    private static volatile List<Mark> marks = List.of();
    private static int letzteVersion = -1;
    private static int letzteEinstellung = 0;
    private static final Set<Long> GEMELDET = new HashSet<>();

    public static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(SusChunks::tick);
        ClientPlayConnectionEvents.DISCONNECT.register((h, c) -> {
            marks = List.of();
            letzteVersion = -1;
            GEMELDET.clear();
        });
        LevelRenderEvents.AFTER_TRANSLUCENT_FEATURES.register(context -> {
            SusChunksModule mod = ModuleManager.INSTANCE.get(SusChunksModule.class);
            if (mod == null || !mod.isEnabled()) return;
            Minecraft client = Minecraft.getInstance();
            if (client.level == null || client.player == null) return;
            PoseStack matrices = context.poseStack();
            SubmitNodeCollector collector = context.submitNodeCollector();
            if (matrices == null || collector == null) return;

            long t0 = System.nanoTime();
            try {
                float tickDelta = client.getDeltaTracker().getGameTimeDeltaPartialTick(false);
                Vec3 cam = EspRender.cameraOffset(client, tickDelta);
                for (Mark m : marks) EspRender.submitBox(collector, matrices, m.box(), cam, m.color(), 2.0f);
            } catch (Throwable pvpErr) {
                com.vortex.client.core.Errors.report("SusChunks", pvpErr);
            } finally {
                com.vortex.client.core.Profiler.record("SusChunks", System.nanoTime() - t0);
            }
        });
    }

    private static void tick(Minecraft mc) {
        try {
            SusChunksModule mod = ModuleManager.INSTANCE.get(SusChunksModule.class);
            if (mod == null || !mod.isEnabled() || mc.level == null || mc.player == null) {
                if (!marks.isEmpty()) marks = List.of();
                letzteVersion = -1;
                return;
            }
            // Neu rechnen bei neuer Scan-Runde ODER geaenderten Einstellungen
            // (sonst wirkte ein Regler erst Sekunden spaeter).
            int einst = mod.getMinScore() * 31 * 31 + mod.getMaxScore() * 31 + (mod.fullHeight.get() ? 1 : 0);
            WorldScan.Snapshot snap = WorldScan.get();
            if (snap.version == letzteVersion && einst == letzteEinstellung) return;
            letzteVersion = snap.version;
            letzteEinstellung = einst;

            int minScore = mod.getMinScore();
            int maxScore = Math.max(mod.getMaxScore(), minScore + 1);
            int weltMin = mc.level.getMinY(), weltMax = mc.level.getMaxY();
            List<Mark> out = new ArrayList<>();
            List<String> meldungen = new ArrayList<>();
            for (Map.Entry<Long, int[]> e : snap.chunkCounts.entrySet()) {
                int[] c = e.getValue();
                if (c.length <= WorldScan.C_MAX_Y) continue;
                int score = c[WorldScan.C_SUS];
                if (score < minScore) continue;
                long ck = e.getKey();
                int ccx = (int) (ck >> 32), ccz = (int) ck;
                float t = Math.min(1f, (float) (score - minScore) / (maxScore - minScore));
                double y0, y1;
                if (mod.fullHeight.get()) { y0 = weltMin; y1 = weltMax; }
                else {
                    y0 = Math.max(weltMin, c[WorldScan.C_MIN_Y] - 1);
                    y1 = Math.min(weltMax, c[WorldScan.C_MAX_Y] + 2);
                }
                double x0 = ccx << 4, z0 = ccz << 4;
                out.add(new Mark(new AABB(x0 + 0.5, y0, z0 + 0.5, x0 + 15.5, y1, z0 + 15.5), heatColor(t)));
                if (mod.notify.get() && score >= maxScore && GEMELDET.add(ck)) {
                    meldungen.add("§c[Sus Chunks] §fVery active chunk at §e"
                            + ((ccx << 4) + 8) + " " + c[WorldScan.C_MIN_Y] + " " + ((ccz << 4) + 8)
                            + " §7(score " + score + ")");
                }
            }
            marks = out;
            for (String m : meldungen) mc.player.sendSystemMessage(Component.literal(m));
        } catch (Throwable pvpErr) {
            com.vortex.client.core.Errors.report("SusChunks.tick", pvpErr);
        }
    }

    /** Heatmap: t=0 -> gruen, t=0.5 -> gelb, t=1 -> rot. ARGB mit festem Alpha. */
    private static int heatColor(float t) {
        int r, g;
        if (t < 0.5f) { r = (int) (255 * (t / 0.5f)); g = 255; }
        else { r = 255; g = (int) (255 * (1f - (t - 0.5f) / 0.5f)); }
        return (0xC0 << 24) | (r << 16) | (g << 8);
    }
}
