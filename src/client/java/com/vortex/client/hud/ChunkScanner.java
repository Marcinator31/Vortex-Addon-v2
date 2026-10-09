package com.vortex.client.hud;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientChunkEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;

/**
 * Gemeinsamer Chunk-Scanner fuer Block-ESP, Tunnel Detector und Amethyst-Erkennung
 * (seit Addon 2.44).
 *
 * VORHER: Jedes Modul lief alle paar Millisekunden ueber JEDEN Block im Umkreis
 * (Block-ESP: Spalte fuer Spalte, getBlockState pro Block, hoechstens 64 Bloecke
 * hoch und runter; Tunnel Detector: fest 24 Bloecke Radius). Bei grosser
 * Reichweite waren das hunderte Millionen Abfragen pro Durchlauf -- deshalb
 * kleine Grenzen, und weit Entferntes erschien nie.
 *
 * JETZT:
 *   - Jeder geladene Chunk wird EINMAL durchsucht, wenn er ankommt, und erst
 *     wieder, wenn sich darin ein Block aendert. Das Ergebnis bleibt pro Chunk
 *     gespeichert. Reichweite = alles, was der Server geschickt hat (die ganze
 *     Sichtweite, nicht nur ein Ausschnitt).
 *   - Pro 16x16x16-Abschnitt wird zuerst die Blockpalette gefragt ("kommt hier
 *     ueberhaupt ein gesuchter Block vor?"). Meist nein -> 4096 Bloecke auf
 *     einmal uebersprungen. Nur Abschnitte mit Treffern werden Block fuer Block
 *     gelesen, und zwar direkt aus dem Abschnitt statt ueber die Welt.
 *   - Naechste Chunks zuerst; alles in einem Hintergrund-Thread.
 *   - Aendern sich die Einstellungen eines Moduls (andere Bloecke, Hoehe ...),
 *     wird fuer dieses Modul alles neu durchsucht.
 */
public final class ChunkScanner {

    private ChunkScanner() {}

    /** Eine Suche ueber Chunks. */
    public interface Job<R> {
        /** Modul an? Sonst ruht die Suche und die Ergebnisse werden verworfen. */
        boolean aktiv();
        /** Aendert sich dieser Wert, wird alles neu durchsucht (Einstellungen). */
        Object signatur();
        /** Einen Chunk durchsuchen (Hintergrund-Thread). null = nichts gefunden. */
        R scanne(ClientLevel level, LevelChunk chunk);
        /** Ergebnisse entladener Chunks behalten? */
        default boolean merken() { return false; }
    }

    /** Zustand einer Suche: Ergebnisse je Chunk. */
    public static final class Slot<R> {
        final Job<R> job;
        /** Geladene Chunks mit Ergebnis (auch "nichts gefunden" -> LEER-Marke). */
        final Map<Long, Object> ergebnisse = new ConcurrentHashMap<>();
        /** Ergebnisse entladener Chunks (nur wenn merken()). */
        final Map<Long, Object> gemerkt = new ConcurrentHashMap<>();
        volatile Object signatur;
        /** Steigt bei jeder Aenderung -- Abnehmer bauen nur dann neu. */
        volatile int version;

        Slot(Job<R> job) { this.job = job; }

        public int version() { return version; }

        /** Alle Ergebnisse (geladene, auf Wunsch auch gemerkte), ohne leere. */
        @SuppressWarnings("unchecked")
        public void fuerAlle(boolean auchGemerkte, java.util.function.BiConsumer<Long, R> aktion) {
            for (Map.Entry<Long, Object> e : ergebnisse.entrySet()) {
                if (e.getValue() != NICHTS) aktion.accept(e.getKey(), (R) e.getValue());
            }
            if (auchGemerkte) {
                for (Map.Entry<Long, Object> e : gemerkt.entrySet()) {
                    if (e.getValue() != NICHTS && !ergebnisse.containsKey(e.getKey())) aktion.accept(e.getKey(), (R) e.getValue());
                }
            }
        }

