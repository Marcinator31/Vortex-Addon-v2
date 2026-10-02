package com.vortex.client.hud;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.vortex.client.module.ModuleManager;
import com.vortex.client.module.modules.NewChunksModule;
import it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientChunkEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

/**
 * New Chunks (siehe NewChunksModule).
 *
 * ERKENNUNG (seit 2.40): ueber die Blockpalette jedes geladenen Chunks
 * ({@link ChunkPalette}). Gemessen im echten Spiel (Bot-Test "chunkstudy":
 * Gebiet erzeugen, Welt speichern und neu oeffnen, dann ins Unbekannte
 * fliegen):
 *   - Palette: 1 Fehler bei 437 alten Chunks, ALLE neuen weit draussen erkannt
 *   - bisher (fliessendes Wasser): von 983 neuen Chunks nur 15 als neu, 194
 *     sogar als ALT markiert -- daher "nur jeder zweite Chunk"
 * Regel: mindestens 2 Abschnitte mit unsortierter Palette = neu. Ein
 * einzelner unsortierter Abschnitt kommt auch in alten Chunks vor (der Server
 * hat dort seit dem Laden etwas veraendert: Schnee, Wasser, Laub ...).
 *
 * ANZEIGE: flache, halbtransparente Flaechen auf der eingestellten Hoehe;
 * zusammenhaengende Chunks einer Art bekommen EINEN Umriss (nur Aussenkanten),
 * neue Markierungen blenden weich ein, am Rand der Sichtweite blendet es aus.
 */
public final class NewChunks {

    private NewChunks() {}

    /** Fuer Tests und den Bot-Test: als neu / alt erkannte Chunks. */
    private static final Set<Long> NEU = ConcurrentHashMap.newKeySet();
    private static final Set<Long> ALT = ConcurrentHashMap.newKeySet();
    /** Seit wann markiert (Einblenden), nur Render-Thread. */
    private static final Long2LongOpenHashMap SEIT = new Long2LongOpenHashMap();
    private static Object letzteWelt = null;

    private static boolean an() {
        NewChunksModule m = ModuleManager.INSTANCE.get(NewChunksModule.class);
        return m != null && m.isEnabled();
    }

