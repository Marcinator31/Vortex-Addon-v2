package com.vortex.client.hud;

import com.vortex.client.module.Module;
import com.vortex.client.module.ModuleManager;
import com.vortex.client.module.modules.ContainerEspModule;
import com.vortex.client.module.modules.SpawnerEspModule;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.world.Container;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.SpawnerBlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;

/**
 * ESP fuer Block-Entities: Container (alles mit Inventar) und Mob-Spawner.
 *
 * Wie beim Block-ESP laeuft die Suche in einem HINTERGRUND-THREAD (stabile FPS),
 * der die geladenen Chunks durchgeht und die passenden Block-Entities sammelt.
 * Der Render-Thread zeichnet nur die fertigen Boxen (+ optional Tracer).
 *
 * Container und Spawner teilen sich Scan und Renderer; pro Treffer merken wir
 * uns Position und Typ, damit wir mit der jeweils eingestellten Farbe zeichnen.
 */
public final class BlockEntityEsp {

    private static final class Hit {
        final AABB box;
        final Vec3 center;
        final boolean spawner; // true = Spawner, false = Container
        Hit(AABB box, Vec3 center, boolean spawner) {
            this.box = box; this.center = center; this.spawner = spawner;
        }
    }

    private static final AtomicReference<List<Hit>> RESULT =
            new AtomicReference<>(new ArrayList<>());
    private static final int CHUNK_RADIUS = 8;
    private static final int MAX_RESULTS = 3000;

    /**
     * Obergrenzen fuers ZEICHNEN (nicht fuers Suchen).
     *
     * Jeder gezeichnete Kasten erzeugt ein Hilfsobjekt und mehrere Linienzuege auf
     * der Grafikkarte. Bei mehreren tausend Kaesten pro Bild sind das
     * hunderttausende Objekte pro Sekunde -- das fuehrt zu Rucklern und kann dem
     * Spiel den Speicher/Grafiktreiber sprengen (harter Absturz ohne Crash-Report).
     * Deshalb: nur Nahes zeichnen und die Anzahl pro Bild deckeln.
     */
    private static final double MAX_DRAW_DIST = 96.0;
    private static final int MAX_DRAW_PER_FRAME = 400;

    private static volatile boolean running = false;
    private static Thread worker;

    public static void register() {
        LevelRenderEvents.AFTER_TRANSLUCENT_FEATURES.register(context -> {
            long pvpT0 = System.nanoTime();
            try {
            ContainerEspModule cont = (ContainerEspModule) find(ContainerEspModule.class);
            SpawnerEspModule spawn = (SpawnerEspModule) find(SpawnerEspModule.class);
            boolean contOn = cont != null && cont.isEnabled();
            boolean spawnOn = spawn != null && spawn.isEnabled();
            if (!contOn && !spawnOn) return;

            Minecraft client = Minecraft.getInstance();
            if (client.level == null || client.player == null) return;

            ensureWorker();

            PoseStack matrices = context.poseStack();
            SubmitNodeCollector collector = context.submitNodeCollector();
            if (matrices == null || collector == null) return;

            try {
                float tickDelta = client.getDeltaTracker().getGameTimeDeltaPartialTick(false);
                Vec3 cam = EspRender.cameraOffset(client, tickDelta);

                int contColor = contOn ? cont.getColor() : 0;
                if ((contColor >>> 24) == 0) contColor |= 0xFF000000;
                int spawnColor = spawnOn ? spawn.getColor() : 0;
                if ((spawnColor >>> 24) == 0) spawnColor |= 0xFF000000;

                Vec3 start = EspRender.tracerStart(client, cam, tickDelta);

                List<Hit> hits = RESULT.get();
                double maxDistSq = MAX_DRAW_DIST * MAX_DRAW_DIST;
                int drawn = 0;
                for (int i = 0; i < hits.size(); i++) {
                    if (drawn >= MAX_DRAW_PER_FRAME) break;
                    Hit h = hits.get(i);
                    // Je nach Typ pruefen, ob das Modul an ist + Farbe waehlen.
                    if (h.spawner && !spawnOn) continue;
                    if (!h.spawner && !contOn) continue;
                    // Zu weit weg -> gar nicht erst zeichnen (spart Objekte + GPU).
                    if (h.center.distanceToSqr(cam) > maxDistSq) continue;
                    drawn++;
                    int color = h.spawner ? spawnColor : contColor;
                    EspRender.submitBox(collector, matrices, h.box, cam, color, 2.0f);

                    boolean tracer = h.spawner ? spawn.tracerEnabled() : cont.tracerEnabled();
                    if (tracer) {
                        final Vec3 tracerStart = start;
                        final Vec3 tracerTarget = h.center;
                        final Vec3 tracerCam = cam;
                        final int tracerColor = color;
                        EspRender.submitLines(collector, matrices,
                                (matrix, lines) -> EspRender.drawTracer(matrix, lines,
                                        tracerStart, tracerTarget, tracerCam, tracerColor, 2.0f));
                    }
                }
            } catch (Throwable pvpErr) {
                com.vortex.client.core.Errors.report("ContainerEsp", pvpErr);
            }
                    } finally {
                com.vortex.client.core.Profiler.record("ContainerEsp",
                        System.nanoTime() - pvpT0);
            }
        });
    }