        @SuppressWarnings("unchecked")
        public R get(long chunk) {
            Object o = ergebnisse.get(chunk);
            if (o == null) o = gemerkt.get(chunk);
            return o == null || o == NICHTS ? null : (R) o;
        }

        /** Wie viele geladene Chunks noch nicht durchsucht sind (fuer Tests/Anzeige). */
        public int offen() {
            int n = 0;
            for (Long k : GELADEN.keySet()) if (!ergebnisse.containsKey(k)) n++;
            return n;
        }

        void leeren() {
            if (!ergebnisse.isEmpty() || !gemerkt.isEmpty()) { ergebnisse.clear(); gemerkt.clear(); version++; }
        }
    }

    private static final Object NICHTS = new Object();
    /** Hoechstens so viele entladene Chunks merken (je Suche). */
    private static final int MAX_GEMERKT = 20000;

    private static final List<Slot<?>> SLOTS = new CopyOnWriteArrayList<>();
    /** Geladene Chunks (Haupt-Thread traegt ein, Worker liest). */
    private static final Map<Long, LevelChunk> GELADEN = new ConcurrentHashMap<>();
    /** Chunks mit geaenderten Bloecken -> Zeitpunkt der letzten Aenderung. */
    private static final Map<Long, Long> GEAENDERT = new ConcurrentHashMap<>();
    /** Nach dem Durchsuchen auf dem Worker ausgefuehrt (Abnehmer bauen ihre Anzeige). */
    private static final List<Runnable> NACHHER = new CopyOnWriteArrayList<>();

    private static volatile Object welt;
    private static volatile boolean gestartet;

    public static <R> Slot<R> anmelden(Job<R> job) {
        Slot<R> s = new Slot<>(job);
        SLOTS.add(s);
        return s;
    }

    /** Laeuft nach jedem Durchgang des Workers (dort Anzeige aus den Ergebnissen bauen). */
    public static void nachher(Runnable r) { NACHHER.add(r); }

    /** Ein Block hat sich geaendert (aus dem Mixin, Haupt-Thread). */
    public static void geaendert(int blockX, int blockZ) {
        if (SLOTS.isEmpty()) return;
        GEAENDERT.put(ChunkPos.pack(blockX >> 4, blockZ >> 4), System.currentTimeMillis());
    }

    public static int geladen() { return GELADEN.size(); }

    public static void register() {
        ClientChunkEvents.CHUNK_LOAD.register((level, chunk) -> {
            long k = chunk.getPos().pack();
            GELADEN.put(k, chunk);
            GEAENDERT.remove(k);
            // Neu (oder neu geschickt): fuer alle Suchen offen
            for (Slot<?> s : SLOTS) if (s.ergebnisse.remove(k) != null) s.version++;
        });
        ClientChunkEvents.CHUNK_UNLOAD.register((level, chunk) -> {
            long k = chunk.getPos().pack();
            GELADEN.remove(k);
            for (Slot<?> s : SLOTS) {
                Object r = s.ergebnisse.remove(k);
                if (r != null && r != NICHTS && s.job.merken() && s.gemerkt.size() < MAX_GEMERKT) s.gemerkt.put(k, r);
                else if (r != null) s.version++;
            }
        });
    }

    private static void starten() {
        if (gestartet) return;
        gestartet = true;
        Thread t = new Thread(ChunkScanner::schleife, "vortexplus-chunkscanner");
        t.setDaemon(true);
        t.setPriority(Thread.NORM_PRIORITY - 1);
        t.start();
    }

    /** Von den Modulen pro Bild aufgerufen: Worker bei Bedarf starten. */
    public static void brauche() { starten(); }

    private static void schleife() {
        while (true) {
            try {
                boolean arbeit = durchgang();
                for (Runnable r : NACHHER) {
                    try { r.run(); } catch (Throwable e) { com.vortex.client.core.Errors.report("ChunkScanner.nachher", e); }
                }
                Thread.sleep(arbeit ? 2 : 60);
            } catch (InterruptedException ie) {
                return;
            } catch (Throwable e) {
                com.vortex.client.core.Errors.report("ChunkScanner", e);
                try { Thread.sleep(200); } catch (InterruptedException ie) { return; }
            }
        }
    }

