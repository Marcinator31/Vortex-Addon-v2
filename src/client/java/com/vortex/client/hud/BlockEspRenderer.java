package com.vortex.client.hud;

import com.vortex.client.module.Module;
import com.vortex.client.module.ModuleManager;
import com.vortex.client.module.modules.BlockEspModule;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import java.util.concurrent.atomic.AtomicReference;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.SubmitNodeCollector;

/**
 * Block-ESP mit Outlines um ausgewaehlte Bloecke.
 *
 * PERFORMANCE -- der entscheidende Punkt fuer "keine FPS-Drops": Die Welt-Suche
 * laeuft in einem EIGENEN HINTERGRUND-THREAD, nicht im Render-Thread. Der
 * Render-Thread zeichnet nur die fertige AABB-Liste, die der Worker bereitstellt.
 * Dadurch hat das (teure) Scannen NULL Einfluss auf die Framerate -- egal wie
 * gross die Reichweite ist.
 *
 * Datenuebergabe: Der Worker baut eine neue Liste und legt sie atomar in eine
 * AtomicReference. Der Render-Thread liest sie nur. Kein gemeinsames
 * Veraendern, daher keine Sperren noetig.
 *
 * Welt-Lesen aus einem Fremd-Thread ist nicht offiziell unterstuetzt, in der
 * Praxis fuer reines Lesen aber tragbar; alle Zugriffe sind in try/catch
 * gekapselt, damit ein seltener Nebenlaeufigkeitsfehler nichts kaputt macht.
 *
 * Seit 2.38: Aussehen und Zeichnen ueber {@link BlockHighlight} (zusammen-
 * haengende Bloecke als eine Form, Fuellung, Glow, Ein-/Ausblenden). Der Worker
 * liefert fertige Geometrie. Und kein Flackern mehr: bis 2.37 begann jeder
 * Scan mit einer leeren Liste und zeigte sie Ring fuer Ring -- ferne Bloecke
 * verschwanden dabei alle 60 ms kurz. Jetzt bleiben sie stehen, bis der Scan
 * ihren Ring erreicht.
 */
public final class BlockEspRenderer {

    // Vom Worker befuelltes, vom Render-Thread gelesenes Ergebnis.
    private static final AtomicReference<BlockHighlight.Mesh> RESULT =
            new AtomicReference<>(BlockHighlight.LEER);
    /** Letztes vollstaendiges Scan-Ergebnis (nur der Worker liest/schreibt es). */
    private static LongArrayList letzterScan = new LongArrayList();
    /** Was gerade angezeigt wird (nur Render-Thread). */
    private static final BlockHighlight.Kanal KANAL = new BlockHighlight.Kanal();

    // Maximale Anzahl Outlines (schuetzt sowohl Scan als auch Zeichnen).
    private static final int MAX_RESULTS = 4000;

    /**
     * Obergrenzen fuers ZEICHNEN. Jeder Kasten erzeugt ein Hilfsobjekt und
     * mehrere Linienzuege -- mehrere tausend pro Bild ergeben hunderttausende
     * Objekte pro Sekunde und koennen Speicher/Grafiktreiber ueberlasten
     * (harter Absturz ohne Crash-Report). Daher: nur Nahes, und gedeckelt.
     */
    private static final double MAX_DRAW_DIST = 96.0;
    /** Tracer: hoechstens so viele (die naechsten zuerst kommen aus dem Ring-Scan). */
    private static final int MAX_TRACER = 500;

    private static volatile boolean running = false;
    private static Thread worker;

