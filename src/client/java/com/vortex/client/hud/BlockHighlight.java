package com.vortex.client.hud;

import com.mojang.blaze3d.pipeline.RenderPipeline;
//#if 26
import com.mojang.blaze3d.pipeline.DepthStencilState;
import com.mojang.blaze3d.platform.CompareOp;
//#else
//$ import com.mojang.blaze3d.platform.DepthTestFunction;
//#endif
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

/**
 * Neues Aussehen der Block-ESPs (Block-ESP, Container ESP, Spawner ESP,
 * Tunnel Detector) -- seit Addon 2.38.
 *
 * VORHER: jeder Block ein eigener Drahtkasten. Eine Erzader war ein Gitter aus
 * Einzelkaesten, jeder Kasten ein eigener Zeichenauftrag (bis 500 pro Bild),
 * und bei jedem neuen Scan sprangen Bloecke hart an und aus.
 *
 * JETZT:
 *   - Zusammenhaengende Bloecke sind EINE Form: gezeichnet werden nur die
 *     Aussenkanten (konvexe Ecken und einspringende Ecken), keine Kanten
 *     zwischen zwei markierten Bloecken.
 *   - Halbtransparente Fuellung nur auf den Aussenseiten.
 *   - "Glow": unter jeder Linie eine breite, blasse Linie.
 *   - Neue Bloecke blenden ein, verschwundene blenden aus, am Rand der
 *     Sichtweite wird es weich schwaecher statt hart abgeschnitten.
 *   - Alles in ZWEI Zeichenauftraegen (Linien + Flaechen) statt hunderten.
 *
 * Die Geometrie (Kanten, Flaechen) baut der jeweilige Hintergrund-Thread
 * ({@link #baue}); der Render-Thread rechnet pro Bild nur noch die
 * Durchsichtigkeit je Block und schreibt Eckpunkte.
 */
public final class BlockHighlight {

    private BlockHighlight() {}

    // ------------------------------------------------------------------
    // Flaechen ohne Tiefentest (wie die ESP-Linien: durch Waende sichtbar)
    // ------------------------------------------------------------------

    private static RenderType fuellung;

    /** Erst beim ersten Zeichnen anlegen (wie EspRenderLayer). */
    private static RenderType fuellung() {
        if (fuellung == null) {
            RenderPipeline pipeline = RenderPipelines.register(
                    RenderPipeline.builder(RenderPipelines.DEBUG_FILLED_SNIPPET)
                            .withLocation(Identifier.fromNamespaceAndPath("vortexplusaddon", "pipeline/esp_fill"))
                            .withCull(false)
                            //#if 26
                            .withDepthStencilState(new DepthStencilState(CompareOp.ALWAYS_PASS, false))
                            //#else
                            //$ .withDepthTestFunction(DepthTestFunction.NO_DEPTH_TEST).withDepthWrite(false)
                            //#endif
                            .build());
            fuellung = RenderType.create("vortexplusaddon_esp_fill", RenderSetup.builder(pipeline).createRenderSetup());
        }
        return fuellung;
    }

    // ------------------------------------------------------------------
    // Geometrie (Hintergrund-Thread)
    // ------------------------------------------------------------------

    /** Fertige Geometrie fuer eine Menge Bloecke. Unveraenderlich. */
    public static final class Mesh {
        /** Ursprung; alle float-Koordinaten sind relativ dazu (Genauigkeit weit draussen). */
        final int ox, oy, oz;
        final long[] bloecke;
        final float[] kanten;        // 6 Werte je Kante
        final int[] kanteBlock;
        final float[] flaechen;      // 12 Werte je Flaeche (4 Ecken)
        final int[] flaecheBlock;

        Mesh(int ox, int oy, int oz, long[] bloecke, float[] kanten, int[] kanteBlock, float[] flaechen, int[] flaecheBlock) {
            this.ox = ox; this.oy = oy; this.oz = oz;
            this.bloecke = bloecke;
            this.kanten = kanten; this.kanteBlock = kanteBlock;
            this.flaechen = flaechen; this.flaecheBlock = flaecheBlock;
        }

