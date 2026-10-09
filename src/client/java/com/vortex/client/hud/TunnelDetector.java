package com.vortex.client.hud;

import com.vortex.client.module.Module;
import com.vortex.client.module.ModuleManager;
import com.vortex.client.module.modules.TunnelDetectorModule;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;

/**
 * Cave/Tunnel Detector: findet gerade, von Spielern gegrabene Tunnel.
 *
 * Seit 2.44 ueber den gemeinsamen Chunk-Scanner: jeder geladene Chunk wird einmal
 * durchsucht (und neu, wenn sich darin etwas aendert) -- in der ganzen Sichtweite
 * statt fest 24 Bloecke um den Spieler und nur 24 Ebenen tief.
 *
 * Algorithmus (im Hintergrund-Thread): Fuer jede Position unter "Max Y" wird
 * geprueft, ob dort ein 1x2-Tunnelsegment beginnt -- also Luft auf zwei Hoehen,
 * mit festem Boden, fester Decke und festen Seitenwaenden (genau 1 Block breit).
 * Von einem Startsegment aus wird in X- und in Z-Richtung verfolgt, wie weit
 * sich das gerade fortsetzt. Erreicht die Linie die Mindestlaenge, wird sie als
 * Tunnel markiert (eine AABB ueber die ganze Linie).
 *
 * "Fester Block" = nicht-Luft (robust, ohne fragile isSolid-Abfragen). Die
 * Mindestlaenge haelt die Fehlalarme durch natuerliche Hoehlen gering.
 */
public final class TunnelDetector {

    /** Fertige Anzeige (Worker baut, Render-Thread zeichnet). */
    private static final AtomicReference<BlockHighlight.Mesh> MESH = new AtomicReference<>(BlockHighlight.LEER);
    private static final AtomicReference<List<AABB>> RESULT = new AtomicReference<>(new ArrayList<>());
    private static final int MAX_RESULTS = 5000;
    private static final BlockHighlight.Kanal KANAL = new BlockHighlight.Kanal();

    /** Gefundene Tunnel-Zellen eines Chunks: Position + Richtung (1 = gerade in X, 2 = gerade in Z). */
    private record Zellen(long[] pos, byte[] richtung) {}

    private static final ChunkScanner.Slot<Zellen> SLOT = ChunkScanner.anmelden(new ChunkScanner.Job<Zellen>() {
        @Override public boolean aktiv() {
            Module m = find(TunnelDetectorModule.class);
            return m != null && m.isEnabled();
        }
        @Override public Object signatur() {
            return ((TunnelDetectorModule) find(TunnelDetectorModule.class)).getMaxY();
        }
        @Override public Zellen scanne(ClientLevel level, LevelChunk chunk) {
            return sucheImChunk(level, chunk, (Integer) SLOT.signatur);
        }
    });

    private static int gebautVersion = -1, gebautLaenge = -1;
    private static long gebautZeit;

    static {
        ChunkScanner.nachher(TunnelDetector::anzeigeBauen);
    }

    public static void register() {
        LevelRenderEvents.AFTER_TRANSLUCENT_FEATURES.register(context -> {
            TunnelDetectorModule mod = (TunnelDetectorModule) find(TunnelDetectorModule.class);
            if (mod == null || !mod.isEnabled()) { KANAL.leeren(); return; }

            Minecraft client = Minecraft.getInstance();
            if (client.level == null || client.player == null) { KANAL.leeren(); return; }

            ChunkScanner.brauche();

            PoseStack matrices = context.poseStack();
            SubmitNodeCollector collector = context.submitNodeCollector();
            if (matrices == null || collector == null) return;

            long pvpT0 = System.nanoTime();
            try {
                float tickDelta = client.getDeltaTracker().getGameTimeDeltaPartialTick(false);
                Vec3 cam = EspRender.cameraOffset(client, tickDelta);
                KANAL.setze(MESH.get());
                BlockHighlight.Stil stil = BlockHighlight.stil(mod.getColor(), null, null, null, 2.0f, mod.viewDistance.get());
                stil.fuellDeckkraft = 0.15f;
                KANAL.zeichne(collector, matrices, cam, stil);
            } catch (Throwable pvpErr) {
                com.vortex.client.core.Errors.report("TunnelDetector", pvpErr);
            } finally {
                com.vortex.client.core.Profiler.record("TunnelDetector", System.nanoTime() - pvpT0);
            }
        });
    }

    /** Fuer Tests: gefundene Tunnel. */
    public static List<AABB> tunnel() { return RESULT.get(); }

    // ------------------------------------------------------------------
    // Suche je Chunk (Worker)
    // ------------------------------------------------------------------

