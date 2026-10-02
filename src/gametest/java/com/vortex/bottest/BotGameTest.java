package com.vortex.bottest;

import com.vortex.client.module.ModuleManager;
import com.vortex.client.module.modules.CropFarmerModule;
import com.vortex.client.module.modules.TreeFarmerModule;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.Container;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

/**
 * Bot-Tests in einem echten Minecraft (nur CI, wird nie ausgeliefert).
 *
 * Eine Testwelt, drei Bereiche:
 *   CROP     9x9-Weizenfeld mit Wasserrinne in der Mitte, davor ein Zaun
 *            (der Bot muss aussen herum), daneben eine Truhe. Inventar fast
 *            voll -> er muss einlagern. Geprueft: geerntet, nachgepflanzt,
 *            kein Acker zertreten, Truhe gefuellt, Spieler gesund.
 *   TREE     zwei Eichen, eine Birke, eine Fichte und ein Baum mit tief
 *            haengender Krone (ein Block Luft darunter). Geprueft: alle Staemme
 *            weg, Setzlinge gepflanzt, fast alles Holz im Inventar, Bot nicht
 *            auf einem Turm haengen geblieben. Danach noch ein tiefer Baum mit
 *            "Collect Drops" AUS: das Holz muss trotzdem beim Bot landen.
 *   DEFENCE  Nacht, ein Zombie. Geprueft: Zombie tot, Spieler lebt.
 *
 * Ergebnis: bot-test-results.txt im Spielordner, dazu Screenshots.
 */
@SuppressWarnings("UnstableApiUsage")
public class BotGameTest implements FabricClientGameTest {

    private final List<String> bericht = new ArrayList<>();
    private int fehler = 0;

    private void pruefe(String was, boolean ok, String details) {
        bericht.add((ok ? "OK    " : "FAIL  ") + was + "  (" + details + ")");
        if (!ok) fehler++;
    }

    private void notiz(String text) {
        bericht.add("      " + text);
    }

    @Override
    public void runTest(ClientGameTestContext ctx) {
        if (System.getProperty("vortex.bottest.only", "").contains("gui")) {
            try { abschnitt(ctx, "GUI screenshots", () -> guiBilder(ctx)); } finally { schreibe(); }
            if (fehler > 0) throw new AssertionError(fehler + " check(s) failed");
            return;
        }
        if (System.getProperty("vortex.bottest.only", "").contains("chunkstudy")) {
            try { abschnitt(ctx, "New Chunks study", () -> chunkStudie(ctx)); } finally { schreibe(); }
            if (fehler > 0) throw new AssertionError(fehler + " check(s) failed");
            return;
        }
        try (TestSingleplayerContext sp = ctx.worldBuilder().create()) {
            TestServerContext srv = sp.getServer();
            srv.runCommand("gamemode survival @a");
            srv.runCommand("difficulty peaceful");
            srv.runCommand("time set day");
            srv.runCommand("weather clear 1000000");
            ctx.waitTicks(20);

            // -Dvortex.bottest.only=elytra,crop ... : nur diese Abschnitte (schnelleres Nachpruefen)
            String nur = System.getProperty("vortex.bottest.only", "").toLowerCase();
            if (nur.contains("esp")) abschnitt(ctx, "Block ESP look", () -> espTest(ctx, srv));
            if (nur.contains("freecam")) abschnitt(ctx, "Freecam view", () -> freecamTest(ctx, srv));
            if (nur.contains("freecam")) abschnitt(ctx, "Freecam keeps momentum", () -> freecamSchwungTest(ctx, srv));
            if (nur.isEmpty() || nur.contains("wtap")) abschnitt(ctx, "W-Tap", () -> wTapTest(ctx, srv));
            if (nur.contains("potion")) abschnitt(ctx, "Potion HUD look", () -> potionTest(ctx, srv));
            if (nur.isEmpty() || nur.contains("crop")) abschnitt(ctx, "Crop Farmer", () -> cropTest(ctx, srv));
            if (nur.isEmpty() || nur.contains("tree")) abschnitt(ctx, "Tree Farmer", () -> treeTest(ctx, srv));
            if (nur.isEmpty() || nur.contains("tree")) abschnitt(ctx, "Tree Farmer (Collect Drops off)", () -> treeOhneSammeln(ctx, srv));
            if (nur.isEmpty() || nur.contains("defen")) abschnitt(ctx, "Defence", () -> defenceTest(ctx, srv));
            if (nur.isEmpty() || nur.contains("elytra")) abschnitt(ctx, "Elytra Autopilot", () -> elytraTest(ctx, srv));
            if (nur.isEmpty() || nur.contains("elytra")) abschnitt(ctx, "Elytra Autopilot: wall in front", () -> elytraWandTest(ctx, srv));
            if (nur.isEmpty() || nur.contains("elytra")) abschnitt(ctx, "Elytra Autopilot: boxed in", () -> elytraKastenTest(ctx, srv));
        } finally {
            schreibe();
        }
        if (fehler > 0) throw new AssertionError(fehler + " bot check(s) failed -- see bot-test-results.txt");
    }

    private void abschnitt(ClientGameTestContext ctx, String name, Runnable r) {
        bericht.add("=== " + name + " ===");
        long t0 = System.currentTimeMillis();
        try {
            r.run();
        } catch (Throwable t) {
            pruefe(name + " ran without crashing", false, t.toString());
            for (StackTraceElement e : t.getStackTrace()) { notiz("at " + e); if (bericht.size() > 400) break; }
        } finally {
            ctx.runOnClient(mc -> {
                var e = ModuleManager.INSTANCE.get(com.vortex.client.module.modules.ElytraAutopilotModule.class);
                if (e != null) e.setEnabled(false);
                var c = ModuleManager.INSTANCE.get(CropFarmerModule.class);
                if (c != null) c.setEnabled(false);
                var t = ModuleManager.INSTANCE.get(TreeFarmerModule.class);
                if (t != null) t.setEnabled(false);
            });
            ctx.waitTicks(10);
            notiz("took " + (System.currentTimeMillis() - t0) / 1000 + " s");
        }
    }

