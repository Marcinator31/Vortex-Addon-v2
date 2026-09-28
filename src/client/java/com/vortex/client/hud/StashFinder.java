package com.vortex.client.hud;

import com.mojang.blaze3d.vertex.PoseStack;
import com.vortex.client.module.ModuleManager;
import com.vortex.client.waypoint.WaypointManager;
import com.vortex.client.module.modules.StashFinderModule;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal;

/**
 * Findet Stashes: Gruppen von Truhen, Faessern und Shulkern.
 *
 * ABLAUF
 *  1. WorldScan sammelt auf dem Haupt-Thread alle Block-Entities der geladenen
 *     Chunks und merkt sich bei Truhen/Faessern/Shulkern die Art.
 *  2. Hier werden die Lager-Container zu GRUPPEN zusammengefasst: Die Welt wird
 *     in Wuerfel mit der Kantenlaenge "Cluster Radius" geteilt; benachbarte
 *     belegte Wuerfel gehoeren zu einer Gruppe. Das ist linear in der Anzahl
 *     Container -- auch ein Lagerraum mit tausenden Truhen kostet kaum etwas.
 *     (Vorher: Container je Chunk gezaehlt. Ein Lager auf einer Chunkgrenze
 *     wurde halbiert und oft uebersehen.)
 *  3. Gruppen ab der Schwelle sind Stashes. Neue werden gemeldet (Chat, Ton,
 *     optional Waypoint) und in eine Liste eingetragen, die auch nach dem
 *     Entladen des Chunks und ueber Neustarts erhalten bleibt
 *     (config/vortexclient/stashes.txt).
 *
 * Gerechnet wird nur, wenn WorldScan eine neue Runde fertig hat (alle paar
 * Sekunden) -- kein eigener Dauer-Thread mehr.
 */
public final class StashFinder {

    private StashFinder() {}

    /** Eine Gruppe in den geladenen Chunks. */
    public static final class Stash {
        public final Vec3 center;
        public final AABB box;
        public final int truhen, faesser, shulker, score;
        Stash(Vec3 center, AABB box, int truhen, int faesser, int shulker, int score) {
            this.center = center; this.box = box;
            this.truhen = truhen; this.faesser = faesser; this.shulker = shulker; this.score = score;
        }
        public int count() { return truhen + faesser + shulker; }
    }

    /** Ein gemerkter Fund. */
    private static final class Bekannt {
        final String welt;
        int x, y, z, truhen, faesser, shulker;
        final long erstmals;
        long zuletzt;
        Bekannt(String welt, int x, int y, int z, int truhen, int faesser, int shulker, long erstmals, long zuletzt) {
            this.welt = welt; this.x = x; this.y = y; this.z = z;
            this.truhen = truhen; this.faesser = faesser; this.shulker = shulker;
            this.erstmals = erstmals; this.zuletzt = zuletzt;
        }
        int count() { return truhen + faesser + shulker; }
    }

    /** Aktuell geladene Stashes (nur Haupt-Thread schreibt, Render liest). */
    private static volatile List<Stash> geladen = List.of();
    private static final List<Bekannt> BEKANNT = new ArrayList<>();
    private static boolean dateiGeladen = false;
    private static int letzteVersion = -1;

    /** Innerhalb dieser Entfernung ist ein Fund "derselbe Stash". */
    private static final double GLEICH = 32;

