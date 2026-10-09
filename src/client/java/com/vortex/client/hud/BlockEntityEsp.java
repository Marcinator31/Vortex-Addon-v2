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
 *
 * Seit 2.38: Aussehen und Zeichnen ueber {@link BlockHighlight} -- eine
 * Doppeltruhe oder eine Reihe Faesser ist eine Form, mit Fuellung, Glow und
 * Ein-/Ausblenden; alles in wenigen Zeichenauftraegen statt einem je Kasten.
 */
public final class BlockEntityEsp {

    /** Fertige Geometrie: [0] Container, [1] Spawner. */
    private static final AtomicReference<BlockHighlight.Mesh[]> RESULT =
            new AtomicReference<>(new BlockHighlight.Mesh[]{BlockHighlight.LEER, BlockHighlight.LEER});
    private static final BlockHighlight.Kanal CONTAINER = new BlockHighlight.Kanal();
    private static final BlockHighlight.Kanal SPAWNER = new BlockHighlight.Kanal();
    private static final int MAX_RESULTS = 50000;
    private static final int MAX_TRACER = 400;

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
            if (!contOn && !spawnOn) { CONTAINER.leeren(); SPAWNER.leeren(); return; }

            Minecraft client = Minecraft.getInstance();
            if (client.level == null || client.player == null) { CONTAINER.leeren(); SPAWNER.leeren(); return; }

            ensureWorker();

            PoseStack matrices = context.poseStack();
            SubmitNodeCollector collector = context.submitNodeCollector();
            if (matrices == null || collector == null) return;

            try {
                float tickDelta = client.getDeltaTracker().getGameTimeDeltaPartialTick(false);
                Vec3 cam = EspRender.cameraOffset(client, tickDelta);
                Vec3 start = EspRender.tracerStart(client, cam, tickDelta);
                BlockHighlight.Mesh[] meshes = RESULT.get();

                if (contOn) {
                    BlockHighlight.Stil stil = BlockHighlight.stil(cont.getColor(), cont.style, cont.fillOpacity, cont.glow, 2.0f, cont.viewDistance.get());
                    CONTAINER.setze(meshes[0]);
                    CONTAINER.zeichne(collector, matrices, cam, stil);
                    if (cont.tracerEnabled()) tracer(collector, matrices, CONTAINER, cam, start, stil);
                } else {
                    CONTAINER.leeren();
                }
                if (spawnOn) {
                    BlockHighlight.Stil stil = BlockHighlight.stil(spawn.getColor(), spawn.style, spawn.fillOpacity, spawn.glow, 2.0f, spawn.viewDistance.get());
                    SPAWNER.setze(meshes[1]);
                    SPAWNER.zeichne(collector, matrices, cam, stil);
                    if (spawn.tracerEnabled()) tracer(collector, matrices, SPAWNER, cam, start, stil);
                } else {
                    SPAWNER.leeren();
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

    /** Tracer zu allen sichtbaren Bloecken des Kanals, in EINEM Auftrag, mit Ein-/Ausblenden. */
    private static void tracer(SubmitNodeCollector collector, PoseStack matrices, BlockHighlight.Kanal kanal,
                               Vec3 cam, Vec3 start, BlockHighlight.Stil stil) {
        final List<double[]> ziele = new ArrayList<>();
        kanal.fuerJedenBlock(cam, stil, (x, y, z, a) -> { if (ziele.size() < MAX_TRACER) ziele.add(new double[]{x, y, z, a}); });
        if (ziele.isEmpty()) return;
        final int farbe = stil.farbe;
        EspRender.submitLines(collector, matrices, (matrix, lines) -> {
            for (double[] z : ziele) {
                int a = Math.round(((farbe >>> 24) & 0xFF) * (float) z[3]);
                EspRender.drawTracer(matrix, lines, start, new Vec3(z[0], z[1], z[2]), cam,
                        (Math.max(a, 1) << 24) | (farbe & 0xFFFFFF), 2.0f);
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
                    RESULT.set(new BlockHighlight.Mesh[]{BlockHighlight.LEER, BlockHighlight.LEER});
                    lastVersion = -1;
                    continue;
                }

                WorldScan.Snapshot snap = WorldScan.get();
                if (snap.entries.isEmpty()) {
                    BlockHighlight.Mesh[] alt = RESULT.get();
                    if (!alt[0].leer() || !alt[1].leer()) RESULT.set(new BlockHighlight.Mesh[]{BlockHighlight.LEER, BlockHighlight.LEER});
                    continue;
                }
                // Nur neu aufbauen, wenn frische Daten vorliegen.
                if (snap.version == lastVersion) continue;
                lastVersion = snap.version;

                it.unimi.dsi.fastutil.longs.LongArrayList kisten = new it.unimi.dsi.fastutil.longs.LongArrayList();
                it.unimi.dsi.fastutil.longs.LongArrayList spawner = new it.unimi.dsi.fastutil.longs.LongArrayList();
                for (WorldScan.Be be : snap.entries) {
                    if (be.spawner && spawnOn) {
                        spawner.add(be.pos.asLong());
                    } else if (be.inventory && !be.spawner && contOn) {
                        kisten.add(be.pos.asLong());
                    }
                    if (kisten.size() + spawner.size() >= MAX_RESULTS) break;
                }
                RESULT.set(new BlockHighlight.Mesh[]{BlockHighlight.baue(kisten), BlockHighlight.baue(spawner)});
            } catch (InterruptedException ie) {
                return;
            } catch (Throwable t) {
                // Durchlauf ueberspringen.
            }
        }
    }

    private static Module find(Class<? extends Module> type) {
        // Konstante Laufzeit statt die ganze Liste zu durchlaufen.
        return ModuleManager.INSTANCE.get(type);
    }
}
