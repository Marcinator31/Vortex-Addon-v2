package com.vortex.client.hud;

import com.vortex.client.module.Module;
import com.vortex.client.module.ModuleManager;
import com.vortex.client.module.modules.SusChunksModule;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.phys.AABB;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.core.BlockPos;
import net.minecraft.world.Container;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.Vec3;

/**
 * Sus Chunks: faerbt Chunks als Heatmap nach Spieler-Aktivitaet.
 *
 * Hintergrund-Thread berechnet pro geladenem Chunk einen Score:
 *   Score = Container * 3 + sonstige Block-Entities * 1
 * (Container zaehlen staerker, weil sie der deutlichste Base-Indikator sind.)
 * Chunks ueber dem Mindest-Score bekommen eine vertikale Saeulen-Outline, deren
 * Farbe von gruen (wenig) ueber gelb nach rot (viel) geht.
 */
public final class SusChunks {

    private static final class ChunkMark {
        final AABB box;
        final int color;
        ChunkMark(AABB box, int color) { this.box = box; this.color = color; }
    }

    private static final AtomicReference<List<ChunkMark>> RESULT =
            new AtomicReference<>(new ArrayList<>());
    private static final int CHUNK_RADIUS = 10;

    private static volatile boolean running = false;
    private static Thread worker;

    public static void register() {
        LevelRenderEvents.AFTER_TRANSLUCENT_FEATURES.register(context -> {
            SusChunksModule mod = (SusChunksModule) find(SusChunksModule.class);
            if (mod == null || !mod.isEnabled()) return;

            Minecraft client = Minecraft.getInstance();
            if (client.level == null || client.player == null) return;

            ensureWorker();

            PoseStack matrices = context.poseStack();
            SubmitNodeCollector collector = context.submitNodeCollector();
            if (matrices == null || collector == null) return;

            long pvpT0 = System.nanoTime();
            try {
                float tickDelta = client.getDeltaTracker().getGameTimeDeltaPartialTick(false);
                Vec3 cam = EspRender.cameraOffset(client, tickDelta);

                List<ChunkMark> marks = RESULT.get();
                for (int i = 0; i < marks.size(); i++) {
                    ChunkMark m = marks.get(i);
                    EspRender.submitBox(collector, matrices, m.box, cam, m.color, 2.0f);
                }
            } catch (Throwable pvpErr) {
                com.vortex.client.core.Errors.report("SusChunks", pvpErr);
            } finally {
                // Draw cost only -- the scan runs on the worker thread.
                com.vortex.client.core.Profiler.record("SusChunks",
                        System.nanoTime() - pvpT0);
            }
        });
    }

    private static void ensureWorker() {
        if (running) return;
        running = true;
        worker = new Thread(SusChunks::workerLoop, "vortexclient-suschunks");
        worker.setDaemon(true);
        worker.start();
    }

    private static void workerLoop() {
        while (true) {
            try {
                Thread.sleep(400);

                Minecraft client = Minecraft.getInstance();
                SusChunksModule mod = (SusChunksModule) find(SusChunksModule.class);
                if (client == null || mod == null || !mod.isEnabled()) {
                    if (!RESULT.get().isEmpty()) RESULT.set(new ArrayList<>());
                    continue;
                }
                ClientLevel world = client.level;
                if (world == null || client.player == null) {
                    if (!RESULT.get().isEmpty()) RESULT.set(new ArrayList<>());
                    continue;
                }

                int minScore = mod.getMinScore();
                int maxScore = Math.max(mod.getMaxScore(), minScore + 1);
                int minY = world.getMinY();
                int maxY = world.getMaxY();

                // Daten aus der sicheren Momentaufnahme (Haupt-Thread).
                WorldScan.Snapshot snap = WorldScan.get();
                if (snap.chunkCounts.isEmpty()) {
                    if (!RESULT.get().isEmpty()) RESULT.set(new ArrayList<>());
                    continue;
                }

                List<ChunkMark> marks = new ArrayList<>();
                for (java.util.Map.Entry<Long, int[]> e : snap.chunkCounts.entrySet()) {
                    int[] c = e.getValue();
                    // Kisten zaehlen dreifach, sonstige Block-Entities einfach.
                    int score = c[0] * 3 + c[1];
                    if (score < minScore) continue;

                    long ck = e.getKey();
                    int ccx = (int) (ck >> 32);
                    int ccz = (int) ck;

                    // Score auf 0..1 normieren -> Heatmap-Farbe.
                    float t = (float) (score - minScore) / (float) (maxScore - minScore);
                    if (t > 1f) t = 1f;
                    int color = heatColor(t);

                    double x0 = ccx << 4, z0 = ccz << 4;
                    AABB box = new AABB(x0 + 0.5, minY, z0 + 0.5,
                            x0 + 15.5, maxY, z0 + 15.5);
                    marks.add(new ChunkMark(box, color));
                }

                RESULT.set(marks);
            } catch (InterruptedException ie) {
                return;
            } catch (Throwable pvpErr) {
                // Durchlauf ueberspringen.
            }
        }
    }

    /** Heatmap: t=0 -> gruen, t=0.5 -> gelb, t=1 -> rot. ARGB mit fixem Alpha. */
    private static int heatColor(float t) {
        int r, g;
        if (t < 0.5f) {
            // gruen -> gelb
            r = (int) (255 * (t / 0.5f));
            g = 255;
        } else {
            // gelb -> rot
            r = 255;
            g = (int) (255 * (1f - (t - 0.5f) / 0.5f));
        }
        int a = 0xC0; // leicht transparent
        return (a << 24) | (r << 16) | (g << 8);
    }

    private static Module find(Class<? extends Module> type) {
        // Konstante Laufzeit statt die ganze Liste zu durchlaufen.
        return ModuleManager.INSTANCE.get(type);
    }
}
