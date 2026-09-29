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
 *   TREE     drei Eichen. Geprueft: alle Staemme weg, Setzlinge gepflanzt,
 *            Bot nicht auf einem Turm haengen geblieben.
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
        try (TestSingleplayerContext sp = ctx.worldBuilder().create()) {
            TestServerContext srv = sp.getServer();
            srv.runCommand("gamemode survival @a");
            srv.runCommand("difficulty peaceful");
            srv.runCommand("time set day");
            srv.runCommand("weather clear 1000000");
            ctx.waitTicks(20);

            abschnitt(ctx, "Crop Farmer", () -> cropTest(ctx, srv));
            abschnitt(ctx, "Tree Farmer", () -> treeTest(ctx, srv));
            abschnitt(ctx, "Defence", () -> defenceTest(ctx, srv));
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
            if (reif == 0 && t >= 400) break;
        }
        ctx.takeScreenshot("crop-end");
        notiz("end status: " + botStatus(ctx, CropFarmerModule.class));

        int weizen = zaehle(srv, 2, -60, 2, 10, -60, 10, st -> st.is(Blocks.WHEAT));
        int acker = zaehle(srv, 2, -61, 2, 10, -61, 10, st -> st.is(Blocks.FARMLAND));
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
        pruefe("wheat replanted", weizen >= 60, weizen + " of 72 spots have wheat again");
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
        srv.runCommand("tp @a 100.5 -60 0.5");
        srv.runCommand("clear @a");
        srv.runCommand("give @a minecraft:iron_axe 1");
        srv.runCommand("give @a minecraft:bread 16");
        srv.runCommand("give @a minecraft:dirt 32");
        ctx.waitTicks(40);
        int stammVorher = zaehle(srv, 90, -60, -8, 122, -44, 24, st -> st.is(BlockTags.LOGS));
        notiz("logs before: " + stammVorher);

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
        pruefe("wood collected", holz >= stammVorher / 2, holz + " logs in the inventory");
        pruefe("not stuck on a tower", y < -58.5, "player y " + y);
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