        public int anzahl() { return bloecke.length; }
        public boolean leer() { return bloecke.length == 0; }
    }

    public static final Mesh LEER = new Mesh(0, 0, 0, new long[0], new float[0], new int[0], new float[0], new int[0]);

    /**
     * Baut Kanten und Flaechen fuer die Bloecke (BlockPos.asLong).
     *
     * Kante zeichnen? Jede Blockkante liegt zwischen zwei Seiten (Richtungen
     * d1, d2). Markiert sind evtl. die Nachbarn n1 (d1), n2 (d2) und n12
     * (diagonal). Gezeichnet wird bei
     *   - keinem Nachbarn (aussen liegende Ecke) oder
     *   - beiden Nachbarn ohne den diagonalen (einspringende Ecke).
     * Ist genau ein Nachbar markiert, liegt die Kante mitten in einer Flaeche
     * der Form -- dann nicht. Doppelte Kanten (Bloecke, die sich nur an einer
     * Kante beruehren) werden einmal gezeichnet.
     */
    public static Mesh baue(LongArrayList positionen) {
        int n = positionen.size();
        if (n == 0) return LEER;
        LongOpenHashSet menge = new LongOpenHashSet(positionen);
        long[] bloecke = menge.toLongArray();
        long erster = bloecke[0];
        int ox = BlockPos.getX(erster), oy = BlockPos.getY(erster), oz = BlockPos.getZ(erster);

        FloatListe kanten = new FloatListe(bloecke.length * 18);
        IntListe kanteBlock = new IntListe(bloecke.length * 3);
        FloatListe flaechen = new FloatListe(bloecke.length * 24);
        IntListe flaecheBlock = new IntListe(bloecke.length * 2);
        LongOpenHashSet[] gezeichnet = {new LongOpenHashSet(), new LongOpenHashSet(), new LongOpenHashSet()};

        for (int i = 0; i < bloecke.length; i++) {
            long p = bloecke[i];
            int x = BlockPos.getX(p), y = BlockPos.getY(p), z = BlockPos.getZ(p);
            float rx = x - ox, ry = y - oy, rz = z - oz;

            // 12 Kanten: je Achse 4 (Vorzeichen der beiden anderen Achsen)
            for (int achse = 0; achse < 3; achse++) {
                for (int s1 = -1; s1 <= 1; s1 += 2) {
                    for (int s2 = -1; s2 <= 1; s2 += 2) {
                        int[] d1 = new int[3], d2 = new int[3];
                        int b = (achse + 1) % 3, c = (achse + 2) % 3;
                        d1[b] = s1;
                        d2[c] = s2;
                        boolean n1 = menge.contains(BlockPos.asLong(x + d1[0], y + d1[1], z + d1[2]));
                        boolean n2 = menge.contains(BlockPos.asLong(x + d2[0], y + d2[1], z + d2[2]));
                        boolean zeichnen;
                        if (!n1 && !n2) zeichnen = true;
                        else if (n1 && n2) zeichnen = !menge.contains(BlockPos.asLong(x + d1[0] + d2[0], y + d1[1] + d2[1], z + d1[2] + d2[2]));
                        else zeichnen = false;
                        if (!zeichnen) continue;
                        // Untere Ecke der Kante (ganzzahlig) -- Schluessel gegen Doppelte
                        int[] e = {x, y, z};
                        if (s1 > 0) e[b]++;
                        if (s2 > 0) e[c]++;
                        if (!gezeichnet[achse].add(BlockPos.asLong(e[0], e[1], e[2]))) continue;
                        float ax = e[0] - ox, ay = e[1] - oy, az = e[2] - oz;
                        kanten.add(ax, ay, az,
                                ax + (achse == 0 ? 1 : 0), ay + (achse == 1 ? 1 : 0), az + (achse == 2 ? 1 : 0));
                        kanteBlock.add(i);
                    }
                }
            }

            // Aussenseiten fuellen
            if (!menge.contains(BlockPos.asLong(x, y + 1, z))) { flaechen.add(rx, ry + 1, rz, rx + 1, ry + 1, rz, rx + 1, ry + 1, rz + 1, rx, ry + 1, rz + 1); flaecheBlock.add(i); }
            if (!menge.contains(BlockPos.asLong(x, y - 1, z))) { flaechen.add(rx, ry, rz, rx, ry, rz + 1, rx + 1, ry, rz + 1, rx + 1, ry, rz); flaecheBlock.add(i); }
            if (!menge.contains(BlockPos.asLong(x + 1, y, z))) { flaechen.add(rx + 1, ry, rz, rx + 1, ry, rz + 1, rx + 1, ry + 1, rz + 1, rx + 1, ry + 1, rz); flaecheBlock.add(i); }
            if (!menge.contains(BlockPos.asLong(x - 1, y, z))) { flaechen.add(rx, ry, rz, rx, ry + 1, rz, rx, ry + 1, rz + 1, rx, ry, rz + 1); flaecheBlock.add(i); }
            if (!menge.contains(BlockPos.asLong(x, y, z + 1))) { flaechen.add(rx, ry, rz + 1, rx, ry + 1, rz + 1, rx + 1, ry + 1, rz + 1, rx + 1, ry, rz + 1); flaecheBlock.add(i); }
            if (!menge.contains(BlockPos.asLong(x, y, z - 1))) { flaechen.add(rx, ry, rz, rx + 1, ry, rz, rx + 1, ry + 1, rz, rx, ry + 1, rz); flaecheBlock.add(i); }
        }
        return new Mesh(ox, oy, oz, bloecke, kanten.fertig(), kanteBlock.fertig(), flaechen.fertig(), flaecheBlock.fertig());
    }

