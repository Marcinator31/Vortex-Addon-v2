package com.vortex.client.hud;

import com.vortex.client.module.Module;
import com.vortex.client.module.ModuleManager;
import com.vortex.client.module.modules.StashFinderModule;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.phys.Vec3;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.Container;
import net.minecraft.world.level.block.entity.BlockEntity;

/**
 * Findet Stashes ueber die Container-Dichte pro Chunk.
 *
 * Architektur wie beim Block-ESP: Ein HINTERGRUND-THREAD durchsucht die
 * geladenen Chunks, zaehlt Container (alles was Container implementiert) je
 * Chunk und merkt sich Chunks ueber der Schwelle als Stash. Der Render-Thread
 * liest nur das Ergebnis und zeichnet Tracer -> keine FPS-Drops.
 *
 * Bei einem NEUEN Stash (Chunk, der vorher nicht gemeldet war) gibt es einmalig
 * eine Chat-Benachrichtigung mit Koordinaten.
 */
public final class StashFinder {

    /** Ein gefundener Stash: Zentrum + Anzahl Container. */
    public static final class Stash {
        public final long chunkKey;   // eindeutige Chunk-Kennung
        public final Vec3 center;    // Mittelpunkt (fuer Tracer)
        public final int blockX, blockZ;
        public final int count;
        Stash(long chunkKey, Vec3 center, int blockX, int blockZ, int count) {
            this.chunkKey = chunkKey;
            this.center = center;
            this.blockX = blockX;
            this.blockZ = blockZ;
            this.count = count;
        }
    }

    private static final AtomicReference<List<Stash>> RESULT =
            new AtomicReference<>(new ArrayList<>());

    // Chunks, die schon einmal als Stash gemeldet wurden (gegen Spam).
    private static final Set<Long> notified = new HashSet<>();
    // Pending Chat-Meldungen, die der Main-Thread ausgeben soll.
    private static final AtomicReference<List<Stash>> PENDING_NOTIFY =
            new AtomicReference<>(new ArrayList<>());

    // Wie weit (in Chunks) um den Spieler gesucht wird.
    private static final int CHUNK_RADIUS = 12;

    private static volatile boolean running = false;
    private static Thread worker;

    public static void register() {
        LevelRenderEvents.AFTER_TRANSLUCENT_FEATURES.register(context -> {
            StashFinderModule mod = (StashFinderModule) find(StashFinderModule.class);
            if (mod == null || !mod.isEnabled()) return;

            Minecraft client = Minecraft.getInstance();
            if (client.level == null || client.player == null) return;

            ensureWorker();

            // Ausstehende Chat-Meldungen ausgeben (im Main-/Render-Thread sicher).
            if (mod.notifyEnabled()) {
                List<Stash> pend = PENDING_NOTIFY.getAndSet(new ArrayList<>());
                for (Stash s : pend) {
                    String msg = "\u00a7d[Stash Finder] \u00a7fStash found at \u00a7e"
                            + s.blockX + ", " + s.blockZ
                            + " \u00a77(" + s.count + " Container)";
                    try {
                        if (client.player != null) client.player.sendSystemMessage(Component.literal(msg));
                    } catch (Throwable pvpErr) {
                com.vortex.client.core.Errors.report("StashFinder", pvpErr);
            }
                }
            } else {
                PENDING_NOTIFY.set(new ArrayList<>());
            }

            // Tracer zeichnen.
            if (!mod.tracerEnabled()) return;
            PoseStack matrices = context.poseStack();
            SubmitNodeCollector collector = context.submitNodeCollector();
            if (matrices == null || collector == null) return;

            long pvpT0 = System.nanoTime();
            try {
                float tickDelta = client.getDeltaTracker().getGameTimeDeltaPartialTick(false);
                // Via the shared helper, which uses the real render camera and
                // handles freecam. Computing this locally from the player's
                // eyes shifted everything as soon as the view changed.
                Vec3 cam = EspRender.cameraOffset(client, tickDelta);
                if (com.vortex.client.freecam.Freecam.isActive()) {
                    cam = com.vortex.client.freecam.Freecam.getPos();
                }

                int color = mod.getTracerColor();
                if ((color >>> 24) == 0) color = 0xFF000000 | color;
                final Vec3 tracerCam = cam;
                final Vec3 tracerStart = pvpclient$tracerStart(client, tracerCam, tickDelta);
                final int tracerColor = color;
                final List<Stash> stashes = List.copyOf(RESULT.get());

                EspRender.submitLines(collector, matrices, (matrix, lines) -> {
                    for (Stash stash : stashes) {
                        EspRender.drawTracer(matrix, lines, tracerStart, stash.center,
                                tracerCam, tracerColor, 2.0f);
                    }
                });
            } catch (Throwable pvpErr) {
                com.vortex.client.core.Errors.report("StashFinder", pvpErr);
            } finally {
                // Draw cost only. The chunk scanning itself runs on the worker
                // thread and never touches the frame; what /lag shows here is
                // the per-frame price of the tracer lines.
                com.vortex.client.core.Profiler.record("StashFinder",
                        System.nanoTime() - pvpT0);
            }
        });
    }