    public static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(StashFinder::tick);
        LevelRenderEvents.AFTER_TRANSLUCENT_FEATURES.register(StashFinder::render);
        ClientPlayConnectionEvents.DISCONNECT.register((h, c) -> { geladen = List.of(); letzteVersion = -1; });
        ClientCommandRegistrationCallback.EVENT.register((d, access) -> d.register(literal("stashes")
                .executes(c -> { liste(false); return 1; })
                .then(literal("all").executes(c -> { liste(true); return 1; }))
                .then(literal("clear").executes(c -> { leeren(); return 1; }))));
    }

    private static StashFinderModule modul() {
        return ModuleManager.INSTANCE.get(StashFinderModule.class);
    }

    // ------------------------------------------------------------------ Tick

    private static void tick(Minecraft mc) {
        long t0 = System.nanoTime();
        try {
            StashFinderModule mod = modul();
            if (mod == null || !mod.isEnabled() || mc.level == null || mc.player == null) {
                if (!geladen.isEmpty()) geladen = List.of();
                letzteVersion = -1;
                return;
            }
            WorldScan.Snapshot snap = WorldScan.get();
            if (snap.version == letzteVersion) return;
            letzteVersion = snap.version;

            List<Stash> neu = gruppieren(snap, mod);
            geladen = neu;
            if (!neu.isEmpty()) abgleichen(mc, mod, neu);
        } catch (Throwable pvpErr) {
            com.vortex.client.core.Errors.report("StashFinder", pvpErr);
        } finally {
            com.vortex.client.core.Profiler.record("StashFinder", System.nanoTime() - t0);
        }
    }

    /** Lager-Container zu Gruppen zusammenfassen (Wuerfel-Nachbarschaft). */
    private static List<Stash> gruppieren(WorldScan.Snapshot snap, StashFinderModule mod) {
        int zelle = Math.max(2, mod.clusterRadius.getInt());
        boolean doppelt = mod.shulkerDouble.get();
        int schwelle = mod.getThreshold();

        Map<Long, List<WorldScan.Be>> zellen = new HashMap<>();
        for (WorldScan.Be be : snap.entries) {
            if (be.lager == 0) continue;
            long k = zellKey(Math.floorDiv(be.pos.getX(), zelle), Math.floorDiv(be.pos.getY(), zelle),
                    Math.floorDiv(be.pos.getZ(), zelle));
            zellen.computeIfAbsent(k, x -> new ArrayList<>()).add(be);
        }
        List<Stash> out = new ArrayList<>();
        java.util.Set<Long> besucht = new java.util.HashSet<>();
        ArrayDeque<Long> offen = new ArrayDeque<>();
        for (Long start : zellen.keySet()) {
            if (!besucht.add(start)) continue;
            int truhen = 0, faesser = 0, shulker = 0;
            double sx = 0, sy = 0, sz = 0;
            int n = 0;
            int x0 = Integer.MAX_VALUE, y0 = Integer.MAX_VALUE, z0 = Integer.MAX_VALUE;
            int x1 = Integer.MIN_VALUE, y1 = Integer.MIN_VALUE, z1 = Integer.MIN_VALUE;
            offen.add(start);
            while (!offen.isEmpty()) {
                long k = offen.poll();
                for (WorldScan.Be be : zellen.get(k)) {
                    if (be.lager == WorldScan.LAGER_TRUHE) truhen++;
                    else if (be.lager == WorldScan.LAGER_FASS) faesser++;
                    else shulker++;
                    int x = be.pos.getX(), y = be.pos.getY(), z = be.pos.getZ();
                    sx += x; sy += y; sz += z; n++;
                    x0 = Math.min(x0, x); y0 = Math.min(y0, y); z0 = Math.min(z0, z);
                    x1 = Math.max(x1, x); y1 = Math.max(y1, y); z1 = Math.max(z1, z);
                }
                int cx = zellX(k), cy = zellY(k), cz = zellZ(k);
                for (int dx = -1; dx <= 1; dx++) for (int dy = -1; dy <= 1; dy++) for (int dz = -1; dz <= 1; dz++) {
                    long nk = zellKey(cx + dx, cy + dy, cz + dz);
                    if (zellen.containsKey(nk) && besucht.add(nk)) offen.add(nk);
                }
            }
            int score = truhen + faesser + (doppelt ? 2 * shulker : shulker);
            if (score < schwelle || n == 0) continue;
            Vec3 mitte = new Vec3(sx / n + 0.5, sy / n + 0.5, sz / n + 0.5);
            AABB box = new AABB(x0 - 0.05, y0 - 0.05, z0 - 0.05, x1 + 1.05, y1 + 1.05, z1 + 1.05);
            out.add(new Stash(mitte, box, truhen, faesser, shulker, score));
        }
        return out;
    }

    // 21 Bit je Achse (vorzeichenbehaftet) -- reicht fuer jede Zellgroesse >= 2.
    private static long zellKey(int x, int y, int z) {
        return ((long) (x & 0x1FFFFF) << 42) | ((long) (y & 0x1FFFFF) << 21) | (z & 0x1FFFFF);
    }
    private static int zellX(long k) { return ((int) (k >>> 42) << 11) >> 11; }
    private static int zellY(long k) { return ((int) ((k >>> 21) & 0x1FFFFF) << 11) >> 11; }
    private static int zellZ(long k) { return ((int) (k & 0x1FFFFF) << 11) >> 11; }

    /** Neue Stashes melden, bekannte aktualisieren. Haupt-Thread. */
    private static void abgleichen(Minecraft mc, StashFinderModule mod, List<Stash> gefunden) {
        ladeDatei();
        String welt = WaypointRenderer.currentWorldKey(mc);
        long jetzt = System.currentTimeMillis();
        boolean geaendert = false;
        for (Stash s : gefunden) {
            int x = (int) Math.floor(s.center.x), y = (int) Math.floor(s.center.y), z = (int) Math.floor(s.center.z);
            Bekannt b = finde(welt, x, z);
            if (b != null) {
                b.zuletzt = jetzt;
                // Waechst das Lager (mehr Chunks geladen), die groesseren Zahlen merken.
                if (s.count() > b.count()) {
                    b.truhen = s.truhen; b.faesser = s.faesser; b.shulker = s.shulker;
                    b.x = x; b.y = y; b.z = z;
                    geaendert = true;
                }
                continue;
            }
            synchronized (BEKANNT) {
                BEKANNT.add(new Bekannt(welt, x, y, z, s.truhen, s.faesser, s.shulker, jetzt, jetzt));
            }
            geaendert = true;
            melden(mc, mod, s, x, y, z);
        }
        if (geaendert && mod.logFile.get()) schreibeDatei();
    }

    private static Bekannt finde(String welt, int x, int z) {
        synchronized (BEKANNT) {
            for (Bekannt b : BEKANNT) {
                if (!b.welt.equals(welt)) continue;
                double dx = b.x - x, dz = b.z - z;
                if (dx * dx + dz * dz <= GLEICH * GLEICH) return b;
            }
        }
        return null;
    }

    private static void melden(Minecraft mc, StashFinderModule mod, Stash s, int x, int y, int z) {
        int dist = (int) Math.sqrt(mc.player.distanceToSqr(s.center));
        if (mod.notifyEnabled()) {
            mc.player.sendSystemMessage(Component.literal("§d[Stash Finder] §fStash at §e"
                    + x + " " + y + " " + z + " §7(" + dist + " m) §8| §f" + inhalt(s.truhen, s.faesser, s.shulker)));
        }
        if (mod.sound.get()) {
            try {
                mc.getSoundManager().play(net.minecraft.client.resources.sounds.SimpleSoundInstance
                        .forUI(net.minecraft.sounds.SoundEvents.NOTE_BLOCK_PLING, 1.5f));
            } catch (Throwable ignored) { }
        }
        if (mod.waypoint.get()) {
            try {
                com.vortex.client.waypoint.WaypointActions.addWaypoint(mc, x, y, z,
                        "Stash (" + s.count() + ")", WaypointManager.Kind.LAGER, false);
            } catch (Throwable pvpErr) {
                com.vortex.client.core.Errors.report("StashFinder.waypoint", pvpErr);
            }
        }
    }

    private static String inhalt(int truhen, int faesser, int shulker) {
        List<String> teile = new ArrayList<>();
        if (truhen > 0) teile.add(truhen + (truhen == 1 ? " chest" : " chests"));
        if (faesser > 0) teile.add(faesser + (faesser == 1 ? " barrel" : " barrels"));
        if (shulker > 0) teile.add("§d" + shulker + " shulker" + (shulker == 1 ? "" : "s") + "§f");
        return String.join(", ", teile);
    }

    // ---------------------------------------------------------------- Datei

    private static Path datei() {
        return com.vortex.client.core.ConfigManager.dataDir().resolve("stashes.txt");
    }

    /** Zeilenformat: welt \t x \t y \t z \t truhen \t faesser \t shulker \t erstmals \t zuletzt */
    private static void ladeDatei() {
        if (dateiGeladen) return;
        dateiGeladen = true;
        try {
            if (!Files.exists(datei())) return;
            for (String z : Files.readAllLines(datei(), StandardCharsets.UTF_8)) {
                if (z.isBlank() || z.startsWith("#")) continue;
                String[] t = z.split("\t");
                if (t.length < 9) continue;
                try {
                    synchronized (BEKANNT) {
                        BEKANNT.add(new Bekannt(t[0], Integer.parseInt(t[1]), Integer.parseInt(t[2]), Integer.parseInt(t[3]),
                                Integer.parseInt(t[4]), Integer.parseInt(t[5]), Integer.parseInt(t[6]),
                                Long.parseLong(t[7]), Long.parseLong(t[8])));
                    }
                } catch (NumberFormatException ignored) { }
            }
        } catch (Throwable pvpErr) {
            com.vortex.client.core.Errors.report("StashFinder.load", pvpErr);
        }
    }

    private static void schreibeDatei() {
        try {
            List<String> zeilen = new ArrayList<>();
            zeilen.add("# Vortex Stash Finder -- world\tx\ty\tz\tchests\tbarrels\tshulkers\tfirstSeen\tlastSeen");
            synchronized (BEKANNT) {
                for (Bekannt b : BEKANNT) {
                    zeilen.add(b.welt + "\t" + b.x + "\t" + b.y + "\t" + b.z + "\t" + b.truhen + "\t" + b.faesser
                            + "\t" + b.shulker + "\t" + b.erstmals + "\t" + b.zuletzt);
                }
            }
            Files.createDirectories(datei().getParent());
            Files.write(datei(), zeilen, StandardCharsets.UTF_8);
        } catch (Throwable pvpErr) {
            com.vortex.client.core.Errors.report("StashFinder.save", pvpErr);
        }
    }

    // ------------------------------------------------------------- Befehle

    private static void liste(boolean alle) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        ladeDatei();
        String welt = WaypointRenderer.currentWorldKey(mc);
        List<Bekannt> l;
        synchronized (BEKANNT) {
            l = new ArrayList<>();
            for (Bekannt b : BEKANNT) if (alle || b.welt.equals(welt)) l.add(b);
        }
        if (l.isEmpty()) {
            sag(mc, "§7No stashes found" + (alle ? "" : " in this world") + " yet.");
            return;
        }
        double px = mc.player.getX(), pz = mc.player.getZ();
        l.sort((a, b) -> Double.compare(dist2(a, px, pz), dist2(b, px, pz)));
        sag(mc, "§d[Stash Finder] §f" + l.size() + " stash" + (l.size() == 1 ? "" : "es")
                + (alle ? " (all worlds)" : " in this world") + ":");
        int n = 0;
        for (Bekannt b : l) {
            if (++n > 15) { sag(mc, "§8 ... and " + (l.size() - 15) + " more (see stashes.txt)"); break; }
            String wo = b.welt.equals(welt) ? " §7(" + (int) Math.sqrt(dist2(b, px, pz)) + " m)" : " §8[" + b.welt + "]";
            sag(mc, " §e" + b.x + " " + b.y + " " + b.z + wo + " §8| §f" + inhalt(b.truhen, b.faesser, b.shulker));
        }
    }

    private static double dist2(Bekannt b, double px, double pz) {
        double dx = b.x - px, dz = b.z - pz;
        return dx * dx + dz * dz;
    }

    private static void leeren() {
        Minecraft mc = Minecraft.getInstance();
        int n;
        synchronized (BEKANNT) { n = BEKANNT.size(); BEKANNT.clear(); }
        dateiGeladen = true;
        try { Files.deleteIfExists(datei()); } catch (Throwable ignored) { }
        letzteVersion = -1;   // aktuell geladene Stashes gleich neu melden
        sag(mc, "§7" + n + " remembered stash" + (n == 1 ? "" : "es") + " forgotten.");
    }

    private static void sag(Minecraft mc, String text) {
        if (mc.player != null) mc.player.sendSystemMessage(Component.literal(text));
    }

    /** Fuer andere Stellen, die frueher reset() riefen. */
    public static void reset() {
        geladen = List.of();
        letzteVersion = -1;
    }

    // ------------------------------------------------------------- Zeichnen

    private static void render(net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext context) {
        StashFinderModule mod = modul();
        if (mod == null || !mod.isEnabled()) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return;
        PoseStack matrices = context.poseStack();
        SubmitNodeCollector collector = context.submitNodeCollector();
        if (matrices == null || collector == null) return;

        long t0 = System.nanoTime();
        try {
            float tickDelta = mc.getDeltaTracker().getGameTimeDeltaPartialTick(false);
            Vec3 cam = EspRender.cameraOffset(mc, tickDelta);
            int color = mod.getTracerColor();
            if ((color >>> 24) == 0) color |= 0xFF000000;
            List<Stash> jetzt = geladen;

            if (mod.box.get()) {
                for (Stash s : jetzt) EspRender.submitBox(collector, matrices, s.box, cam, color, 2.0f);
            }
            if (!mod.tracerEnabled()) return;

            List<Vec3> ziele = new ArrayList<>();
            for (Stash s : jetzt) ziele.add(s.center);
            if (mod.remember.get()) {
                String welt = WaypointRenderer.currentWorldKey(mc);
                synchronized (BEKANNT) {
                    for (Bekannt b : BEKANNT) {
                        if (!b.welt.equals(welt)) continue;
                        Vec3 p = new Vec3(b.x + 0.5, b.y + 0.5, b.z + 0.5);
                        boolean doppelt = false;
                        for (Stash s : jetzt) if (s.center.distanceToSqr(p) < GLEICH * GLEICH) { doppelt = true; break; }
                        if (!doppelt) ziele.add(p);
                    }
                }
                // Nicht hunderte Linien: die 40 naechsten.
                if (ziele.size() > 40) {
                    Vec3 me = mc.player.position();
                    ziele.sort((a, b) -> Double.compare(a.distanceToSqr(me), b.distanceToSqr(me)));
                    ziele = new ArrayList<>(ziele.subList(0, 40));
                }
            }
            final Vec3 start = EspRender.tracerStart(mc, cam, tickDelta);
            final int farbe = color;
            final List<Vec3> alle = ziele;
            EspRender.submitLines(collector, matrices, (matrix, lines) -> {
                for (Vec3 z : alle) EspRender.drawTracer(matrix, lines, start, z, cam, farbe, 2.0f);
            });
        } catch (Throwable pvpErr) {
            com.vortex.client.core.Errors.report("StashFinder.render", pvpErr);
        } finally {
            com.vortex.client.core.Profiler.record("StashFinder draw", System.nanoTime() - t0);
        }
    }
}