    // ------------------------------------------------------------------
    // Zeichnen (Render-Thread) -- mit Ein-/Ausblenden
    // ------------------------------------------------------------------

    /** Aussehen. */
    public static final class Stil {
        public int farbe = 0xFF00FFFF;
        public boolean linien = true;
        public boolean flaechen = true;
        public boolean glow = true;
        public float linienBreite = 2.0f;
        /** 0..1 */
        public float fuellDeckkraft = 0.2f;
        /** Bis wohin gezeichnet wird; die letzten 25 % blenden aus. */
        public double sichtweite = 96;
    }

    /** Stil aus den ueblichen Modul-Einstellungen ("Style": Outline + Fill / Outline / Fill). */
    public static Stil stil(int farbe, com.vortex.client.core.setting.ModeSetting style,
                            com.vortex.client.core.setting.NumberSetting fillOpacity,
                            com.vortex.client.core.setting.BooleanSetting glow, float breite, double sichtweite) {
        Stil s = new Stil();
        s.farbe = (farbe >>> 24) == 0 ? (0xFF000000 | farbe) : farbe;
        int modus = style == null ? 0 : style.getIndex();
        s.linien = modus != 2;
        s.flaechen = modus != 1;
        s.fuellDeckkraft = fillOpacity == null ? 0.2f : (float) (fillOpacity.get() / 100.0);
        s.glow = glow == null || glow.get();
        s.linienBreite = breite;
        s.sichtweite = sichtweite;
        return s;
    }

    private static final long EINBLENDEN_MS = 220, AUSBLENDEN_MS = 260;
    private static final int MAX_GEISTER = 400;

    /**
     * Zustand eines ESP-Kanals im Render-Thread: was gerade angezeigt wird,
     * seit wann welcher Block da ist, welche gerade ausblenden.
     */
    public static final class Kanal {
        private Mesh mesh = LEER;
        private final Long2LongOpenHashMap seit = new Long2LongOpenHashMap();
        private final List<long[]> geister = new ArrayList<>();   // {pos, wegSeit}