    public static void register() {
        LevelRenderEvents.AFTER_TRANSLUCENT_FEATURES.register(context -> {
            long pvpT0 = System.nanoTime();
            try {
            BlockEspModule mod = (BlockEspModule) find(BlockEspModule.class);
            if (mod == null || !mod.isEnabled() || !mod.hasAnyBlock()) {
                KANAL.leeren();
                return;
            }

            Minecraft client = Minecraft.getInstance();
            if (client.level == null || client.player == null) { KANAL.leeren(); return; }

            PoseStack matrices = context.poseStack();
            SubmitNodeCollector collector = context.submitNodeCollector();
            if (matrices == null || collector == null) return;

            // Worker sicherstellen (laeuft dauerhaft, scannt im Hintergrund).
            ensureWorker();

            try {
                float tickDelta = client.getDeltaTracker().getGameTimeDeltaPartialTick(false);
                // Echte Render-Kamera (auch mit Freecam).
                Vec3 cam = EspRender.cameraOffset(client, tickDelta);

                double drawDist = mod.drawDistance.get();
                if (drawDist <= 0) drawDist = MAX_DRAW_DIST;
                BlockHighlight.Stil stil = BlockHighlight.stil(mod.getEspColor(), mod.style, mod.fillOpacity,
                        mod.glow, mod.lineWidth.getFloat(), drawDist);

                // Nur die fertige Geometrie zeichnen -- KEINE Suche hier.
                KANAL.setze(RESULT.get());
                KANAL.zeichne(collector, matrices, cam, stil);

                // Optional: Tracer von der Sicht zu den Bloecken -- alle in EINEM Auftrag,
                // mit derselben Ein-/Ausblendung wie die Bloecke.
                if (mod.tracersEnabled()) {
                    int tColor = mod.getTracerColor();
                    if ((tColor >>> 24) == 0) tColor = 0xFF000000 | tColor;
                    final Vec3 start = pvpclient$tracerStart(client, cam, tickDelta);
                    final java.util.List<double[]> ziele = new java.util.ArrayList<>();
                    KANAL.fuerJedenBlock(cam, stil, (x, y, z, a) -> {
                        if (ziele.size() < MAX_TRACER) ziele.add(new double[]{x, y, z, a});
                    });
                    if (!ziele.isEmpty()) {
                        final Vec3 tracerCam = cam;
                        final int tracerColor = tColor;
                        final float tracerWidth = mod.lineWidth.getFloat();
                        EspRender.submitLines(collector, matrices, (matrix, lines) -> {
                            for (double[] z : ziele) {
                                int a = Math.round(((tracerColor >>> 24) & 0xFF) * (float) z[3]);
                                EspRender.drawTracer(matrix, lines, start, new Vec3(z[0], z[1], z[2]), tracerCam,
                                        (Math.max(a, 1) << 24) | (tracerColor & 0xFFFFFF), tracerWidth);
                            }
                        });
                    }
                }
            } catch (Throwable pvpErr) {
                com.vortex.client.core.Errors.report("BlockEsp", pvpErr);
            }
                    } finally {
                com.vortex.client.core.Profiler.record("BlockEsp",
                        System.nanoTime() - pvpT0);
            }
        });
    }

    /** Startet den Hintergrund-Worker einmalig. */
    private static void ensureWorker() {
        if (running) return;
        running = true;
        worker = new Thread(BlockEspRenderer::workerLoop, "vortexclient-blockesp");
        worker.setDaemon(true);
        worker.start();
    }

    /**
     * Endlosschleife im Hintergrund: scannt die Welt um den Spieler und legt das
     * Ergebnis ab. Laeuft mit kurzer Pause zwischen den Durchlaeufen, damit der
     * Thread nicht durchdreht.
     */
    private static void workerLoop() {
        while (true) {
            try {
                Thread.sleep(60); // ~16 Scans/Sekunde maximal

                Minecraft client = Minecraft.getInstance();
                BlockEspModule mod = (BlockEspModule) find(BlockEspModule.class);
                if (client == null || mod == null || !mod.isEnabled()
                        || !mod.hasAnyBlock()) {
                    if (!RESULT.get().leer()) RESULT.set(BlockHighlight.LEER);
                    letzterScan = new LongArrayList();
                    continue;
                }
                ClientLevel world = client.level;
                if (world == null || client.player == null) {
                    if (!RESULT.get().leer()) RESULT.set(BlockHighlight.LEER);
                    letzterScan = new LongArrayList();
                    continue;
                }

                int cx = (int) Math.floor(client.player.getX());
                int cy = (int) Math.floor(client.player.getY());
                int cz = (int) Math.floor(client.player.getZ());
                int range = mod.range.getInt();

                LongArrayList found = scan(world, mod, cx, cy, cz, range);
                if (found == null) continue;                 // abgebrochen
                letzterScan = found;
                RESULT.set(BlockHighlight.baue(found)); // atomar uebergeben
            } catch (InterruptedException ie) {
                return;
            } catch (Throwable t) {
                // Nebenlaeufigkeitsfehler o.ae. -> Durchlauf ueberspringen.
            }
        }
    }