    /** true = es wurde gearbeitet (gleich weitermachen). */
    private static boolean durchgang() {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level != welt) {
            welt = level;
            for (Slot<?> s : SLOTS) s.leeren();
            GEAENDERT.clear();
            if (level == null) GELADEN.clear();
            else GELADEN.values().removeIf(c -> c.getLevel() != level);
        }
        if (level == null || mc.player == null) return false;
        int pcx = mc.player.getBlockX() >> 4, pcz = mc.player.getBlockZ() >> 4;

        List<Slot<?>> aktiv = new ArrayList<>();
        for (Slot<?> s : SLOTS) {
            boolean an;
            try { an = s.job.aktiv(); } catch (Throwable e) { an = false; }
            if (!an) { s.leeren(); s.signatur = null; continue; }
            Object sig;
            try { sig = s.job.signatur(); } catch (Throwable e) { sig = null; }
            if (s.signatur == null || !s.signatur.equals(sig)) {
                s.leeren();
                s.signatur = sig;
            }
            aktiv.add(s);
        }
        if (aktiv.isEmpty()) return false;

        // Geaenderte Chunks (kurz warten, falls gerade viel gebaut/abgebaut wird)
        long jetzt = System.currentTimeMillis();
        for (Map.Entry<Long, Long> e : GEAENDERT.entrySet()) {
            if (jetzt - e.getValue() < 150) continue;
            long k = e.getKey();
            if (GEAENDERT.remove(k, e.getValue())) {
                for (Slot<?> s : aktiv) if (s.ergebnisse.remove(k) != null) s.version++;
            }
        }

        // Offene Chunks: naechste zuerst
        long[] offen = new long[GELADEN.size()];
        int n = 0;
        for (Long k : GELADEN.keySet()) {
            for (Slot<?> s : aktiv) {
                if (!s.ergebnisse.containsKey(k)) { if (n < offen.length) offen[n++] = k; break; }
            }
        }
        if (n == 0) return false;
        final int fx = pcx, fz = pcz;
        Long[] sortiert = new Long[n];
        for (int i = 0; i < n; i++) sortiert[i] = offen[i];
        java.util.Arrays.sort(sortiert, (a, b) -> Integer.compare(abstand(a, fx, fz), abstand(b, fx, fz)));

        long ende = System.nanoTime() + 25_000_000L;   // ~25 ms am Stueck, dann Ergebnisse zeigen
        for (Long k : sortiert) {
            LevelChunk c = GELADEN.get(k);
            if (c == null) continue;
            for (Slot<?> s : aktiv) {
                if (s.ergebnisse.containsKey(k)) continue;
                scanne(s, level, c, k);
            }
            if (System.nanoTime() > ende) break;
        }
        return true;
    }

    private static <R> void scanne(Slot<R> s, ClientLevel level, LevelChunk c, long k) {
        Object r;
        try {
            r = s.job.scanne(level, c);
        } catch (Throwable e) {
            r = null;   // Chunk wurde gerade veraendert/entladen -- als leer werten, kommt bei Aenderung wieder
        }
        if (!GELADEN.containsKey(k)) return;
        Object alt = s.ergebnisse.put(k, r == null ? NICHTS : r);
        s.gemerkt.remove(k);
        if (!(alt == NICHTS && r == null)) s.version++;
    }

    private static int abstand(long chunk, int pcx, int pcz) {
        int dx = ChunkPos.getX(chunk) - pcx, dz = ChunkPos.getZ(chunk) - pcz;
        return dx * dx + dz * dz;
    }

    // ------------------------------------------------------------------
    // Hilfen fuer Suchen
    // ------------------------------------------------------------------

    /** Kleinste Blockhoehe eines Abschnitts. */
    public static int abschnittY(LevelChunk c, int index) {
        return c.getMinY() + index * 16;
    }
}