        /** Neue Geometrie uebernehmen (nur wenn sie sich geaendert hat -- Referenzvergleich). */
        public void setze(Mesh neu) {
            if (neu == null) neu = LEER;
            if (neu == mesh) return;
            long jetzt = System.currentTimeMillis();
            LongOpenHashSet neuMenge = new LongOpenHashSet(neu.bloecke);
            // Verschwunden -> ausblenden
            for (long p : mesh.bloecke) {
                if (!neuMenge.contains(p)) {
                    seit.remove(p);
                    if (geister.size() < MAX_GEISTER) geister.add(new long[]{p, jetzt});
                }
            }
            // Neu -> einblenden (wieder aufgetaucht: aus den Geistern nehmen)
            for (long p : neu.bloecke) {
                if (!seit.containsKey(p)) seit.put(p, jetzt);
            }
            if (!geister.isEmpty()) geister.removeIf(g -> neuMenge.contains(g[0]));
            mesh = neu;
        }

        /** Alles sofort weg (Modul aus, Weltwechsel). */
        public void leeren() {
            mesh = LEER;
            seit.clear();
            geister.clear();
        }

        public boolean nichtsZuZeichnen() {
            return mesh.leer() && geister.isEmpty();
        }

        /** Mittelpunkte der sichtbaren Bloecke mit ihrer Deckkraft -- fuer Tracer. */
        public void fuerJedenBlock(Vec3 cam, Stil stil, BlockAktion aktion) {
            long jetzt = System.currentTimeMillis();
            for (long p : mesh.bloecke) {
                double x = BlockPos.getX(p) + 0.5, y = BlockPos.getY(p) + 0.5, z = BlockPos.getZ(p) + 0.5;
                float a = deckkraft(p, x, y, z, cam, stil, jetzt);
                if (a > 0.01f) aktion.mach(x, y, z, a);
            }
        }

        private float deckkraft(long p, double x, double y, double z, Vec3 cam, Stil stil, long jetzt) {
            double dx = x - cam.x, dy = y - cam.y, dz = z - cam.z;
            double d = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (d > stil.sichtweite) return 0f;
            double rand = stil.sichtweite * 0.25;
            float weit = (float) Math.min(1.0, (stil.sichtweite - d) / rand);
            float ein = glatt((jetzt - seit.getOrDefault(p, jetzt - EINBLENDEN_MS)) / (float) EINBLENDEN_MS);
            return weit * ein;
        }