    private void schreibe() {
        try {
            Path p = FabricLoader.getInstance().getGameDir().resolve("bot-test-results.txt");
            Files.write(p, bericht, StandardCharsets.UTF_8);
            System.out.println("[bot-tests] ===== RESULTS =====");
            for (String s : bericht) System.out.println("[bot-tests] " + s);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // ------------------------------------------------------------------
    // Welt-Helfer

    private static void flaeche(TestServerContext srv, int x1, int z1, int x2, int z2) {
        // Boden auf y=-61, darueber frei (bis y=-48)
        srv.runCommand("fill " + x1 + " -61 " + z1 + " " + x2 + " -61 " + z2 + " minecraft:grass_block");
        srv.runCommand("fill " + x1 + " -62 " + z1 + " " + x2 + " -62 " + z2 + " minecraft:dirt");
        srv.runCommand("fill " + x1 + " -60 " + z1 + " " + x2 + " -48 " + z2 + " minecraft:air");
    }

    private static ServerPlayer spieler(MinecraftServer s) {
        return s.getPlayerList().getPlayers().get(0);
    }

    private static void enable(ClientGameTestContext ctx, Class<? extends com.vortex.client.module.Module> c) {
        ctx.runOnClient(mc -> ModuleManager.INSTANCE.get(c).setEnabled(true));
    }

    private static String botStatus(ClientGameTestContext ctx, Class<?> c) {
        return ctx.computeOnClient(mc -> {
            var m = ModuleManager.INSTANCE.get(c.asSubclass(com.vortex.client.module.Module.class));
            try { return String.valueOf(m.getClass().getMethod("getStatus").invoke(m)); } catch (Exception e) { return "?"; }
        });
    }

    // ------------------------------------------------------------------
    // ESP-Aussehen (nur mit -PbotOnly=esp): Screenshots zum Anschauen

    private void espTest(ClientGameTestContext ctx, TestServerContext srv) {
        srv.runCommand("time set noon");
        srv.runCommand("tp @a 2000.5 -60 0.5 0 12");
        ctx.waitTicks(40);
        flaeche(srv, 1985, -10, 2030, 24);
        srv.runCommand("fill 1990 -60 4 2010 -53 4 minecraft:stone");                  // Wand: alles dahinter
        srv.runCommand("fill 1999 -59 10 2001 -58 11 minecraft:diamond_ore");          // Ader (3x2x2)
        srv.runCommand("setblock 2002 -59 10 minecraft:diamond_ore");                  // ...mit Ausleger
        srv.runCommand("setblock 2001 -57 11 minecraft:diamond_ore");
        srv.runCommand("setblock 1995 -60 12 minecraft:diamond_ore");                  // einzeln
        srv.runCommand("setblock 2006 -56 9 minecraft:diamond_ore");                   // einzeln, schwebend
        srv.runCommand("setblock 1996 -60 8 minecraft:chest[facing=north,type=right]");
        srv.runCommand("setblock 1997 -60 8 minecraft:chest[facing=north,type=left]");
        srv.runCommand("fill 2004 -60 14 2007 -60 14 minecraft:barrel");
        srv.runCommand("setblock 1993 -60 9 minecraft:spawner");
        srv.runCommand("tp @a 2000.5 -60 0.5 0 12");
        ctx.waitTicks(40);
        ctx.runOnClient(mc -> {
            com.vortex.client.core.Errors.clear();
            com.vortex.client.core.Profiler.setEnabled(true);
            com.vortex.client.core.Profiler.reset();
            var b = ModuleManager.INSTANCE.get(com.vortex.client.module.modules.BlockEspModule.class);
            if (!b.isBlockEnabled("minecraft:diamond_ore")) b.toggleBlock("minecraft:diamond_ore");
            b.setEnabled(true);
            ModuleManager.INSTANCE.get(com.vortex.client.module.modules.ContainerEspModule.class).setEnabled(true);
            ModuleManager.INSTANCE.get(com.vortex.client.module.modules.SpawnerEspModule.class).setEnabled(true);
        });
        ctx.waitTicks(4);
        ctx.takeScreenshot("esp-fading-in");
        ctx.waitTicks(60);
        ctx.takeScreenshot("esp-default");
        // Andere Stile (nur Block-ESP aendert sich), wenn es sie gibt
        ctx.runOnClient(mc -> {
            var b = ModuleManager.INSTANCE.get(com.vortex.client.module.modules.BlockEspModule.class);
            for (var st : b.getSettings()) if (st.getName().equals("Style") && st instanceof com.vortex.client.core.setting.ModeSetting ms) ms.set("Outline");
        });
        ctx.waitTicks(10);
        ctx.takeScreenshot("esp-outline-only");
        ctx.runOnClient(mc -> {
            var b = ModuleManager.INSTANCE.get(com.vortex.client.module.modules.BlockEspModule.class);
            for (var st : b.getSettings()) if (st.getName().equals("Style") && st instanceof com.vortex.client.core.setting.ModeSetting ms) ms.set("Outline + Fill");
        });
        srv.runCommand("tp @a 2012.5 -54 -3.5 45 25");                                 // schraeg von oben
        ctx.waitTicks(40);
        ctx.takeScreenshot("esp-from-above");
        // FPS je Variante (gemittelt), Blick wieder auf die Wand
        srv.runCommand("tp @a 2000.5 -60 0.5 0 12");
        ctx.waitTicks(40);
        String[][] varianten = {{"Outline", "false"}, {"Outline", "true"}, {"Outline + Fill", "false"}, {"Outline + Fill", "true"}, {"aus", "false"}};
        for (String[] v : varianten) {
            ctx.runOnClient(mc -> {
                boolean aus = v[0].equals("aus");
                for (Class<? extends com.vortex.client.module.Module> c : java.util.List.of(
                        com.vortex.client.module.modules.BlockEspModule.class,
                        com.vortex.client.module.modules.ContainerEspModule.class,
                        com.vortex.client.module.modules.SpawnerEspModule.class)) {
                    var mod = ModuleManager.INSTANCE.get(c);
                    mod.setEnabled(!aus);
                    for (var st : mod.getSettings()) {
                        if (st.getName().equals("Style") && st instanceof com.vortex.client.core.setting.ModeSetting ms && !aus) ms.set(v[0]);
                        if (st.getName().equals("Glow") && st instanceof com.vortex.client.core.setting.BooleanSetting bs) bs.set(Boolean.parseBoolean(v[1]));
                    }
                }
            });
            ctx.waitTicks(60);
            int summe = 0;
            for (int i = 0; i < 6; i++) { ctx.waitTicks(20); summe += ctx.computeOnClient(mc -> mc.getFps()); }
            notiz("fps " + v[0] + (v[0].equals("aus") ? "" : " glow=" + v[1]) + ": " + (summe / 6));
        }
        ctx.runOnClient(mc -> {
            for (Class<? extends com.vortex.client.module.Module> c : java.util.List.of(
                    com.vortex.client.module.modules.BlockEspModule.class,
                    com.vortex.client.module.modules.ContainerEspModule.class,
                    com.vortex.client.module.modules.SpawnerEspModule.class)) ModuleManager.INSTANCE.get(c).setEnabled(true);
        });
        ctx.waitTicks(40);
        notiz("profiler: " + ctx.computeOnClient(mc -> com.vortex.client.core.Profiler.summary()).replace('\n', '|'));
        String fehler = ctx.computeOnClient(mc -> com.vortex.client.core.Errors.summary());
        notiz("errors: " + fehler.replace('\n', '|'));
        int n = ctx.computeOnClient(mc -> com.vortex.client.core.Errors.count("BlockEsp") + com.vortex.client.core.Errors.count("ContainerEsp"));
        ctx.runOnClient(mc -> {
            ModuleManager.INSTANCE.get(com.vortex.client.module.modules.BlockEspModule.class).setEnabled(false);
            ModuleManager.INSTANCE.get(com.vortex.client.module.modules.ContainerEspModule.class).setEnabled(false);
            ModuleManager.INSTANCE.get(com.vortex.client.module.modules.SpawnerEspModule.class).setEnabled(false);
        });
        pruefe("ESP drew without errors", n == 0, n + " errors");
    }

    // ------------------------------------------------------------------
    // FREECAM (nur mit -PbotOnly=freecam): Sicht unter der Erde, F5

    private static void freecamPos(double x, double y, double z) {
        try {
            Class<?> f = Class.forName("com.vortex.client.freecam.Freecam");
            for (String n : new String[]{"x", "y", "z"}) {
                var fld = f.getDeclaredField(n);
                fld.setAccessible(true);
                fld.setDouble(null, n.equals("x") ? x : n.equals("y") ? y : z);
            }
            var p = f.getDeclaredField("pitch"); p.setAccessible(true); p.setFloat(null, 35f);
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    private static void modul(ClientGameTestContext ctx, String name, boolean an) {
        ctx.runOnClient(mc -> {
            for (var m : ModuleManager.INSTANCE.getModules()) if (m.getName().equals(name)) m.setEnabled(an);
        });
    }

    private int sichtbar(ClientGameTestContext ctx) {
        ctx.waitTicks(30);
        return ctx.computeOnClient(mc -> mc.levelRenderer.visibleSections().size());
    }

    private void freecamTest(ClientGameTestContext ctx, TestServerContext srv) {
        srv.runCommand("time set noon");
        srv.runCommand("gamemode creative @a");
        srv.runCommand("tp @a 3000.5 -20 0.5");
        ctx.waitTicks(40);
        // 64x64 Stein bis y=-24, darin drei Hoehlengaenge
        for (int x = 2968; x < 3032; x += 16)
            for (int z = -32; z < 32; z += 16)
                srv.runCommand("fill " + x + " -60 " + z + " " + (x + 15) + " -25 " + (z + 15) + " minecraft:stone");
        srv.runCommand("fill 2975 -50 -2 3025 -48 2 minecraft:air");
        srv.runCommand("fill 2998 -40 -25 3002 -38 25 minecraft:air");
        srv.runCommand("fill 2980 -56 -20 3020 -54 -16 minecraft:air");
        srv.runCommand("fill 2980 -24 -32 3031 -24 31 minecraft:grass_block");
        srv.runCommand("tp @a 3000.5 -23 0.5 0 35");
        ctx.waitTicks(100);
        int oben = sichtbar(ctx);
        // Freecam im Stein (zwischen den Gaengen), Blick schraeg nach unten
        modul(ctx, "Freecam", true);
        ctx.waitTicks(5);
        freecamPos(3000.5, -44.5, -8.5);
        int frei = sichtbar(ctx);
        ctx.takeScreenshot("freecam-in-stone");
        // Zum Vergleich: dasselbe mit F5 + Ghost View (Wall Vision)
        modul(ctx, "Ghost View", true);
        ctx.runOnClient(mc -> mc.options.setCameraType(net.minecraft.client.CameraType.THIRD_PERSON_BACK));
        freecamPos(3000.5, -44.5, -8.5);
        int ghost = sichtbar(ctx);
        ctx.takeScreenshot("freecam-f5-ghost-view");
        // F5 in der Freecam: Kamera steht hinter dem Freecam-Punkt (im Freien messen)
        modul(ctx, "Ghost View", false);
        freecamPos(3000.5, -10.5, 0.5);
        ctx.waitTicks(10);
        double abstand = ctx.computeOnClient(mc -> mc.gameRenderer.mainCamera().position()
                .distanceTo(new net.minecraft.world.phys.Vec3(3000.5, -10.5, 0.5)));
        ctx.takeScreenshot("freecam-f5-outside");
        ctx.runOnClient(mc -> mc.options.setCameraType(net.minecraft.client.CameraType.FIRST_PERSON));
        ctx.waitTicks(10);
        double abstandEgo = ctx.computeOnClient(mc -> mc.gameRenderer.mainCamera().position()
                .distanceTo(new net.minecraft.world.phys.Vec3(3000.5, -10.5, 0.5)));
        modul(ctx, "Freecam", false);
        srv.runCommand("gamemode survival @a");
        notiz("visible sections: above ground " + oben + ", freecam in stone " + frei + ", F5 + Ghost View " + ghost);
        notiz("camera distance from freecam point: F5 " + Math.round(abstand * 100) / 100.0 + ", first person " + Math.round(abstandEgo * 100) / 100.0);
        pruefe("freecam in stone sees as much as Ghost View", frei >= ghost * 0.9, frei + " vs " + ghost + " sections");
        pruefe("F5 stays F5 in freecam", abstand > 3.0 && abstandEgo < 0.1, "F5 " + abstand + ", first person " + abstandEgo);
    }

    // ------------------------------------------------------------------
    // NEW CHUNKS: Messung in einer normalen Welt (nur -PbotOnly=chunkstudy)
    //
    // Phase 1: Gebiet um x = 0..400 erzeugen, Welt schliessen (speichert).
    // Phase 2: Welt neu oeffnen, von x = 0 bis 1600 fliegen. Was in Phase 1
    // schon da war, ist ALT (von der Festplatte), alles andere NEU. Fuer jeden
    // Chunk wird festgehalten, was die Verfahren sagen.

    private static final java.util.Map<Long, String> STUDIE = new java.util.concurrent.ConcurrentHashMap<>();
    private static volatile int studiePhase = 0;
    private static boolean studieRegistriert = false;

    private void chunkStudie(ClientGameTestContext ctx) {
        java.util.Set<Long> phase1 = java.util.concurrent.ConcurrentHashMap.newKeySet();
        if (!studieRegistriert) {
            studieRegistriert = true;
            net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientChunkEvents.CHUNK_LOAD.register((level, chunk) -> {
                long c = chunk.getPos().pack();
                if (studiePhase == 1) { phase1.add(c); return; }
                if (studiePhase != 2 || STUDIE.containsKey(c)) return;
                var b = com.vortex.client.hud.ChunkPalette.pruefe(chunk);
                STUDIE.put(c, (phase1.contains(c) ? "old" : "new") + "\t" + b.abschnitte() + "\t" + b.sortiert());
            });
        }
        var bauer = ctx.worldBuilder().setUseConsistentSettings(false).adjustSettings(s -> s.setSeed("vortex-new-chunks"));
        net.fabricmc.fabric.api.client.gametest.v1.world.TestWorldSave save;
        studiePhase = 1;
        try (TestSingleplayerContext sp = bauer.create()) {
            TestServerContext srv = sp.getServer();
            srv.runCommand("gamemode spectator @a");
            for (int x = 0; x <= 400; x += 100) {
                srv.runCommand("tp @a " + x + " 120 0");
                sp.getConnection().waitForChunksDownload();
                ctx.waitTicks(20);
            }
            save = sp.getWorldSave();
            notiz("phase 1: " + phase1.size() + " chunks");
        }
        studiePhase = 2;
        try (TestSingleplayerContext sp = save.open()) {
            TestServerContext srv = sp.getServer();
            ctx.runOnClient(mc -> ModuleManager.INSTANCE.get(com.vortex.client.module.modules.NewChunksModule.class).setEnabled(true));
            for (int x = 0; x <= 1600; x += 64) {
                srv.runCommand("tp @a " + x + " 120 0");
                sp.getConnection().waitForChunksDownload();
                ctx.waitTicks(10);
                if (x == 800) ctx.takeScreenshot("newchunks-border");
            }
            ctx.waitTicks(60);
            ctx.takeScreenshot("newchunks-end");
            // Was sagt das Fluessigkeits-Verfahren (bis 2.39)?
            java.util.Set<Long> neu = feld("NEU"), alt = feld("ALT");
            java.util.List<String> zeilen = new ArrayList<>();
            zeilen.add("cx\tcz\ttruth\tsections\tsorted\tliquid");
            int[][] m = new int[2][4];      // [truth][liquid: none/new/old], palette: [.][3] richtig
            int pal = 0, palRichtig = 0, palOhne = 0;
            for (var e : STUDIE.entrySet()) {
                long c = e.getKey();
                String fl = neu.contains(c) ? "new" : alt.contains(c) ? "old" : "-";
                zeilen.add(net.minecraft.world.level.ChunkPos.getX(c) + "\t" + net.minecraft.world.level.ChunkPos.getZ(c) + "\t" + e.getValue() + "\t" + fl);
                String[] t = e.getValue().split("\t");
                int wahr = t[0].equals("new") ? 1 : 0;
                m[wahr][fl.equals("-") ? 0 : fl.equals("new") ? 1 : 2]++;
                int ab = Integer.parseInt(t[1]), so = Integer.parseInt(t[2]);
                if (ab < 2) { palOhne++; continue; }
                pal++;
                boolean sagtNeu = so < ab;
                if (sagtNeu == (wahr == 1)) palRichtig++;
            }
            try {
                Files.write(FabricLoader.getInstance().getGameDir().resolve("chunk-study.tsv"), zeilen, StandardCharsets.UTF_8);
            } catch (Exception ex) { notiz("write failed: " + ex); }
            notiz("chunks: old " + (m[0][0] + m[0][1] + m[0][2]) + ", new " + (m[1][0] + m[1][1] + m[1][2]));
            notiz("liquid method, truly OLD: none " + m[0][0] + ", says new " + m[0][1] + ", says old " + m[0][2]);
            notiz("liquid method, truly NEW: none " + m[1][0] + ", says new " + m[1][1] + ", says old " + m[1][2]);
            notiz("palette method: " + palRichtig + " of " + pal + " correct, " + palOhne + " without information");
            pruefe("palette method mostly right", pal > 0 && palRichtig >= pal * 0.95, palRichtig + "/" + pal);
        }
    }

    @SuppressWarnings("unchecked")
    private static java.util.Set<Long> feld(String name) {
        try {
            var f = com.vortex.client.hud.NewChunks.class.getDeclaredField(name);
            f.setAccessible(true);
            return (java.util.Set<Long>) f.get(null);
        } catch (Exception e) {
            return java.util.Set.of();
        }
    }

    // ------------------------------------------------------------------
    // W-TAP: 6 Sprint-Schlaege auf ein Schwein, wie weit fliegt es?

    private double[] wTapDurchgang(ClientGameTestContext ctx, TestServerContext srv, String modus) {
        srv.runCommand("tp @a 4000.5 -60 0.5 -90 0");
        srv.runCommand("kill @e[type=pig]");
        ctx.waitTicks(10);
        ctx.runOnClient(mc -> {
            var m = ModuleManager.INSTANCE.get(com.vortex.client.module.modules.WTapModule.class);
            m.setEnabled(!modus.equals("off"));
            if (!modus.equals("off")) m.mode.set(modus);
            mc.player.getInventory().setSelectedSlot(0);
            mc.options.keyUp.setDown(true);
            mc.options.keySprint.setDown(true);
        });
        ctx.waitTicks(15);
        double[] weg = new double[6];
        for (int i = 0; i < 6; i++) {
            double px = srv.computeOnServer(s -> spieler(s).getX());
            srv.runCommand("summon minecraft:pig " + (px + 2.6) + " -60 0.5 {NoGravity:0b,Silent:1b,attributes:[{id:\"minecraft:movement_speed\",base:0.0}]}");
            ctx.waitTicks(2);
            double x0 = srv.computeOnServer(s -> {
                var l = s.overworld().getEntitiesOfClass(net.minecraft.world.entity.animal.pig.Pig.class, new AABB(3990, -62, -5, 4300, -50, 5), e -> e.isAlive() && !e.isDeadOrDying());
                return l.isEmpty() ? Double.NaN : l.get(0).getX();
            });
            boolean sprint = ctx.computeOnClient(mc -> {
                var l = mc.level.getEntitiesOfClass(net.minecraft.world.entity.animal.pig.Pig.class, mc.player.getBoundingBox().inflate(5),
                        e -> e.isAlive() && !e.isDeadOrDying());          // nicht das sterbende vom letzten Schlag
                if (l.isEmpty()) return false;
                mc.gameMode.attack(mc.player, l.get(0));
                mc.player.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
                return true;
            });
            ctx.waitTicks(8);
            double x1 = srv.computeOnServer(s -> {
                var l = s.overworld().getEntitiesOfClass(net.minecraft.world.entity.animal.pig.Pig.class, new AABB(3990, -62, -5, 4300, -50, 5), e -> e.isAlive() && !e.isDeadOrDying());
                return l.isEmpty() ? Double.NaN : l.get(0).getX();
            });
            weg[i] = sprint ? Math.round((x1 - x0) * 100) / 100.0 : -1;
            srv.runOnServer(sv -> sv.overworld().getEntitiesOfClass(net.minecraft.world.entity.animal.pig.Pig.class,
                    new AABB(3990, -62, -5, 4300, -50, 5)).forEach(e -> e.discard()));
            ctx.waitTicks(6);
        }
        ctx.runOnClient(mc -> {
            mc.options.keyUp.setDown(false);
            mc.options.keySprint.setDown(false);
            ModuleManager.INSTANCE.get(com.vortex.client.module.modules.WTapModule.class).setEnabled(false);
        });
        ctx.waitTicks(10);
        notiz(modus + ": knockback per hit " + java.util.Arrays.toString(weg));
        return weg;
    }

    private void wTapTest(ClientGameTestContext ctx, TestServerContext srv) {
        srv.runCommand("tp @a 4000.5 -60 0.5");
        ctx.waitTicks(20);
        flaeche(srv, 3990, -6, 4200, 6);
        srv.runCommand("difficulty easy");
        srv.runCommand("clear @a");
        srv.runCommand("item replace entity @a hotbar.0 with minecraft:iron_sword");
        srv.runCommand("effect give @a minecraft:saturation 120 5 true");
        double[] aus = wTapDurchgang(ctx, srv, "off");
        double[] packet = wTapDurchgang(ctx, srv, "Packet");
        double[] legit = wTapDurchgang(ctx, srv, "Legit");
        srv.runCommand("difficulty peaceful");
        double stark = aus[0];
        int ausStark = 0, packetStark = 0, legitStark = 0;
        for (int i = 1; i < 6; i++) {
            if (aus[i] >= stark * 0.8) ausStark++;
            if (packet[i] >= stark * 0.8) packetStark++;
            if (legit[i] >= stark * 0.8) legitStark++;
        }
        notiz("hits 2-6 with full sprint knockback: off " + ausStark + "/5, Packet " + packetStark + "/5, Legit " + legitStark + "/5");
        pruefe("W-Tap Packet: every hit with sprint knockback", packetStark == 5, packetStark + "/5");
        pruefe("W-Tap Legit: every hit with sprint knockback", legitStark == 5, legitStark + "/5");
    }

    // ------------------------------------------------------------------
    // FREECAM: Sprint-Sprung, dann Freecam -- der Sprung muss weitergehen

    private void freecamSchwungTest(ClientGameTestContext ctx, TestServerContext srv) {
        srv.runCommand("gamemode survival @a");
        srv.runCommand("tp @a 5000.5 -60 0.5 -90 0");
        ctx.waitTicks(20);
        flaeche(srv, 4990, -6, 5060, 6);
        srv.runCommand("tp @a 5000.5 -60 0.5 -90 0");
        ctx.waitTicks(10);
        ctx.runOnClient(mc -> { mc.options.keyUp.setDown(true); mc.options.keySprint.setDown(true); });
        ctx.waitTicks(15);
        ctx.runOnClient(mc -> mc.options.keyJump.setDown(true));
        ctx.waitTicks(2);
        double xStart = srv.computeOnServer(s -> spieler(s).getX());
        modul(ctx, "Freecam", true);
        ctx.waitTicks(1);
        ctx.runOnClient(mc -> { mc.options.keyJump.setDown(false); });
        ctx.waitTicks(12);
        double xEnde = srv.computeOnServer(s -> spieler(s).getX());
        modul(ctx, "Freecam", false);
        ctx.runOnClient(mc -> { mc.options.keyUp.setDown(false); mc.options.keySprint.setDown(false); });
        double weiter = Math.round((xEnde - xStart) * 100) / 100.0;
        notiz("distance travelled after opening freecam mid-jump: " + weiter + " blocks");
        pruefe("freecam does not stop a running jump", weiter > 1.0, weiter + " blocks");
    }

    // ------------------------------------------------------------------
    // POTION HUD (nur -PbotOnly=potion): Screenshots

    private void potionTest(ClientGameTestContext ctx, TestServerContext srv) {
        srv.runCommand("tp @a 6000.5 -60 0.5 0 -10");
        ctx.waitTicks(20);
        flaeche(srv, 5990, -6, 6010, 20);
        srv.runCommand("effect clear @a");
        modul(ctx, "Potion Effects", true);
        srv.runCommand("effect give @a minecraft:speed 90 1");
        srv.runCommand("effect give @a minecraft:night_vision infinite 0");
        srv.runCommand("effect give @a minecraft:strength 8 1");
        ctx.waitTicks(3);
        ctx.takeScreenshot("potion-sliding-in");
        ctx.waitTicks(20);
        srv.runCommand("effect give @a minecraft:poison 3 0");
        srv.runCommand("effect give @a minecraft:fire_resistance 300 0");
        ctx.waitTicks(25);
        ctx.takeScreenshot("potion-list");
        ctx.waitTicks(40);
        ctx.takeScreenshot("potion-poison-gone");
        ctx.waitTicks(4);
        ctx.takeScreenshot("potion-after");
        String fehler = ctx.computeOnClient(mc -> com.vortex.client.core.Errors.summary());
        srv.runCommand("effect clear @a");
        pruefe("potion HUD drew without errors", !fehler.contains("PotionHud"), fehler.replace('\n', '|'));
    }

    // ------------------------------------------------------------------
    // GUI (nur -PbotOnly=gui): Bildschirme in 1920x1080, GUI-Skala 2 und 3

    private void guiBilder(ClientGameTestContext ctx) {
        ctx.getInput().resizeWindow(1920, 1080);
        ctx.waitTicks(20);
        for (int skala : new int[]{3, 2}) {
            ctx.runOnClient(mc -> { mc.options.guiScale().set(skala); mc.resizeGui(); });
            ctx.waitTicks(10);
            ctx.setScreen(() -> new net.minecraft.client.gui.screens.TitleScreen());
            ctx.waitTicks(40);
            ctx.takeScreenshot("title-s" + skala);
            ctx.setScreen(() -> new net.minecraft.client.gui.screens.worldselection.SelectWorldScreen(new net.minecraft.client.gui.screens.TitleScreen()));
            ctx.waitTicks(30);
            ctx.takeScreenshot("singleplayer-s" + skala);
            ctx.setScreen(() -> new net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen(new net.minecraft.client.gui.screens.TitleScreen()));
            ctx.waitTicks(30);
            ctx.takeScreenshot("multiplayer-s" + skala);
            ctx.setScreen(() -> new net.minecraft.client.gui.screens.TitleScreen());
            ctx.waitTicks(10);
        }
        try (TestSingleplayerContext sp = ctx.worldBuilder().create()) {
            sp.getServer().runCommand("time set noon");
            ctx.waitTicks(40);
            for (int skala : new int[]{3, 2}) {
                ctx.runOnClient(mc -> { mc.options.guiScale().set(skala); mc.resizeGui(); });
                ctx.waitTicks(10);
                ctx.setScreen(() -> new com.vortex.client.gui.HomeScreen());
                ctx.waitTicks(40);
                ctx.takeScreenshot("home-s" + skala);
                ctx.setScreen(() -> new com.vortex.client.gui.ClickGui());
                ctx.waitTicks(30);
                ctx.takeScreenshot("clickgui-s" + skala);
                ctx.setScreen(() -> new com.vortex.client.gui.PanelGui());
                ctx.waitTicks(30);
                ctx.takeScreenshot("panelgui-s" + skala);
                ctx.setScreen(() -> null);
                ctx.waitTicks(5);
            }
            String fehler = ctx.computeOnClient(mc -> com.vortex.client.core.Errors.summary());
            notiz("errors: " + fehler.replace('\n', '|'));
            pruefe("screens drew without errors", !fehler.contains("Screen") && !fehler.contains("Gui"), "");
        }
    }

    // ------------------------------------------------------------------
    // CROP

    private void cropTest(ClientGameTestContext ctx, TestServerContext srv) {
        srv.runCommand("tp @a 3.5 -60 -4.5 0 20");
        ctx.waitTicks(40);
        flaeche(srv, -6, -12, 20, 20);
        srv.runCommand("fill 2 -61 2 10 -61 10 minecraft:farmland[moisture=7]");
        srv.runCommand("fill 2 -61 6 10 -61 6 minecraft:water");
        srv.runCommand("fill 2 -60 2 10 -60 5 minecraft:wheat[age=7]");
        srv.runCommand("fill 2 -60 7 10 -60 10 minecraft:wheat[age=7]");
        srv.runCommand("fill -4 -60 0 9 -60 0 minecraft:oak_fence");       // Zaun: aussen herum (x >= 10)
        srv.runCommand("setblock 13 -60 6 minecraft:chest");
        srv.runCommand("tp @a 3.5 -60 -4.5 0 20");
        srv.runCommand("clear @a");
        srv.runCommand("give @a minecraft:bread 16");
        srv.runCommand("give @a minecraft:cobblestone 2048");               // 32 Stapel: Inventar fast voll
        srv.runCommand("effect give @a minecraft:hunger 20 10 true");       // hungrig machen -> muss essen
        ctx.waitTicks(40);
        int ackerVorher = zaehle(srv, 2, -61, 2, 10, -61, 10, st -> st.is(Blocks.FARMLAND));

        ctx.runOnClient(mc -> {
            CropFarmerModule m = ModuleManager.INSTANCE.get(CropFarmerModule.class);
            m.range.set(16);
            m.store.set(true);
            m.eat.set(true);
            m.defend.set(true);
            m.stayNearStart.set(true);
            m.setEnabled(true);
        });

        int reif = -1;
        for (int t = 0; t < 3000; t += 100) {
            ctx.waitTicks(100);
            if (t == 200) ctx.takeScreenshot("crop-start");
            if (t == 1000) ctx.takeScreenshot("crop-middle");
            if (t % 500 == 0) notiz("t=" + t + "  status: " + botStatus(ctx, CropFarmerModule.class));
            reif = zaehle(srv, 2, -60, 2, 10, -60, 10, st -> st.getBlock() instanceof CropBlock c && c.isMaxAge(st));
            if (reif == 0 && t >= 400) {
                // Zeit zum Nachpflanzen (Samen muessen erst eingesammelt werden): bis 60 s
                for (int w = 0; w < 1200; w += 100) {
                    ctx.waitTicks(100);
                    if (zaehle(srv, 2, -60, 2, 10, -60, 10, st -> st.is(Blocks.WHEAT)) >= 72) break;
                }
                break;
            }
        }
        ctx.takeScreenshot("crop-end");
        notiz("end status: " + botStatus(ctx, CropFarmerModule.class));

        int weizen = zaehle(srv, 2, -60, 2, 10, -60, 10, st -> st.is(Blocks.WHEAT));
        int acker = zaehle(srv, 2, -61, 2, 10, -61, 10, st -> st.is(Blocks.FARMLAND));
        notiz("trampled (dirt) at: " + srv.computeOnServer(s -> {
            List<String> l = new ArrayList<>();
            for (BlockPos b : BlockPos.betweenClosed(2, -61, 2, 10, -61, 10)) if (s.overworld().getBlockState(b).is(Blocks.DIRT)) l.add(b.toShortString());
            return l;
        }));
        int truhe = srv.computeOnServer(s -> {
            if (s.overworld().getBlockEntity(new BlockPos(13, -60, 6)) instanceof Container c) {
                int n = 0;
                for (int i = 0; i < c.getContainerSize(); i++) {
                    ItemStack st = c.getItem(i);
                    if (st.is(Items.WHEAT) || st.is(Items.WHEAT_SEEDS)) n += st.getCount();
                }
                return n;
            }
            return -1;
        });
        float leben = srv.computeOnServer(s -> spieler(s).getHealth());
        int hunger = srv.computeOnServer(s -> spieler(s).getFoodData().getFoodLevel());
        pruefe("all ripe wheat harvested", reif == 0, reif + " ripe left of 72");
        pruefe("wheat replanted", weizen >= 70, weizen + " of 72 spots have wheat again");
        pruefe("no farmland trampled", acker == ackerVorher, acker + " of " + ackerVorher + " farmland left");
        pruefe("harvest stored in the chest", truhe > 0, truhe + " wheat/seeds in the chest");
        pruefe("player healthy", leben >= 14, "health " + leben);
        pruefe("player ate", hunger >= 10, "food level " + hunger);
    }

    // ------------------------------------------------------------------
    // TREE

    private void treeTest(ClientGameTestContext ctx, TestServerContext srv) {
        srv.runCommand("tp @a 100.5 -60 0.5");
        ctx.waitTicks(40);
        flaeche(srv, 88, -10, 124, 26);
        srv.runCommand("place feature minecraft:oak 104 -60 6");
        srv.runCommand("place feature minecraft:oak 111 -60 12");
        srv.runCommand("place feature minecraft:birch 99 -60 15");
        srv.runCommand("place feature minecraft:spruce 92 -60 5");
        tieferBaum(srv, 114, -6);
        srv.runCommand("tp @a 100.5 -60 0.5");
        srv.runCommand("clear @a");
        srv.runCommand("give @a minecraft:iron_axe 1");
        srv.runCommand("give @a minecraft:bread 16");
        srv.runCommand("give @a minecraft:dirt 32");
        ctx.waitTicks(40);
        int stammVorher = zaehle(srv, 90, -60, -8, 122, -44, 24, st -> st.is(BlockTags.LOGS));
        notiz("logs before: " + stammVorher);
        List<BlockPos> basen = srv.computeOnServer(s -> {
            List<BlockPos> l = new ArrayList<>();
            for (BlockPos b : BlockPos.betweenClosed(90, -60, -8, 122, -60, 24)) {
                if (s.overworld().getBlockState(b).is(BlockTags.LOGS)) l.add(b.immutable());
            }
            return l;
        });
        notiz("tree bases: " + basen);

        ctx.runOnClient(mc -> {
            TreeFarmerModule m = ModuleManager.INSTANCE.get(TreeFarmerModule.class);
            m.range.set(16);
            m.replant.set(true);
            m.collect.set(true);
            m.pillar.set(true);
            m.stayNearStart.set(true);
            m.setEnabled(true);
        });

        int stamm = -1;
        for (int t = 0; t < 4800; t += 100) {
            ctx.waitTicks(100);
            if (t == 300) ctx.takeScreenshot("tree-start");
            if (t == 1500) ctx.takeScreenshot("tree-middle");
            if (t % 600 == 0) notiz("t=" + t + "  status: " + botStatus(ctx, TreeFarmerModule.class));
            stamm = zaehle(srv, 90, -60, -8, 122, -44, 24, st -> st.is(BlockTags.LOGS));
            if (stamm == 0 && t >= 600) {
                // Laub zerfaellt langsam und wirft erst dann Setzlinge ab: bis 2 Minuten warten
                for (int w = 0; w < 2400; w += 100) {
                    ctx.waitTicks(100);
                    int sz = zaehle(srv, 90, -60, -8, 122, -60, 24,
                            st -> st.getBlock() instanceof net.minecraft.world.level.block.SaplingBlock);
                    if (sz >= 3) break;
                }
                break;
            }
        }
        ctx.takeScreenshot("tree-end");
        notiz("saplings now at: " + srv.computeOnServer(s -> {
            List<String> l = new ArrayList<>();
            for (BlockPos b : BlockPos.betweenClosed(90, -61, -8, 122, -58, 24)) {
                if (s.overworld().getBlockState(b).getBlock() instanceof net.minecraft.world.level.block.SaplingBlock) l.add(b.toShortString());
            }
            return l;
        }));
        for (BlockPos bb : basen) {
            int[] b = {bb.getX(), bb.getZ()};
            notiz("tree spot " + b[0] + "/" + b[1] + ": " + srv.computeOnServer(s -> {
                StringBuilder sb = new StringBuilder();
                for (int y = -62; y <= -57; y++) {
                    sb.append(y).append('=').append(net.minecraft.core.registries.BuiltInRegistries.BLOCK
                            .getKey(s.overworld().getBlockState(new BlockPos(b[0], y, b[1])).getBlock()).getPath()).append(' ');
                }
                return sb.toString();
            }));
        }
        notiz("end status: " + botStatus(ctx, TreeFarmerModule.class));
        stamm = zaehle(srv, 90, -60, -8, 122, -44, 24, st -> st.is(BlockTags.LOGS));
        int setzlinge = zaehle(srv, 90, -60, -8, 122, -60, 24,
                st -> st.getBlock() instanceof net.minecraft.world.level.block.SaplingBlock);
        double y = srv.computeOnServer(s -> spieler(s).getY());
        int holz = srv.computeOnServer(s -> {
            int n = 0;
            var inv = spieler(s).getInventory();
            for (int i = 0; i < 36; i++) if (inv.getItem(i).is(net.minecraft.tags.ItemTags.LOGS)) n += inv.getItem(i).getCount();
            return n;
        });
        pruefe("all trees chopped", stamm == 0, stamm + " logs left of " + stammVorher);
        pruefe("saplings replanted", setzlinge >= 3, setzlinge + " saplings in the ground (3 trees)");
        notiz("low-crown tree trunk left: " + zaehle(srv, 114, -60, -6, 114, -52, -6, st -> st.is(BlockTags.LOGS)));
        pruefe("wood collected", holz >= stammVorher * 9 / 10, holz + " logs in the inventory of " + stammVorher);
        pruefe("not stuck on a tower", y < -58.5, "player y " + y);
    }

    /**
     * Baum, wie ihn der Tree Farmer bis 2.35 nicht schaffte: Krone 5x5 ab einem
     * Block ueber dem Boden (darunter kommt man nicht durch), Stamm 6 hoch.
     */
    private static void tieferBaum(TestServerContext srv, int x, int z) {
        String laub = "minecraft:oak_leaves[persistent=false,distance=1]";
        srv.runCommand("fill " + (x - 2) + " -59 " + (z - 2) + " " + (x + 2) + " -57 " + (z + 2) + " " + laub);
        srv.runCommand("fill " + (x - 1) + " -56 " + (z - 1) + " " + (x + 1) + " -54 " + (z + 1) + " " + laub);
        srv.runCommand("fill " + x + " -60 " + z + " " + x + " -55 " + z + " minecraft:oak_log");
    }

    private void treeOhneSammeln(ClientGameTestContext ctx, TestServerContext srv) {
        srv.runCommand("tp @a 150.5 -60 0.5");
        ctx.waitTicks(40);
        flaeche(srv, 140, -8, 162, 14);
        tieferBaum(srv, 152, 6);
        srv.runCommand("tp @a 150.5 -60 0.5");
        srv.runCommand("clear @a");
        srv.runCommand("give @a minecraft:iron_axe 1");
        srv.runCommand("give @a minecraft:bread 16");
        srv.runCommand("give @a minecraft:dirt 32");
        ctx.waitTicks(40);
        int vorher = zaehle(srv, 142, -60, -6, 160, -48, 12, st -> st.is(BlockTags.LOGS));
        ctx.runOnClient(mc -> {
            TreeFarmerModule m = ModuleManager.INSTANCE.get(TreeFarmerModule.class);
            m.range.set(12);
            m.collect.set(false);
            m.replant.set(false);
            m.stayNearStart.set(true);
            m.setEnabled(true);
        });
        int rest = vorher;
        for (int t = 0; t < 2400 && rest > 0; t += 100) {
            ctx.waitTicks(100);
            rest = zaehle(srv, 142, -60, -6, 160, -48, 12, st -> st.is(BlockTags.LOGS));
        }
        ctx.waitTicks(100);
        ctx.takeScreenshot("tree-nocollect-end");
        notiz("end status: " + botStatus(ctx, TreeFarmerModule.class));
        int holz = srv.computeOnServer(s -> {
            int n = 0;
            var inv = spieler(s).getInventory();
            for (int i = 0; i < 36; i++) if (inv.getItem(i).is(net.minecraft.tags.ItemTags.LOGS)) n += inv.getItem(i).getCount();
            return n;
        });
        ctx.runOnClient(mc -> ModuleManager.INSTANCE.get(TreeFarmerModule.class).collect.set(true));
        pruefe("low-crown tree chopped (Collect Drops off)", rest == 0, rest + " logs left of " + vorher);
        pruefe("its wood landed in the inventory anyway", holz >= vorher - 1, holz + " of " + vorher + " logs in the inventory");
    }

    // ------------------------------------------------------------------
    // DEFENCE

    private void defenceTest(ClientGameTestContext ctx, TestServerContext srv) {
        srv.runCommand("tp @a 200.5 -60 0.5");
        ctx.waitTicks(40);
        flaeche(srv, 190, -10, 212, 12);
        srv.runCommand("clear @a");
        srv.runCommand("give @a minecraft:iron_sword 1");
        srv.runCommand("give @a minecraft:bread 16");
        srv.runCommand("effect clear @a");
        srv.runCommand("tp @a 200.5 -60 0.5");
        ctx.runOnClient(mc -> {
            CropFarmerModule m = ModuleManager.INSTANCE.get(CropFarmerModule.class);
            m.defend.set(true);
            m.stopHealth.set(4);
            m.setEnabled(true);
        });
        srv.runCommand("time set midnight");
        srv.runCommand("difficulty easy");
        srv.runCommand("summon minecraft:zombie 204.5 -60 0.5");
        ctx.waitTicks(40);
        ctx.takeScreenshot("defence-start");
        int monster = -1;
        for (int t = 0; t < 1200; t += 20) {
            ctx.waitTicks(20);
            monster = srv.computeOnServer(s -> s.overworld().getEntitiesOfClass(Monster.class,
                    new AABB(180, -70, -20, 220, -40, 20)).size());
            if (monster == 0) break;
        }
        ctx.takeScreenshot("defence-end");
        notiz("end status: " + botStatus(ctx, CropFarmerModule.class));
        float leben = srv.computeOnServer(s -> spieler(s).getHealth());
        boolean lebt = srv.computeOnServer(s -> spieler(s).isAlive());
        pruefe("zombie killed", monster == 0, monster + " monsters left");
        pruefe("player survived", lebt && leben > 4, "health " + leben);
        srv.runCommand("difficulty peaceful");
        srv.runCommand("time set day");
    }

    // ------------------------------------------------------------------
    // ELYTRA: fast kaputte Elytra an, Ersatz im Inventar, 300 Bloecke weit

    private static int raketenIm(MinecraftServer s) {
        int n = 0;
        var inv = spieler(s).getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) if (inv.getItem(i).is(Items.FIREWORK_ROCKET)) n += inv.getItem(i).getCount();
        return n;
    }

    private void elytraStart(ClientGameTestContext ctx, TestServerContext srv, int zx, int zz) {
        srv.runCommand("clear @a");
        srv.runCommand("effect clear @a");
        srv.runCommand("effect give @a minecraft:instant_health 1 10 true");
        srv.runCommand("item replace entity @a armor.chest with minecraft:elytra");
        srv.runCommand("give @a minecraft:firework_rocket 64");
        ctx.waitTicks(40);
        ctx.runOnClient(mc -> {
            var m = ModuleManager.INSTANCE.get(com.vortex.client.module.modules.ElytraAutopilotModule.class);
            m.cruiseY.set(64);
            m.land.set(true);
            m.rockets.set(true);
            m.minDurability.set(20);
            mc.getConnection().sendCommand("autopilot " + zx + " " + zz);
        });
    }

    /**
     * Wand direkt vor dem Spieler, Ziel dahinter. Bis 2.36 zuendete der Pilot
     * eine Rakete nach der anderen in die Wand (Aufprallschaden bis zum Tod).
     */
    private void elytraWandTest(ClientGameTestContext ctx, TestServerContext srv) {
        srv.runCommand("tp @a 1000.5 -60 0.5 -90 0");
        ctx.waitTicks(40);
        flaeche(srv, 985, -15, 1110, 15);
        srv.runCommand("fill 1002 -60 -15 1002 -25 15 minecraft:stone");
        srv.runCommand("tp @a 1000.5 -60 0.5 -90 0");
        elytraStart(ctx, srv, 1090, 0);
        float minLeben = 20;
        boolean an = true;
        for (int t = 0; t < 2400; t += 10) {
            ctx.waitTicks(10);
            minLeben = Math.min(minLeben, srv.computeOnServer(s -> spieler(s).getHealth()));
            if (t == 60) ctx.takeScreenshot("elytra-wall-start");
            if (t % 200 == 0) notiz("t=" + t + "  status: " + botStatus(ctx, com.vortex.client.module.modules.ElytraAutopilotModule.class));
            an = ctx.computeOnClient(mc -> ModuleManager.INSTANCE.get(com.vortex.client.module.modules.ElytraAutopilotModule.class).isEnabled());
            if (!an) break;
            if (minLeben <= 0) break;
        }
        ctx.takeScreenshot("elytra-wall-end");
        int raketen = 64 - srv.computeOnServer(BotGameTest::raketenIm);
        double x = srv.computeOnServer(s -> spieler(s).getX());
        boolean lebt = srv.computeOnServer(s -> spieler(s).isAlive());
        notiz("end status: " + botStatus(ctx, com.vortex.client.module.modules.ElytraAutopilotModule.class));
        ctx.runOnClient(mc -> mc.getConnection().sendCommand("autopilot stop"));
        if (!lebt) srv.runCommand("kill @e[type=item]");
        pruefe("survived the wall", lebt && minLeben >= 14, "lowest health " + minLeben);
        pruefe("no rocket spam", raketen <= 20, raketen + " rockets used");
        pruefe("got past the wall", x > 1003, "player x " + Math.round(x));
    }

    /** Eingemauert (kein Weg hinaus): keine einzige Rakete, kein Schaden. */
    private void elytraKastenTest(ClientGameTestContext ctx, TestServerContext srv) {
        srv.runCommand("tp @a 1200.5 -60 0.5");
        ctx.waitTicks(40);
        flaeche(srv, 1190, -10, 1210, 10);
        srv.runCommand("fill 1197 -61 -3 1203 -55 3 minecraft:stone hollow");
        srv.runCommand("tp @a 1200.5 -60 0.5");
        elytraStart(ctx, srv, 1300, 0);
        float minLeben = 20;
        for (int t = 0; t < 400; t += 10) {
            ctx.waitTicks(10);
            minLeben = Math.min(minLeben, srv.computeOnServer(s -> spieler(s).getHealth()));
        }
        int raketen = 64 - srv.computeOnServer(BotGameTest::raketenIm);
        notiz("end status: " + botStatus(ctx, com.vortex.client.module.modules.ElytraAutopilotModule.class));
        ctx.runOnClient(mc -> mc.getConnection().sendCommand("autopilot stop"));
        pruefe("no rockets while boxed in", raketen == 0, raketen + " rockets used");
        pruefe("no damage while boxed in", minLeben >= 19, "lowest health " + minLeben);
    }

    private void elytraTest(ClientGameTestContext ctx, TestServerContext srv) {
        srv.runCommand("tp @a 400.5 -60 0.5");
        ctx.waitTicks(40);
        flaeche(srv, 395, -5, 405, 5);
        srv.runCommand("clear @a");
        srv.runCommand("effect clear @a");
        srv.runCommand("item replace entity @a armor.chest with minecraft:elytra[minecraft:damage=420]");
        srv.runCommand("give @a minecraft:elytra 1");
        srv.runCommand("give @a minecraft:firework_rocket 64");
        srv.runCommand("give @a minecraft:bread 8");
        ctx.waitTicks(20);
        ctx.runOnClient(mc -> {
            var m = ModuleManager.INSTANCE.get(com.vortex.client.module.modules.ElytraAutopilotModule.class);
            m.cruiseY.set(64);
            m.land.set(true);
            m.rockets.set(true);
            m.minDurability.set(20);
            mc.getConnection().sendCommand("autopilot 700 0");
        });
        boolean an = true;
        for (int t = 0; t < 3600; t += 20) {
            ctx.waitTicks(20);
            if (t == 200) ctx.takeScreenshot("elytra-flying");
            if (t % 400 == 0) notiz("t=" + t + "  status: " + botStatus(ctx, com.vortex.client.module.modules.ElytraAutopilotModule.class));
            an = ctx.computeOnClient(mc -> ModuleManager.INSTANCE.get(com.vortex.client.module.modules.ElytraAutopilotModule.class).isEnabled());
            if (!an) break;
        }
        ctx.takeScreenshot("elytra-end");
        notiz("client: " + ctx.computeOnClient(mc -> "fallFlying=" + mc.player.isFallFlying() + " onGround=" + mc.player.onGround()
                + " y=" + Math.round(mc.player.getY() * 10) / 10.0 + " motion=" + mc.player.getDeltaMovement()));
        double x = srv.computeOnServer(s -> spieler(s).getX());
        double z = srv.computeOnServer(s -> spieler(s).getZ());
        float leben = srv.computeOnServer(s -> spieler(s).getHealth());
        boolean amBoden = srv.computeOnServer(s -> spieler(s).onGround());
        int brustRest = srv.computeOnServer(s -> {
            ItemStack b = spieler(s).getItemBySlot(net.minecraft.world.entity.EquipmentSlot.CHEST);
            return b.is(Items.ELYTRA) ? b.getMaxDamage() - b.getDamageValue() : -1;
        });
        double abstand = Math.sqrt((x - 700) * (x - 700) + z * z);
        pruefe("autopilot finished by itself", !an, an ? "still flying after 3 min" : "stopped");
        pruefe("landed near the target", abstand <= 60, Math.round(abstand) + " blocks from 700/0");
        pruefe("on the ground", amBoden, "onGround=" + amBoden);
        pruefe("no fall damage", leben >= 18, "health " + leben);
        pruefe("switched to the spare elytra", brustRest > 100, "worn elytra durability " + brustRest);
    }

    // ------------------------------------------------------------------

    private static int zaehle(TestServerContext srv, int x1, int y1, int z1, int x2, int y2, int z2,
                              java.util.function.Predicate<BlockState> passt) {
        return srv.computeOnServer(s -> {
            ServerLevel l = s.overworld();
            int n = 0;
            for (BlockPos b : BlockPos.betweenClosed(x1, y1, z1, x2, y2, z2)) if (passt.test(l.getBlockState(b))) n++;
            return n;
        });
    }
}