    private static Zellen sucheImChunk(ClientLevel level, LevelChunk chunk, Integer maxYEinst) {
        if (maxYEinst == null) return null;
        LevelChunkSection[] sec = chunk.getSections();
        int minY = chunk.getMinY();
        int bx = chunk.getPos().getMinBlockX(), bz = chunk.getPos().getMinBlockZ();
        int top = Math.min(maxYEinst, level.getMaxY() - 2);
        int bottom = level.getMinY() + 1;
        it.unimi.dsi.fastutil.longs.LongArrayList pos = null;
        it.unimi.dsi.fastutil.bytes.ByteArrayList ri = null;
        java.util.function.Predicate<BlockState> luft = BlockState::isAir;
        BlockPos.MutableBlockPos mp = new BlockPos.MutableBlockPos();
        for (int i = 0; i < sec.length; i++) {
            LevelChunkSection s = sec[i];
            int y0 = minY + i * 16;
            if (y0 > top || y0 + 15 < bottom) continue;
            if (s == null || s.hasOnlyAir()) continue;          // reine Luft: kein Boden, keine Decke
            if (!s.maybeHas(luft)) continue;                    // keine Luft -> kein Gang
            for (int y = Math.max(y0, bottom); y <= Math.min(y0 + 15, top); y++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        if (!s.getBlockState(x, y - y0, z).isAir()) continue;
                        if (!luft(chunk, sec, level, mp, x, y + 1, z, minY)) continue;
                        if (!fest(chunk, sec, level, mp, x, y - 1, z, minY) || !fest(chunk, sec, level, mp, x, y + 2, z, minY)) continue;
                        byte r = 0;
                        if (fest(chunk, sec, level, mp, x, y, z - 1, minY) && fest(chunk, sec, level, mp, x, y, z + 1, minY)) r |= 1;
                        if (fest(chunk, sec, level, mp, x - 1, y, z, minY) && fest(chunk, sec, level, mp, x + 1, y, z, minY)) r |= 2;
                        if (r == 0) continue;
                        if (pos == null) { pos = new it.unimi.dsi.fastutil.longs.LongArrayList(); ri = new it.unimi.dsi.fastutil.bytes.ByteArrayList(); }
                        pos.add(BlockPos.asLong(bx + x, y, bz + z));
                        ri.add(r);
                    }
                }
            }
        }
        return pos == null ? null : new Zellen(pos.toLongArray(), ri.toByteArray());
    }

    /** Block lesen: im Chunk direkt aus dem Abschnitt, am Rand ueber die Welt. */
    private static BlockState lies(LevelChunk c, LevelChunkSection[] sec, ClientLevel level, BlockPos.MutableBlockPos mp, int x, int y, int z, int minY) {
        if (x >= 0 && x < 16 && z >= 0 && z < 16) {
            int i = (y - minY) >> 4;
            if (i < 0 || i >= sec.length || sec[i] == null) return net.minecraft.world.level.block.Blocks.AIR.defaultBlockState();
            return sec[i].getBlockState(x, (y - minY) & 15, z);
        }
        mp.set(c.getPos().getMinBlockX() + x, y, c.getPos().getMinBlockZ() + z);
        if (!level.hasChunk(mp.getX() >> 4, mp.getZ() >> 4)) return null;   // Nachbar nicht geladen
        return level.getBlockState(mp);
    }

    private static boolean luft(LevelChunk c, LevelChunkSection[] sec, ClientLevel level, BlockPos.MutableBlockPos mp, int x, int y, int z, int minY) {
        BlockState s = lies(c, sec, level, mp, x, y, z, minY);
        return s != null && s.isAir();
    }

    /** "Fest" = nicht Luft und keine Fluessigkeit; nicht geladen zaehlt NICHT als fest. */
    private static boolean fest(LevelChunk c, LevelChunkSection[] sec, ClientLevel level, BlockPos.MutableBlockPos mp, int x, int y, int z, int minY) {
        BlockState s = lies(c, sec, level, mp, x, y, z, minY);
        return s != null && !s.isAir() && s.getFluidState().isEmpty();
    }

    // ------------------------------------------------------------------
    // Gerade Linien aus den Zellen (Worker)
    // ------------------------------------------------------------------

    private static void anzeigeBauen() {
        TunnelDetectorModule mod = (TunnelDetectorModule) find(TunnelDetectorModule.class);
        if (mod == null || !mod.isEnabled()) {
            if (!MESH.get().leer()) { MESH.set(BlockHighlight.LEER); RESULT.set(new ArrayList<>()); }
            gebautVersion = -1;
            return;
        }
        int v = SLOT.version(), minLen = mod.getMinLength();
        long jetzt = System.currentTimeMillis();
        if (v == gebautVersion && minLen == gebautLaenge) return;
        if (jetzt - gebautZeit < 500 && minLen == gebautLaenge) return;
        gebautVersion = v; gebautLaenge = minLen; gebautZeit = jetzt;

        it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap alle = new it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap();
        SLOT.fuerAlle(false, (k, z) -> { for (int i = 0; i < z.pos().length; i++) alle.put(z.pos()[i], z.richtung()[i]); });

        List<AABB> found = new ArrayList<>();
        it.unimi.dsi.fastutil.longs.LongArrayList zellen = new it.unimi.dsi.fastutil.longs.LongArrayList();
        for (var e : alle.long2ByteEntrySet()) {
            long p = e.getLongKey();
            byte r = e.getByteValue();
            int x = BlockPos.getX(p), y = BlockPos.getY(p), z = BlockPos.getZ(p);
            for (int achse = 1; achse <= 2; achse <<= 1) {
                if ((r & achse) == 0) continue;
                int dx = achse == 1 ? 1 : 0, dz = achse == 2 ? 1 : 0;
                // Nur am Anfang einer Linie starten
                if ((alle.get(BlockPos.asLong(x - dx, y, z - dz)) & achse) != 0) continue;
                int len = 1;
                while ((alle.get(BlockPos.asLong(x + dx * len, y, z + dz * len)) & achse) != 0) len++;
                if (len < minLen) continue;
                found.add(new AABB(x, y, z, x + (dx == 1 ? len : 1), y + 2.0, z + (dz == 1 ? len : 1)));
                for (int i = 0; i < len; i++) {
                    zellen.add(BlockPos.asLong(x + dx * i, y, z + dz * i));
                    zellen.add(BlockPos.asLong(x + dx * i, y + 1, z + dz * i));
                }
                if (found.size() >= MAX_RESULTS) break;
            }
            if (found.size() >= MAX_RESULTS) break;
        }
        RESULT.set(found);
        MESH.set(BlockHighlight.baue(zellen));
    }

    private static Module find(Class<? extends Module> type) {
        return ModuleManager.INSTANCE.get(type);
    }
}