        /** Zeichnet Form, Fuellung und ausblendende Bloecke -- in zwei Auftraegen. */
        public void zeichne(SubmitNodeCollector collector, PoseStack ms, Vec3 cam, Stil stil) {
            long jetzt = System.currentTimeMillis();
            final Mesh m = mesh;
            // Deckkraft je Block (einmal pro Bild)
            final float[] alpha = new float[m.bloecke.length];
            boolean irgendwas = false;
            for (int i = 0; i < alpha.length; i++) {
                long p = m.bloecke[i];
                alpha[i] = deckkraft(p, BlockPos.getX(p) + 0.5, BlockPos.getY(p) + 0.5, BlockPos.getZ(p) + 0.5, cam, stil, jetzt);
                if (alpha[i] > 0.004f) irgendwas = true;
            }
            // Ausblendende Bloecke: einzeln, als Kasten
            geister.removeIf(g -> jetzt - g[1] > AUSBLENDEN_MS);
            final long[] gp = new long[geister.size()];
            final float[] ga = new float[geister.size()];
            for (int i = 0; i < gp.length; i++) {
                long[] g = geister.get(i);
                gp[i] = g[0];
                long p = g[0];
                float weg = 1f - glatt((jetzt - g[1]) / (float) AUSBLENDEN_MS);
                float d = deckkraft(p, BlockPos.getX(p) + 0.5, BlockPos.getY(p) + 0.5, BlockPos.getZ(p) + 0.5, cam, stil, jetzt);
                ga[i] = weg * d;
                if (ga[i] > 0.004f) irgendwas = true;
            }
            if (!irgendwas) return;

            float r = ((stil.farbe >> 16) & 0xFF) / 255f, g = ((stil.farbe >> 8) & 0xFF) / 255f, b = (stil.farbe & 0xFF) / 255f;
            float grundA = ((stil.farbe >>> 24) & 0xFF) / 255f;
            if (grundA == 0f) grundA = 1f;
            final float fa = grundA;
            final float breite = stil.linienBreite;

            // Ursprung: der des Mesh -- ist es leer (alles blendet aus), der
            // erste ausblendende Block (float-Genauigkeit weit draussen).
            final int ox = m.leer() && gp.length > 0 ? BlockPos.getX(gp[0]) : m.ox;
            final int oy = m.leer() && gp.length > 0 ? BlockPos.getY(gp[0]) : m.oy;
            final int oz = m.leer() && gp.length > 0 ? BlockPos.getZ(gp[0]) : m.oz;
            ms.pushPose();
            try {
                ms.translate(ox - cam.x, oy - cam.y, oz - cam.z);
                if (stil.flaechen && stil.fuellDeckkraft > 0.001f) {
                    final float fd = stil.fuellDeckkraft;
                    collector.submitCustomGeometry(ms, fuellung(), (pose, v) -> {
                        Matrix4f mat = pose.pose();
                        float[] f = m.flaechen;
                        for (int i = 0, k = 0; i < m.flaecheBlock.length; i++, k += 12) {
                            float a = alpha[m.flaecheBlock[i]] * fd * fa;
                            if (a <= 0.003f) continue;
                            for (int e = 0; e < 4; e++) v.addVertex(mat, f[k + e * 3], f[k + e * 3 + 1], f[k + e * 3 + 2]).setColor(r, g, b, a);
                        }
                        for (int i = 0; i < gp.length; i++) {
                            float a = ga[i] * fd * fa;
                            if (a <= 0.003f) continue;
                            wuerfelFlaechen(mat, v, gp[i], ox, oy, oz, r, g, b, a);
                        }
                    });
                }
                if (stil.linien) {
                    final boolean glow = stil.glow;
                    collector.submitCustomGeometry(ms, EspRenderLayer.espLines(), (pose, v) -> {
                        Matrix4f mat = pose.pose();
                        float[] k = m.kanten;
                        // Glow zuerst (breit, blass), darueber die scharfe Linie
                        for (int durchgang = glow ? 0 : 1; durchgang < 2; durchgang++) {
                            float w = durchgang == 0 ? breite * 3.0f : breite;
                            float faktor = durchgang == 0 ? 0.22f : 1f;
                            for (int i = 0, j = 0; i < m.kanteBlock.length; i++, j += 6) {
                                float a = alpha[m.kanteBlock[i]] * fa * faktor;
                                if (a <= 0.003f) continue;
                                linie(mat, v, k[j], k[j + 1], k[j + 2], k[j + 3], k[j + 4], k[j + 5], r, g, b, a, w);
                            }
                            for (int i = 0; i < gp.length; i++) {
                                float a = ga[i] * fa * faktor;
                                if (a <= 0.003f) continue;
                                wuerfelKanten(mat, v, gp[i], ox, oy, oz, r, g, b, a, w);
                            }
                        }
                    });
                }
            } finally {
                ms.popPose();
            }
        }
    }

    @FunctionalInterface
    public interface BlockAktion {
        void mach(double x, double y, double z, float deckkraft);
    }

    // ------------------------------------------------------------------

    private static float glatt(float t) {
        if (t <= 0f) return 0f;
        if (t >= 1f) return 1f;
        return t * t * (3f - 2f * t);
    }