    /**
     * Durchsucht den Bereich RINGFOERMIG von innen nach aussen und veroeffentlicht
     * das Zwischenergebnis nach jedem Ring. Dadurch erscheinen nahe Bloecke quasi
     * sofort, ferne kommen Ring fuer Ring nach -- statt erst nach einem
     * kompletten (bei grosser Reichweite sekundenlangen) Scan alles auf einmal.
     */
    private static LongArrayList scan(ClientLevel world, BlockEspModule mod,
                                  int cx, int cy, int cz, int range) {
        LongArrayList out = new LongArrayList();
        final LongArrayList vorher = letzterScan;

        int worldMin = world.getMinY();
        int worldMax = world.getMaxY();

        // Hoehenbereich: Schnittmenge aus Welt, Einstellung und einer vertikalen
        // Begrenzung um den Spieler (sonst waechst das Suchvolumen ins Uferlose).
        int vRange = Math.min(range, 64);
        int yStart = Math.max(Math.max(cy - vRange, worldMin), mod.minY.getInt());
        int yEnd = Math.min(Math.min(cy + vRange, worldMax), mod.maxY.getInt());
        if (yStart > yEnd) return out;

        boolean onlyExposed = mod.onlyExposed.get();

        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

        // Merker fuer die Chunk-Pruefung: nicht geladene Chunks liefern bei
        // getBlockState einfach Luft -- Bloecke darin wuerden also stillschweigend
        // uebersehen. Die Pruefung passiert pro Chunk (nicht pro Block), deshalb
        // wird das letzte Ergebnis gemerkt.
        int lastChunkX = Integer.MIN_VALUE, lastChunkZ = Integer.MIN_VALUE;
        boolean lastChunkLoaded = false;

        // Ring 0 = nur die Mittelspalte, dann immer groessere Quadrat-Ringe.
        //
        // WICHTIG: Es wird gezielt nur der RAND jedes Rings abgelaufen. Frueher
        // lief die Schleife ueber die ganze Flaeche und verwarf das Innere wieder
        // -- bei Reichweite 64 waren das rund 366.000 statt 16.600 Spalten, also
        // 22-mal so viel Arbeit. Dadurch dauerte ein Durchlauf lange und die
        // angezeigten Bloecke hinkten der Wirklichkeit hinterher.
        for (int r = 0; r <= range; r++) {
            int steps = (r == 0) ? 1 : 8 * r;   // Anzahl Felder auf dem Rand
            for (int i = 0; i < steps; i++) {
                int dx, dz;
                if (r == 0) {
                    dx = 0; dz = 0;
                } else {
                    // Rand im Uhrzeigersinn ablaufen: oben, rechts, unten, links.
                    int side = i / (2 * r);        // 0..3
                    int off = i % (2 * r);         // Position auf dieser Seite
                    switch (side) {
                        case 0:  dx = -r + off; dz = -r;        break;
                        case 1:  dx = r;        dz = -r + off;  break;
                        case 2:  dx = r - off;  dz = r;         break;
                        default: dx = -r;       dz = r - off;   break;
                    }
                }
                {
                    int x = cx + dx;
                    int z = cz + dz;

                    // Chunk geladen? Sonst die ganze Spalte ueberspringen --
                    // spart Arbeit und macht klar, dass hier nicht "nichts" ist,
                    // sondern schlicht keine Daten vorliegen.
                    int chX = x >> 4, chZ = z >> 4;
                    if (chX != lastChunkX || chZ != lastChunkZ) {
                        lastChunkX = chX;
                        lastChunkZ = chZ;
                        try {
                            lastChunkLoaded = world.hasChunk(chX, chZ);
                        } catch (Throwable t) {
                            lastChunkLoaded = false;
                        }
                    }
                    if (!lastChunkLoaded) continue;

                    for (int y = yStart; y <= yEnd; y++) {
                        pos.set(x, y, z);
                        BlockState state;
                        try {
                            state = world.getBlockState(pos);
                        } catch (Throwable t) {
                            continue; // Chunk evtl. gerade entladen
                        }
                        if (state.isAir()) continue;
                        Identifier id = BuiltInRegistries.BLOCK.getKey(state.getBlock());
                        if (!mod.isBlockEnabled(id)) continue;
                        // Optional: nur Bloecke, die an mindestens einer Seite
                        // frei liegen. Die Pruefung passiert erst NACH dem Filter,
                        // laeuft also nur fuer die wenigen Treffer.
                        if (onlyExposed && !isExposed(world, x, y, z)) continue;
                        out.add(BlockPos.asLong(x, y, z));
                        if (out.size() >= MAX_RESULTS) return out;
                    }
                }
            }
            // Nach jedem Ring das bisherige Ergebnis sichtbar machen -> nahe
            // Bloecke erscheinen sofort, der Rest fuellt sich auf. Nicht nach
            // JEDEM Ring (das waere bei grosser Reichweite viel Kopierarbeit),
            // sondern alle paar Ringe -- das reicht fuers Gefuehl von "instant".
            if ((r & 7) == 0) {
                // Zwischenstand: das Neue bis Ring r, dahinter das Ergebnis des
                // letzten Scans -- so verschwindet nichts, was noch nicht neu
                // geprueft wurde (bis 2.37 flackerten ferne Bloecke hier).
                LongArrayList zeigen = new LongArrayList(out);
                for (int i = 0; i < vorher.size(); i++) {
                    long p = vorher.getLong(i);
                    int ring = Math.max(Math.abs(BlockPos.getX(p) - cx), Math.abs(BlockPos.getZ(p) - cz));
                    if (ring > r) zeigen.add(p);
                }
                RESULT.set(BlockHighlight.baue(zeigen));
                // Gegen Freezes: Locks zwischendurch freigeben. getBlockState
                // greift live auf Chunk-Daten zu; ohne Pause haelt ein grosser
                // Scan (hohe Reichweite) die Locks zu lange und das Spiel
                // ruckelt periodisch.
                try { Thread.sleep(3); } catch (InterruptedException ie) { return null; }
            }
        }
        return out;
    }

