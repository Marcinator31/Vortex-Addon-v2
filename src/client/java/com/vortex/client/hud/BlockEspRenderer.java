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
    /** Was gerade angezeigt wird (nur Render-Thread). */
    private static final BlockHighlight.Kanal KANAL = new BlockHighlight.Kanal();

    /**
     * Hoechstens so viele Bloecke anzeigen (die naechsten). Seit 2.44 deutlich mehr:
     * ferne Bloecke werden nur noch als kleines Kreuz gezeichnet (BlockHighlight).
     */
    private static final int MAX_RESULTS = 60000;

    /**
     * Obergrenzen fuers ZEICHNEN. Jeder Kasten erzeugt ein Hilfsobjekt und
     * mehrere Linienzuege -- mehrere tausend pro Bild ergeben hunderttausende
     * Objekte pro Sekunde und koennen Speicher/Grafiktreiber ueberlasten
     * (harter Absturz ohne Crash-Report). Daher: nur Nahes, und gedeckelt.
     */
    private static final double MAX_DRAW_DIST = 96.0;
    /** Tracer: hoechstens so viele (die naechsten zuerst kommen aus dem Ring-Scan). */
    private static final int MAX_TRACER = 500;


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

            // Gemeinsamen Chunk-Scanner sicherstellen (laeuft im Hintergrund).
            ChunkScanner.brauche();

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

    // ------------------------------------------------------------------
    // Suche ueber den gemeinsamen Chunk-Scanner (seit 2.44)
    // ------------------------------------------------------------------

    /** Was die Suche gerade sucht (aus den Einstellungen). */
    private record Auftrag(java.util.Set<net.minecraft.world.level.block.Block> bloecke, int minY, int maxY, boolean frei) {}

    private static final ChunkScanner.Slot<long[]> SLOT = ChunkScanner.anmelden(new ChunkScanner.Job<long[]>() {
        @Override public boolean aktiv() {
            BlockEspModule m = (BlockEspModule) find(BlockEspModule.class);
            return m != null && m.isEnabled() && m.hasAnyBlock();
        }
        @Override public Object signatur() {
            BlockEspModule m = (BlockEspModule) find(BlockEspModule.class);
            java.util.Set<net.minecraft.world.level.block.Block> b = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
            for (String id : m.getEnabledBlocks()) {
                try {
                    Identifier i = Identifier.tryParse(id);
                    if (i == null) continue;
                    var blk = BuiltInRegistries.BLOCK.getValue(i);
                    if (blk != null && blk != net.minecraft.world.level.block.Blocks.AIR) b.add(blk);
                } catch (Throwable ignored) {}
            }
            return new Auftrag(b, m.minY.getInt(), m.maxY.getInt(), m.onlyExposed.get());
        }
        @Override public boolean merken() {
            BlockEspModule m = (BlockEspModule) find(BlockEspModule.class);
            return m != null && m.remember.get();
        }
        @Override public long[] scanne(ClientLevel level, net.minecraft.world.level.chunk.LevelChunk chunk) {
            return sucheImChunk(level, chunk, (Auftrag) SLOT.signatur);
        }
    });

    private static int gebautVersion = -1;
    private static int gebautX = Integer.MIN_VALUE, gebautZ, gebautRange;
    private static long gebautZeit;

    static {
        ChunkScanner.nachher(BlockEspRenderer::anzeigeBauen);
    }

    /** Einen Chunk durchsuchen: nur Abschnitte, deren Palette einen gesuchten Block enthaelt. */
    private static long[] sucheImChunk(ClientLevel level, net.minecraft.world.level.chunk.LevelChunk chunk, Auftrag auf) {
        if (auf == null || auf.bloecke().isEmpty()) return null;
        java.util.function.Predicate<BlockState> treffer = st -> auf.bloecke().contains(st.getBlock());
        var abschnitte = chunk.getSections();
        int bx = chunk.getPos().getMinBlockX(), bz = chunk.getPos().getMinBlockZ();
        LongArrayList out = null;
        for (int i = 0; i < abschnitte.length; i++) {
            var sec = abschnitte[i];
            if (sec == null || sec.hasOnlyAir()) continue;
            int y0 = ChunkScanner.abschnittY(chunk, i);
            if (y0 + 15 < auf.minY() || y0 > auf.maxY()) continue;
            if (!sec.maybeHas(treffer)) continue;        // Palette: kein gesuchter Block -> 4096 Bloecke uebersprungen
            for (int y = 0; y < 16; y++) {
                int wy = y0 + y;
                if (wy < auf.minY() || wy > auf.maxY()) continue;
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        BlockState st = sec.getBlockState(x, y, z);
                        if (!treffer.test(st)) continue;
                        if (auf.frei() && !isExposed(level, bx + x, wy, bz + z)) continue;
                        if (out == null) out = new LongArrayList();
                        out.add(BlockPos.asLong(bx + x, wy, bz + z));
                    }
                }
            }
        }
        return out == null ? null : out.toLongArray();
    }

    /**
     * Anzeige aus den Chunk-Ergebnissen bauen (Worker-Thread): alles in Reichweite,
     * die naechsten zuerst, hoechstens MAX_RESULTS. Nur wenn sich etwas geaendert hat
     * oder der Spieler ein Stueck weiter ist.
     */
    private static void anzeigeBauen() {
        Minecraft client = Minecraft.getInstance();
        BlockEspModule mod = (BlockEspModule) find(BlockEspModule.class);
        if (client.player == null || mod == null || !mod.isEnabled() || !mod.hasAnyBlock()) {
            if (!RESULT.get().leer()) RESULT.set(BlockHighlight.LEER);
            gebautVersion = -1;
            return;
        }
        int px = client.player.getBlockX(), pz = client.player.getBlockZ(), py = client.player.getBlockY();
        int range = mod.range.getInt();
        int v = SLOT.version();
        long jetzt = System.currentTimeMillis();
        boolean bewegt = Math.abs(px - gebautX) > 24 || Math.abs(pz - gebautZ) > 24 || range != gebautRange;
        if (v == gebautVersion && !bewegt) return;
        if (jetzt - gebautZeit < 250 && !bewegt) return;   // nicht oefter als 4x pro Sekunde neu bauen
        gebautVersion = v; gebautX = px; gebautZ = pz; gebautRange = range; gebautZeit = jetzt;

        LongArrayList alle = new LongArrayList();
        long r2 = (long) range * range;
        SLOT.fuerAlle(mod.remember.get(), (chunk, pos) -> {
            int cx = (net.minecraft.world.level.ChunkPos.getX(chunk) << 4) + 8 - px, cz = (net.minecraft.world.level.ChunkPos.getZ(chunk) << 4) + 8 - pz;
            if ((long) cx * cx + (long) cz * cz > (r2 + 512L * range + 65536)) return;   // Chunk ganz ausserhalb
            for (long p : pos) {
                long dx = BlockPos.getX(p) - px, dz = BlockPos.getZ(p) - pz;
                if (dx * dx + dz * dz <= r2) alle.add(p);
            }
        });
        if (alle.size() > MAX_RESULTS) {
            // naechste zuerst
            long[] arr = alle.toLongArray();
            if (arr.length > (1 << 20)) arr = java.util.Arrays.copyOf(arr, 1 << 20);
            long[] schluessel = new long[arr.length];
            for (int i = 0; i < arr.length; i++) {
                long dx = BlockPos.getX(arr[i]) - px, dy = BlockPos.getY(arr[i]) - py, dz = BlockPos.getZ(arr[i]) - pz;
                schluessel[i] = ((dx * dx + dy * dy + dz * dz) << 20) | i;
            }
            java.util.Arrays.sort(schluessel);
            alle.clear();
            for (int i = 0; i < MAX_RESULTS; i++) alle.add(arr[(int) (schluessel[i] & 0xFFFFF)]);
        }
        RESULT.set(BlockHighlight.baue(alle));
    }

    /** Fuer Tests: wie viele Bloecke gerade gefunden sind und wie viele Chunks noch offen. */
    public static int gefunden() { return RESULT.get().anzahl(); }
    public static int offeneChunks() { return SLOT.offen(); }
    public static boolean enthaelt(long pos) {
        for (long p : RESULT.get().bloecke) if (p == pos) return true;
        return false;
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