    private static void linie(Matrix4f mat, VertexConsumer v, float x1, float y1, float z1, float x2, float y2, float z2,
                              float r, float g, float b, float a, float breite) {
        float dx = x2 - x1, dy = y2 - y1, dz = z2 - z1;
        float len = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (len < 1.0e-6f) return;
        float nx = dx / len, ny = dy / len, nz = dz / len;
        v.addVertex(mat, x1, y1, z1).setColor(r, g, b, a).setNormal(nx, ny, nz).setLineWidth(breite);
        v.addVertex(mat, x2, y2, z2).setColor(r, g, b, a).setNormal(nx, ny, nz).setLineWidth(breite);
    }

    /** Kasten um einen einzelnen (ausblendenden) Block, relativ zum Ursprung des Mesh. */
    private static void wuerfelKanten(Matrix4f mat, VertexConsumer v, long p, int ox, int oy, int oz,
                                      float r, float g, float b, float a, float w) {
        float x = BlockPos.getX(p) - ox, y = BlockPos.getY(p) - oy, z = BlockPos.getZ(p) - oz;
        float X = x + 1, Y = y + 1, Z = z + 1;
        linie(mat, v, x, y, z, X, y, z, r, g, b, a, w); linie(mat, v, x, Y, z, X, Y, z, r, g, b, a, w);
        linie(mat, v, x, y, Z, X, y, Z, r, g, b, a, w); linie(mat, v, x, Y, Z, X, Y, Z, r, g, b, a, w);
        linie(mat, v, x, y, z, x, Y, z, r, g, b, a, w); linie(mat, v, X, y, z, X, Y, z, r, g, b, a, w);
        linie(mat, v, x, y, Z, x, Y, Z, r, g, b, a, w); linie(mat, v, X, y, Z, X, Y, Z, r, g, b, a, w);
        linie(mat, v, x, y, z, x, y, Z, r, g, b, a, w); linie(mat, v, X, y, z, X, y, Z, r, g, b, a, w);
        linie(mat, v, x, Y, z, x, Y, Z, r, g, b, a, w); linie(mat, v, X, Y, z, X, Y, Z, r, g, b, a, w);
    }

    private static void wuerfelFlaechen(Matrix4f mat, VertexConsumer v, long p, int ox, int oy, int oz, float r, float g, float b, float a) {
        float x = BlockPos.getX(p) - ox, y = BlockPos.getY(p) - oy, z = BlockPos.getZ(p) - oz;
        float X = x + 1, Y = y + 1, Z = z + 1;
        float[][] q = {
                {x, Y, z, X, Y, z, X, Y, Z, x, Y, Z}, {x, y, z, x, y, Z, X, y, Z, X, y, z},
                {X, y, z, X, y, Z, X, Y, Z, X, Y, z}, {x, y, z, x, Y, z, x, Y, Z, x, y, Z},
                {x, y, Z, x, Y, Z, X, Y, Z, X, y, Z}, {x, y, z, X, y, z, X, Y, z, x, Y, z}};
        for (float[] f : q) for (int e = 0; e < 4; e++) v.addVertex(mat, f[e * 3], f[e * 3 + 1], f[e * 3 + 2]).setColor(r, g, b, a);
    }

    /** Kleine wachsende Listen ohne Boxing. */
    private static final class FloatListe {
        private float[] d;
        private int n;
        FloatListe(int start) { d = new float[Math.max(16, start)]; }
        void add(float... w) {
            if (n + w.length > d.length) d = java.util.Arrays.copyOf(d, Math.max(d.length * 2, n + w.length));
            System.arraycopy(w, 0, d, n, w.length);
            n += w.length;
        }
        float[] fertig() { return java.util.Arrays.copyOf(d, n); }
    }

    private static final class IntListe {
        private int[] d;
        private int n;
        IntListe(int start) { d = new int[Math.max(16, start)]; }
        void add(int w) {
            if (n == d.length) d = java.util.Arrays.copyOf(d, d.length * 2);
            d[n++] = w;
        }
        int[] fertig() { return java.util.Arrays.copyOf(d, n); }
    }
}