    /**
     * Liegt der Block an mindestens einer der sechs Seiten frei (Luft daneben)?
     * Komplett eingeschlossene Bloecke koennen damit ausgeblendet werden.
     */
    private static boolean isExposed(ClientLevel world, int x, int y, int z) {
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        int[][] dirs = {{1,0,0},{-1,0,0},{0,1,0},{0,-1,0},{0,0,1},{0,0,-1}};
        for (int[] d : dirs) {
            p.set(x + d[0], y + d[1], z + d[2]);
            try {
                if (world.getBlockState(p).isAir()) return true;
            } catch (Throwable t) {
                return true; // im Zweifel anzeigen
            }
        }
        return false;
    }

    /**
     * Startpunkt der Tracer: ein kleines Stueck vor der Kamera in Blickrichtung.
     * So scheinen die Linien aus dem Fadenkreuz zu kommen statt aus dem Auge.
     * Bei aktiver Freecam nutzen wir deren Blickrichtung.
     */
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
        // Blickrichtungs-Vektor (Minecraft-Konvention).
        double fx = -Math.sin(yawRad) * Math.cos(pitchRad);
        double fy = -Math.sin(pitchRad);
        double fz = Math.cos(yawRad) * Math.cos(pitchRad);
        // 0.5 Bloecke vor die Kamera setzen.
        return new Vec3(cam.x + fx * 0.5, cam.y + fy * 0.5, cam.z + fz * 0.5);
    }

    private static Module find(Class<? extends Module> type) {
        // Konstante Laufzeit statt die ganze Liste zu durchlaufen.
        return ModuleManager.INSTANCE.get(type);
    }
}