    public static void register() {
        ClientChunkEvents.CHUNK_LOAD.register((level, chunk) -> {
            try {
                if (an()) pruefe(chunk);
            } catch (Throwable e) {
                com.vortex.client.core.Errors.report("NewChunks.load", e);
            }
        });
        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            if (mc.level != letzteWelt) {
                letzteWelt = mc.level;
                NEU.clear();
                ALT.clear();
            }
        });
        LevelRenderEvents.AFTER_TRANSLUCENT_FEATURES.register(context -> {
            try { zeichnen(context.poseStack(), context.submitNodeCollector()); }
            catch (Throwable e) { com.vortex.client.core.Errors.report("NewChunks.render", e); }
        });
    }

    private static void pruefe(LevelChunk chunk) {
        long c = chunk.getPos().pack();
        // Das erste Urteil bleibt: kommt man spaeter zurueck, ist ein neuer Chunk
        // inzwischen gespeichert und saehe "alt" aus -- er bleibt rot.
        if (NEU.contains(c) || ALT.contains(c)) return;
        ChunkPalette.Befund b = ChunkPalette.pruefe(chunk);
        if (b.abschnitte() < 2) return;                       // keine Aussage (Leere, nur eine Blockart)
        if (b.abschnitte() - b.sortiert() >= 2) NEU.add(c);
        else ALT.add(c);
    }

    // ------------------------------------------------------------------

    private static void zeichnen(PoseStack ms, SubmitNodeCollector col) {
        NewChunksModule m = ModuleManager.INSTANCE.get(NewChunksModule.class);
        if (m == null || !m.isEnabled() || ms == null || col == null) { SEIT.clear(); return; }
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        boolean zeigNeu = m.showNew.get(), zeigAlt = m.showOld.get();
        if (!zeigNeu && !zeigAlt) return;

        float td = mc.getDeltaTracker().getGameTimeDeltaPartialTick(false);
        Vec3 cam = EspRender.cameraOffset(mc, td);
        int r = mc.options.renderDistance().get() + 1;
        int pcx = mc.player.getBlockX() >> 4, pcz = mc.player.getBlockZ() >> 4;
        double sicht = r * 16.0;

        // Art je Chunk im Bereich: 1 = neu, 2 = alt
        Long2ByteOpenHashMap art = new Long2ByteOpenHashMap();
        if (zeigNeu) for (long c : NEU) if (imBereich(c, pcx, pcz, r)) art.put(c, (byte) 1);
        if (zeigAlt) for (long c : ALT) if (imBereich(c, pcx, pcz, r)) art.put(c, (byte) 2);
        if (art.isEmpty()) return;

        long jetzt = System.currentTimeMillis();
        SEIT.keySet().removeIf(c -> !art.containsKey(c));
        for (long c : art.keySet()) if (!SEIT.containsKey(c)) SEIT.put(c, jetzt);

        // Hoehe: weich der Spielerhoehe folgen (kein Springen bei jeder Stufe)
        double zielY = m.followPlayer.get() ? net.minecraft.util.Mth.lerp(td, mc.player.yo, mc.player.getY()) + 0.05 : m.renderY.get() + 0.05;
        if (Double.isNaN(hoehe) || Math.abs(hoehe - zielY) > 24) hoehe = zielY;
        hoehe += (zielY - hoehe) * 0.15;
        final float y = (float) (hoehe - cam.y);

        int neuF = deckend(m.newColor.get()), altF = deckend(m.oldColor.get());
        float fuell = (float) (m.fillOpacity.get() / 100.0);

        // Deckkraft je Chunk: Einblenden x Sichtweite
        final long[] chunks = art.keySet().toLongArray();
        final float[] alpha = new float[chunks.length];
        final int[] farbe = new int[chunks.length];
        for (int i = 0; i < chunks.length; i++) {
            long c = chunks[i];
            double mx = ChunkPos.getX(c) * 16 + 8 - cam.x, mz = ChunkPos.getZ(c) * 16 + 8 - cam.z;
            double d = Math.sqrt(mx * mx + mz * mz);
            float weit = (float) Math.max(0, Math.min(1, (sicht - d) / (sicht * 0.3)));
            float ein = glatt((jetzt - SEIT.get(c)) / 400f);
            alpha[i] = weit * ein;
            farbe[i] = art.get(c) == 1 ? neuF : altF;
        }

        if (fuell > 0.001f) {
            col.submitCustomGeometry(ms, BlockHighlight.fuellung(), (pose, v) -> {
                Matrix4f mat = pose.pose();
                for (int i = 0; i < chunks.length; i++) {
                    float a = alpha[i] * fuell;
                    if (a < 0.003f) continue;
                    float x0 = (float) (ChunkPos.getX(chunks[i]) * 16 - cam.x), z0 = (float) (ChunkPos.getZ(chunks[i]) * 16 - cam.z);
                    int f = farbe[i];
                    float rr = ((f >> 16) & 0xFF) / 255f, gg = ((f >> 8) & 0xFF) / 255f, bb = (f & 0xFF) / 255f;
                    v.addVertex(mat, x0, y, z0).setColor(rr, gg, bb, a);
                    v.addVertex(mat, x0, y, z0 + 16).setColor(rr, gg, bb, a);
                    v.addVertex(mat, x0 + 16, y, z0 + 16).setColor(rr, gg, bb, a);
                    v.addVertex(mat, x0 + 16, y, z0).setColor(rr, gg, bb, a);
                }
            });
        }
        // Umriss: nur Kanten zu einem Nachbarn anderer Art (oder ohne Markierung)
        col.submitCustomGeometry(ms, EspRenderLayer.espLines(), (pose, v) -> {
            Matrix4f mat = pose.pose();
            for (int durchgang = 0; durchgang < 2; durchgang++) {
                float w = durchgang == 0 ? 6f : 2f, faktor = durchgang == 0 ? 0.2f : 0.95f;
                for (int i = 0; i < chunks.length; i++) {
                    float a = alpha[i] * faktor;
                    if (a < 0.003f) continue;
                    long c = chunks[i];
                    int cx = ChunkPos.getX(c), cz = ChunkPos.getZ(c);
                    byte meine = art.get(c);
                    float x0 = (float) (cx * 16 - cam.x), z0 = (float) (cz * 16 - cam.z), x1 = x0 + 16, z1 = z0 + 16;
                    int f = farbe[i];
                    float rr = ((f >> 16) & 0xFF) / 255f, gg = ((f >> 8) & 0xFF) / 255f, bb = (f & 0xFF) / 255f;
                    if (art.get(ChunkPos.pack(cx, cz - 1)) != meine) linie(mat, v, x0, y, z0, x1, y, z0, rr, gg, bb, a, w);
                    if (art.get(ChunkPos.pack(cx, cz + 1)) != meine) linie(mat, v, x0, y, z1, x1, y, z1, rr, gg, bb, a, w);
                    if (art.get(ChunkPos.pack(cx - 1, cz)) != meine) linie(mat, v, x0, y, z0, x0, y, z1, rr, gg, bb, a, w);
                    if (art.get(ChunkPos.pack(cx + 1, cz)) != meine) linie(mat, v, x1, y, z0, x1, y, z1, rr, gg, bb, a, w);
                }
            }
        });
    }

    private static double hoehe = Double.NaN;

    private static boolean imBereich(long c, int pcx, int pcz, int r) {
        return Math.abs(ChunkPos.getX(c) - pcx) <= r && Math.abs(ChunkPos.getZ(c) - pcz) <= r;
    }

    private static void linie(Matrix4f mat, VertexConsumer v, float x1, float y1, float z1, float x2, float y2, float z2,
                              float r, float g, float b, float a, float w) {
        float dx = x2 - x1, dz = z2 - z1;
        float len = (float) Math.sqrt(dx * dx + dz * dz);
        if (len < 1e-6f) return;
        v.addVertex(mat, x1, y1, z1).setColor(r, g, b, a).setNormal(dx / len, 0, dz / len).setLineWidth(w);
        v.addVertex(mat, x2, y2, z2).setColor(r, g, b, a).setNormal(dx / len, 0, dz / len).setLineWidth(w);
    }

    private static float glatt(float t) {
        if (t <= 0f) return 0f;
        if (t >= 1f) return 1f;
        return t * t * (3f - 2f * t);
    }

    private static int deckend(int c) {
        return (c >>> 24) == 0 ? c | 0xFF000000 : c;
    }
}