    private static void ensureWorker() {
        if (running) return;
        running = true;
        worker = new Thread(BlockEntityEsp::workerLoop, "vortexclient-blockentity-esp");
        worker.setDaemon(true);
        worker.start();
    }

    /**
     * Baut die Anzeige-Liste aus der Momentaufnahme von {@link WorldScan}.
     *
     * Hier wird NICHT mehr selbst in der Welt gelesen -- das passiert sicher auf
     * dem Haupt-Thread. Diese Schleife verarbeitet nur noch fertige Daten und
     * kann deshalb nicht mehr haengen bleiben.
     */
    private static void workerLoop() {
        int lastVersion = -1;
        while (true) {
            try {
                Thread.sleep(200);

                ContainerEspModule cont = (ContainerEspModule) find(ContainerEspModule.class);
                SpawnerEspModule spawn = (SpawnerEspModule) find(SpawnerEspModule.class);
                boolean contOn = cont != null && cont.isEnabled();
                boolean spawnOn = spawn != null && spawn.isEnabled();
                if (!contOn && !spawnOn) {
                    if (!RESULT.get().isEmpty()) RESULT.set(new ArrayList<>());
                    lastVersion = -1;
                    continue;
                }

                WorldScan.Snapshot snap = WorldScan.get();
                if (snap.entries.isEmpty()) {
                    if (!RESULT.get().isEmpty()) RESULT.set(new ArrayList<>());
                    continue;
                }
                // Nur neu aufbauen, wenn frische Daten vorliegen.
                if (snap.version == lastVersion) continue;
                lastVersion = snap.version;

                List<Hit> found = new ArrayList<>();
                for (WorldScan.Be be : snap.entries) {
                    if (be.spawner && spawnOn) {
                        found.add(makeHit(be.pos, true));
                    } else if (be.inventory && !be.spawner && contOn) {
                        found.add(makeHit(be.pos, false));
                    }
                    if (found.size() >= MAX_RESULTS) break;
                }
                RESULT.set(found);
            } catch (InterruptedException ie) {
                return;
            } catch (Throwable t) {
                // Durchlauf ueberspringen.
            }
        }
    }

    private static Hit makeHit(BlockPos p, boolean spawner) {
        AABB box = new AABB(p.getX(), p.getY(), p.getZ(),
                p.getX() + 1.0, p.getY() + 1.0, p.getZ() + 1.0);
        Vec3 center = new Vec3(p.getX() + 0.5, p.getY() + 0.5, p.getZ() + 0.5);
        return new Hit(box, center, spawner);
    }

    private static Module find(Class<? extends Module> type) {
        // Konstante Laufzeit statt die ganze Liste zu durchlaufen.
        return ModuleManager.INSTANCE.get(type);
    }
}