    private static Vec3 pvpclient$tracerStart(Minecraft client, Vec3 cam,
                                               float tickDelta) {
        float yaw, pitch;
        if (com.vortex.client.freecam.Freecam.isActive()) {
            yaw = com.vortex.client.freecam.Freecam.getYaw();
            pitch = com.vortex.client.freecam.Freecam.getPitch();
        } else {
            yaw = client.player.getViewYRot(tickDelta);
            pitch = client.player.getViewXRot(tickDelta);
        }
        double yawRad = Math.toRadians(yaw);
        double pitchRad = Math.toRadians(pitch);
        double fx = -Math.sin(yawRad) * Math.cos(pitchRad);
        double fy = -Math.sin(pitchRad);
        double fz = Math.cos(yawRad) * Math.cos(pitchRad);
        return new Vec3(cam.x + fx * 0.5, cam.y + fy * 0.5, cam.z + fz * 0.5);
    }

    private static void ensureWorker() {
        if (running) return;
        running = true;
        worker = new Thread(StashFinder::workerLoop, "vortexclient-stashfinder");
        worker.setDaemon(true);
        worker.start();
    }

    private static void workerLoop() {
        while (true) {
            try {
                Thread.sleep(500); // Stashes aendern sich langsam -> 2x/Sekunde reicht

                Minecraft client = Minecraft.getInstance();
                StashFinderModule mod = (StashFinderModule) find(StashFinderModule.class);
                if (client == null || mod == null || !mod.isEnabled()) {
                    if (!RESULT.get().isEmpty()) RESULT.set(new ArrayList<>());
                    continue;
                }
                ClientLevel world = client.level;
                if (world == null || client.player == null) {
                    if (!RESULT.get().isEmpty()) RESULT.set(new ArrayList<>());
                    continue;
                }

                int threshold = mod.getThreshold();

                // Daten kommen aus der sicheren Momentaufnahme (Haupt-Thread).
                WorldScan.Snapshot snap = WorldScan.get();
                if (snap.chunkCounts.isEmpty()) {
                    if (!RESULT.get().isEmpty()) RESULT.set(new ArrayList<>());
                    continue;
                }

                List<Stash> found = new ArrayList<>();
                List<Stash> newlyFound = new ArrayList<>();

                for (java.util.Map.Entry<Long, int[]> e : snap.chunkCounts.entrySet()) {
                    int count = e.getValue()[0]; // Anzahl Kisten/Inventare
                    if (count < threshold) continue;

                    long ck = e.getKey();
                    int ccx = (int) (ck >> 32);
                    int ccz = (int) ck;
                    int bx = (ccx << 4) + 8;
                    int bz = (ccz << 4) + 8;
                    Stash st = new Stash(ck,
                            new net.minecraft.world.phys.Vec3(bx + 0.5, 64.0, bz + 0.5),
                            bx, bz, count);
                    found.add(st);
                    if (!notified.contains(ck)) {
                        notified.add(ck);
                        newlyFound.add(st);
                    }
                }

                RESULT.set(found);
                if (!newlyFound.isEmpty()) {
                    // Zu den ausstehenden Meldungen hinzufuegen.
                    List<Stash> pend = new ArrayList<>(PENDING_NOTIFY.get());
                    pend.addAll(newlyFound);
                    PENDING_NOTIFY.set(pend);
                }
            } catch (InterruptedException ie) {
                return;
            } catch (Throwable t) {
                // Nebenlaeufigkeit/Chunk entladen -> Durchlauf ueberspringen.
            }
        }
    }

    /**
     * Zaehlt Container in einem Chunk. Liegt die Zahl >= Schwelle, wird ein
     * Stash mit dem Schwerpunkt der Container zurueckgegeben, sonst null.
     */
    private static Module find(Class<? extends Module> type) {
        // Konstante Laufzeit statt die ganze Liste zu durchlaufen.
        return ModuleManager.INSTANCE.get(type);
    }

    /**
     * Setzt die gemeldeten Stashes zurueck. Beim Welt-/Serverwechsel aufrufen,
     * damit Stashes an gleichen Koordinaten in einer neuen Welt wieder gemeldet
     * werden (und das notified-Set nicht ewig waechst).
     */
    public static void reset() {
        notified.clear();
        RESULT.set(new ArrayList<>());
        PENDING_NOTIFY.set(new ArrayList<>());
    }
}
