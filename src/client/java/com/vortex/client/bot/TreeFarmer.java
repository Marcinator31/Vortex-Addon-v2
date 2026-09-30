package com.vortex.client.bot;

import com.vortex.client.cheat.Inv;
import com.vortex.client.module.ModuleManager;
import com.vortex.client.module.modules.TreeFarmerModule;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Tree Farmer (siehe TreeFarmerModule).
 *
 *   SUCHEN        erst Aufgaben rund um die Farm (einlagern, liegengebliebene
 *                 Setzlinge nachpflanzen, Holz/Setzlinge einsammeln), dann den
 *                 naechsten Baum finden (Stamm auf Erde + natuerliches Laub)
 *   HINGEHEN      mit Weg direkt neben den Stamm laufen (Laub im Weg wird
 *                 weggeschlagen -- auch unter tief haengende Kronen)
 *   FAELLEN       die untersten zwei Stammbloecke abbauen, in die Stammspalte
 *                 treten und von dort den Rest (fuer hohe Baeume hochbauen).
 *                 Abgebaut wird NUR, was zu sehen ist; verdeckt Laub den Stamm,
 *                 kommt erst das Laub weg -- nie durch Blaetter oder Bloecke
 *                 hindurch. Aus der Spalte fallen die Stammbloecke dem Bot direkt
 *                 vor die Fuesse.
 *   ABSTEIGEN     den eigenen Turm wieder abbauen
 *   PFLANZEN      einen Schritt zur Seite, Setzling setzen (fehlt einer: merken)
 *   SAMMELN       Holz und Setzlinge einsammeln; liegt etwas oben auf Laub,
 *                 wird das Laub darunter weggeschlagen, damit es herunterfaellt
 *   NACHPFLANZEN  gemerkte Stelle bepflanzen, sobald ein Setzling da ist
 */
public final class TreeFarmer {

    private TreeFarmer() {}

    private enum Phase { SUCHEN, HINGEHEN, FAELLEN, ABSTEIGEN, PFLANZEN, SAMMELN, NACHPFLANZEN, DUENGEN }

    private static final BotMotor MOTOR = new BotMotor();
    private static final BotEssen ESSEN = new BotEssen("Tree Farmer");
    private static final BotLager LAGER = new BotLager("Tree Farmer");
    private static final BotSchutz SCHUTZ = new BotSchutz("Tree Farmer");
    /** Wo der Bot eingeschaltet wurde -- Mitte des Arbeitsbereichs. */
    private static BlockPos startPos = null;
    private static long tick = 0;
    private static boolean warAn = false;
    private static Phase phase = Phase.SUCHEN;
    private static long phaseSeit = 0;

    private static BlockPos basis = null;
    private static Set<BlockPos> stamm = new HashSet<>();
    private static Item setzling = null;
    private static BlockPos abbau = null;
    /** Wohin beim Abbauen geschaut wird (die sichtbare Stelle). */
    private static Vec3 abbauPunkt = null;
    /** Laubbloecke, die fuer diesen Baum schon weggeschlagen wurden (Obergrenze gegen Kahlschlag). */
    private static int laubGeschlagen = 0;
    /** Kam der Bot nicht direkt neben den Stamm? Dann nur in Reichweite gehen. */
    private static boolean nurReichweite = false;
    private static int standY = Integer.MIN_VALUE;
    private static boolean keinTurmGemeldet = false;
    private static boolean turmGemeldet = false;
    private static final Map<BlockPos, Long> GESPERRT = new HashMap<>();
    /** Gefaellte Baeume, fuer die noch ein Setzling fehlt: Boden-Position (Erde) +1. */
    private static final Set<BlockPos> OFFEN = new LinkedHashSet<>();
    private static final Map<BlockPos, Integer> PFLANZ_VERSUCHE = new HashMap<>();
    /**
     * Seit wann an einer Stelle ein Setzling zu sehen ist. Erst nach 2 s gilt
     * sie als erledigt: der Client zeigt einen gesetzten Block sofort an --
     * lehnt der Server ab, verschwindet er einen Moment spaeter wieder
     * (gefunden im Bot-Test: die Stelle wurde abgehakt und blieb leer).
     */
    private static final Map<BlockPos, Long> GESEHEN_SEIT = new HashMap<>();
    private static BlockPos pflanzZiel = null;
    private static long nichtsLiegtSeit = -1;
    private static ItemEntity sammelZiel = null;
    private static BlockPos duengZiel = null;
    private static long duengZuletzt = -100;
    /** Wie oft ein Setzling schon Knochenmehl bekam (waechst er nie, wird er ausgelassen). */
    private static final Map<BlockPos, Integer> DUENG_ZAEHLER = new HashMap<>();

    private static int baeume = 0, stammBloecke = 0;
    private static long startZeit = 0;
    private static String status = "Idle";

    public static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            tick++;
            try { tick(mc); } catch (Throwable e) {
                com.vortex.client.core.Errors.report("TreeFarmer", e);
                MOTOR.aus(mc);
                ESSEN.aus(mc, mc.player);
                LAGER.aus(mc, mc.player);
                wechsel(Phase.SUCHEN);
            }
        });
    }

    /** Statuszeile fuer die Bot-Seite. */
    public static String status() {
        TreeFarmerModule m = ModuleManager.INSTANCE.get(TreeFarmerModule.class);
        if (m == null || !m.isEnabled()) return "Idle";
        long min = startZeit == 0 ? 0 : (System.currentTimeMillis() - startZeit) / 60000L;
        return status + "  |  " + baeume + " trees, " + stammBloecke + " logs"
                + (LAGER.gelagert() > 0 ? "  |  " + LAGER.gelagert() + " stacks stored" : "")
                + (OFFEN.isEmpty() ? "" : "  |  " + OFFEN.size() + " to replant") + "  |  " + min + " min";
    }

    private static void wechsel(Phase p) {
        phase = p;
        phaseSeit = tick;
        abbau = null;
        abbauPunkt = null;
        MOTOR.fortschrittZuruecksetzen();
        MOTOR.vergiss();
    }

    /** Protokoll fuer die Bot-Tests (nur mit -Dvortex.bot.debug=true, sonst still). */
    private static final boolean DEBUG = Boolean.getBoolean("vortex.bot.debug");
    private static void dbg(String text) {
        if (DEBUG) System.out.println("[TreeFarmer] t=" + tick + " " + text);
    }

    private static void melde(LocalPlayer p, String text) {
        p.sendSystemMessage(Component.literal("§d[Tree Farmer]§r " + text));
    }

    private static void tick(Minecraft mc) {
        TreeFarmerModule m = ModuleManager.INSTANCE.get(TreeFarmerModule.class);
        LocalPlayer p = mc.player;
        boolean an = m != null && m.isEnabled() && p != null && mc.level != null && mc.gameMode != null;
        if (!an) {
            if (warAn) {
                MOTOR.aus(mc);
                ESSEN.aus(mc, p);
                LAGER.zuruecksetzen(mc, p);
                SCHUTZ.zuruecksetzen(p);
                wechsel(Phase.SUCHEN);
                basis = null;
                GESPERRT.clear();
                DUENG_ZAEHLER.clear();
                PFLANZ_VERSUCHE.clear();
                GESEHEN_SEIT.clear();
                duengZiel = null;
                if (p != null && baeume > 0) {
                    long min = (System.currentTimeMillis() - startZeit) / 60000L;
                    melde(p, "Stopped: " + baeume + " trees (" + stammBloecke + " logs) in " + min + " min.");
                }
            }
            warAn = false;
            return;
        }
        if (!warAn) { baeume = 0; stammBloecke = 0; startZeit = System.currentTimeMillis(); OFFEN.clear(); startPos = p.blockPosition().immutable(); }
        warAn = true;
        MOTOR.freischneiden = true;             // Laub auf dem Weg darf weg (nur natuerliches)
        if (!p.isAlive()) { MOTOR.anhalten(mc); return; }
        GESPERRT.values().removeIf(bis -> bis < tick);
        try {
            // Sicherheit zuerst -- auch auf dem Weg zur Truhe (nicht bei offenem Fenster)
            if (mc.gui.screen() == null) {
                BotSchutz.Lage lage = SCHUTZ.tick(mc, p, MOTOR, tick, m.defend.get(), m.stopHealth.getInt(), m.playerPause.getInt());
                if (lage != BotSchutz.Lage.OK) {
                    ESSEN.aus(mc, p);                      // sonst bleibt die Benutzen-Taste gedrueckt
                    if (LAGER.aktiv()) LAGER.aus(mc, p);
                    MOTOR.fortschrittZuruecksetzen();
                    if (abbau != null) { try { mc.gameMode.stopDestroyBlock(); } catch (Throwable ignored) { } abbau = null; }
                    MOTOR.springen(mc, false);
                    phaseSeit++;                           // Pause/Kampf zaehlt nicht gegen die Phasen-Fristen
                    if (lage == BotSchutz.Lage.STOP) { m.setEnabled(false); return; }
                    status = lage == BotSchutz.Lage.KAMPF ? "Fighting" : "Paused: " + SCHUTZ.pauseGrund() + " nearby";
                    return;
                }
            }
            if (LAGER.aktiv()) {
                status = "Storing in chest";
                LAGER.tick(mc, p, MOTOR, tick, TreeFarmer::einlagern, behalten());
                return;
            }
            if (mc.gui.screen() != null) { MOTOR.anhalten(mc); status = "Paused (menu open)"; return; }
            // Nicht mitten im Turmbau essen -- oben auf dem Turm waere das riskant.
            boolean ruhig = phase != Phase.FAELLEN && phase != Phase.ABSTEIGEN;
            if (!m.eat.get()) ESSEN.aus(mc, p);
            else if ((ruhig || ESSEN.isst()) && ESSEN.tick(mc, p, tick, 14)) { MOTOR.anhalten(mc); status = "Eating"; return; }
            switch (phase) {
                case SUCHEN -> suchen(mc, p, m);
                case HINGEHEN -> hingehen(mc, p);
                case FAELLEN -> faellen(mc, p, m);
                case ABSTEIGEN -> absteigen(mc, p);
                case PFLANZEN -> pflanzen(mc, p, m);
                case SAMMELN -> sammeln(mc, p, m);
                case NACHPFLANZEN -> nachpflanzen(mc, p);
                case DUENGEN -> duengen(mc, p);
            }
        } finally {
            MOTOR.drehen(p, 30f);
        }
    }

    // ------------------------------------------------------------------

    private static void suchen(Minecraft mc, LocalPlayer p, TreeFarmerModule m) {
        MOTOR.anhalten(mc);
        status = "Looking for trees";
        if (tick % 20 != 0) return;
        int r = m.range.getInt();

        // 1. Inventar (fast) voll -> einlagern
        if (m.store.get() && BotMotor.freiePlaetze(p) <= 2
                && LAGER.starten(mc, p, tick, r + 8, TreeFarmer::einlagern, behalten())) return;

        // 2. Gemerkte Stellen nachpflanzen, sobald ein Setzling da ist
        if (m.replant.get() && !OFFEN.isEmpty()) {
            OFFEN.removeIf(b -> {
                if (!pflanzboden(mc.level.getBlockState(b.below()))) { dbg("drop " + b.toShortString() + ": no dirt below"); PFLANZ_VERSUCHE.remove(b); GESEHEN_SEIT.remove(b); return true; }
                BlockState da = mc.level.getBlockState(b);
                if (da.isAir()) { GESEHEN_SEIT.remove(b); return false; }
                long seit = GESEHEN_SEIT.computeIfAbsent(b, k -> tick);
                boolean setzling = da.getBlock() instanceof net.minecraft.world.level.block.SaplingBlock || da.is(BlockTags.LOGS);
                // Setzling (oder schon ein Baum): nach 2 s bestaetigt. Etwas anderes
                // (Block im Weg): noch eine Minute warten, dann aufgeben.
                if (tick - seit < (setzling ? 40 : 1200)) return false;
                dbg("done " + b.toShortString() + ": " + da);
                PFLANZ_VERSUCHE.remove(b);
                GESEHEN_SEIT.remove(b);
                return true;
            });
            if (hatSetzling(p, null)) {
                BlockPos naechste = null;
                for (BlockPos b : OFFEN) {
                    if (GESPERRT.containsKey(b)) continue;
                    if (naechste == null || p.distanceToSqr(Vec3.atCenterOf(b)) < p.distanceToSqr(Vec3.atCenterOf(naechste))) naechste = b;
                }
                if (naechste != null) { pflanzZiel = naechste; wechsel(Phase.NACHPFLANZEN); return; }
            }
        }

        // 3. Knochenmehl auf einen Setzling im Bereich (optional) -- der naechste Baum waechst sofort
        boolean hatKnochenmehl = Inv.hotbar(p, st -> st.is(Items.BONE_MEAL)) >= 0 || Inv.inventar(p, st -> st.is(Items.BONE_MEAL)) >= 0;
        if (m.boneMeal.get() && hatKnochenmehl) {
            BlockPos z = null;
            double zd = Double.MAX_VALUE;
            BlockPos c = mitte(p, m);
            for (BlockPos b : BlockPos.betweenClosed(c.offset(-r, -4, -r), c.offset(r, 6, r))) {
                if (GESPERRT.containsKey(b) || !duengbar(mc.level.getBlockState(b))) continue;
                double d = p.distanceToSqr(Vec3.atCenterOf(b));
                if (d < zd) { zd = d; z = b.immutable(); }
            }
            if (z != null) { duengZiel = z; wechsel(Phase.DUENGEN); return; }
        }

        // 4. Liegengebliebenes Holz / Setzlinge einsammeln (Laub zerfaellt langsam)
        if (m.collect.get() && BotMotor.freiePlaetze(p) > 0 && naechsterDrop(mc, p, new AABB(mitte(p, m)).inflate(r, 10, r)) != null) {
            basis = null;
            nichtsLiegtSeit = -1;
            wechsel(Phase.SAMMELN);
            return;
        }

        // 5. Naechster Baum
        BlockPos mitte = mitte(p, m);
        BlockPos best = null;
        Set<BlockPos> bestStamm = null;
        double bestD = Double.MAX_VALUE;
        for (BlockPos b : BlockPos.betweenClosed(mitte.offset(-r, -4, -r), mitte.offset(r, 6, r))) {
            if (GESPERRT.containsKey(b)) continue;
            BlockState st = mc.level.getBlockState(b);
            if (!st.is(BlockTags.LOGS) || !pflanzboden(mc.level.getBlockState(b.below()))) continue;
            double d = p.distanceToSqr(Vec3.atCenterOf(b));
            if (d >= bestD) continue;
            Set<BlockPos> s = baum(mc, b.immutable());
            if (s == null) { GESPERRT.put(b.immutable(), tick + 6000); continue; }
            best = b.immutable();
            bestStamm = s;
            bestD = d;
        }
        if (best == null) { status = OFFEN.isEmpty() ? "No trees in range -- waiting" : "Waiting for saplings"; return; }
        basis = best;
        stamm = bestStamm;
        setzling = passenderSetzling(mc.level.getBlockState(best));
        dbg("tree at " + best.toShortString() + " with " + bestStamm.size() + " logs");
        standY = Integer.MIN_VALUE;
        keinTurmGemeldet = false;
        turmGemeldet = false;
        laubGeschlagen = 0;
        nurReichweite = false;
        wechsel(Phase.HINGEHEN);
    }

    /** Alle Stammbloecke eines echten Baums ab basis -- oder null (kein Baum / zu gross). */
    private static Set<BlockPos> baum(Minecraft mc, BlockPos basis) {
        // 2x2-Stamm? Dann auslassen.
        for (Direction d : Direction.Plane.HORIZONTAL) {
            BlockPos n = basis.relative(d);
            if (mc.level.getBlockState(n).is(BlockTags.LOGS) && pflanzboden(mc.level.getBlockState(n.below()))) return null;
        }
        Set<BlockPos> s = new HashSet<>();
        ArrayDeque<BlockPos> offen = new ArrayDeque<>();
        offen.add(basis);
        s.add(basis);
        boolean laub = false;
        while (!offen.isEmpty()) {
            BlockPos b = offen.poll();
            for (int dx = -1; dx <= 1; dx++) for (int dy = 0; dy <= 1; dy++) for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dy == 0 && dz == 0) continue;
                BlockPos n = b.offset(dx, dy, dz);
                if (Math.abs(n.getX() - basis.getX()) > 6 || Math.abs(n.getZ() - basis.getZ()) > 6 || n.getY() - basis.getY() > 32) continue;
                BlockState st = mc.level.getBlockState(n);
                if (st.getBlock() instanceof LeavesBlock && !st.getValue(LeavesBlock.PERSISTENT)) laub = true;
                if (st.is(BlockTags.LOGS) && s.add(n)) {
                    if (s.size() > 80) return null;
                    offen.add(n);
                }
            }
        }
        return laub ? s : null;
    }

    private static Item passenderSetzling(BlockState stammSt) {
        try {
            String id = BuiltInRegistries.BLOCK.getKey(stammSt.getBlock()).getPath();
            String art = id.replace("stripped_", "").replace("_log", "").replace("_wood", "").replace("_stem", "");
            var rl = net.minecraft.resources.Identifier.tryParse("minecraft:" + art + "_sapling");
            if (art.equals("mangrove")) rl = net.minecraft.resources.Identifier.tryParse("minecraft:mangrove_propagule");
            if (rl == null) return null;
            return BuiltInRegistries.ITEM.getOptional(rl).orElse(null);
        } catch (Throwable e) {
            return null;
        }
    }

    private static void hingehen(Minecraft mc, LocalPlayer p) {
        if (basis == null || !mc.level.getBlockState(basis).is(BlockTags.LOGS)) { wechsel(Phase.SUCHEN); return; }
        status = "Walking to a tree";
        double reichweite = p.blockInteractionRange() - 0.5;
        Vec3 c = Vec3.atCenterOf(basis);
        // Da: direkt neben dem Stamm -- oder (wenn das nicht geht) der Stamm ist von hier zu sehen
        boolean daneben = neben(p, basis);
        boolean sichtbar = nurReichweite && p.getEyePosition().distanceToSqr(c) <= reichweite * reichweite
                && schritt(mc, p, basis, false) != null;
        if ((daneben || sichtbar) && p.onGround()) {
            MOTOR.anhalten(mc);
            wechsel(Phase.FAELLEN);
            return;
        }
        BotWeg.Ziel ziel = nurReichweite ? BotWeg.inReichweite(c.x, c.y, c.z, reichweite - 0.3, 1.62)
                : BotWeg.nahBei(c.x, basis.getY() + 0.5, c.z, 1.5);
        BotMotor.Lauf l = MOTOR.laufe(mc, p, ziel, nurReichweite ? (Object) basis.above(100) : basis, tick);
        if (l == BotMotor.Lauf.UNERREICHBAR && !nurReichweite) {
            dbg("cannot get next to " + basis.toShortString() + " -- trying from a distance");
            nurReichweite = true;
            return;
        }
        if (l == BotMotor.Lauf.UNERREICHBAR || tick - phaseSeit > 20 * 60) {
            GESPERRT.put(basis, tick + 2400);
            MOTOR.anhalten(mc);
            wechsel(Phase.SUCHEN);
        }
    }

    /** Steht der Spieler direkt neben (oder in) der Stammspalte, etwa auf Stammhoehe? */
    private static boolean neben(LocalPlayer p, BlockPos b) {
        double dx = p.getX() - (b.getX() + 0.5), dz = p.getZ() - (b.getZ() + 0.5);
        return dx * dx + dz * dz <= 1.6 * 1.6 && Math.abs(p.getY() - b.getY()) <= 1.1;
    }

    /** Was als naechstes abgebaut wird, um an einen Block zu kommen, und wohin man dabei schaut. */
    private record Schritt(BlockPos pos, Vec3 punkt) {}

    /**
     * Freie Sicht auf "ziel"? Dann Schritt(ziel, sichtbare Stelle). Verdeckt
     * natuerliches Laub (oder ein anderer Stammblock dieses Baums) in Reichweite
     * die Sicht und darf Laub weg ("laub"), ist das der Schritt. Sonst (Erde,
     * Stein, ein Bauwerk dazwischen, zu weit) null -- abgebaut wird nie durch
     * etwas hindurch.
     */
    private static Schritt schritt(Minecraft mc, LocalPlayer p, BlockPos ziel, boolean laub) {
        Vec3 auge = p.getEyePosition();
        double r = p.blockInteractionRange() - 0.3;
        double r2 = r * r;
        Vec3 mitte = Vec3.atCenterOf(ziel);
        // Blockmitte und die Mitten der Seiten, die zum Spieler zeigen (knapp innen)
        java.util.List<Vec3> punkte = new java.util.ArrayList<>(4);
        punkte.add(mitte);
        if (auge.x < ziel.getX()) punkte.add(mitte.add(-0.45, 0, 0)); else if (auge.x > ziel.getX() + 1) punkte.add(mitte.add(0.45, 0, 0));
        if (auge.y < ziel.getY()) punkte.add(mitte.add(0, -0.45, 0)); else if (auge.y > ziel.getY() + 1) punkte.add(mitte.add(0, 0.45, 0));
        if (auge.z < ziel.getZ()) punkte.add(mitte.add(0, 0, -0.45)); else if (auge.z > ziel.getZ() + 1) punkte.add(mitte.add(0, 0, 0.45));
        for (Vec3 pt : punkte) {
            if (auge.distanceToSqr(pt) > r2) continue;
            BlockHitResult hr = mc.level.clip(new ClipContext(auge, pt, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, p));
            if (hr.getType() == HitResult.Type.BLOCK && hr.getBlockPos().equals(ziel)) return new Schritt(ziel, pt);
        }
        if (!laub || auge.distanceToSqr(mitte) > (r + 1.5) * (r + 1.5)) return null;
        BlockHitResult hr = mc.level.clip(new ClipContext(auge, mitte, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, p));
        if (hr.getType() != HitResult.Type.BLOCK || hr.getBlockPos().equals(ziel)) return null;
        BlockPos vor = hr.getBlockPos().immutable();
        BlockState st = mc.level.getBlockState(vor);
        boolean weg = BotMotor.natuerlichesLaub(st) || (st.is(BlockTags.LOGS) && stamm.contains(vor));
        if (!weg || auge.distanceToSqr(hr.getLocation()) > r2) return null;
        return new Schritt(vor, hr.getLocation());
    }

    private static void faellen(Minecraft mc, LocalPlayer p, TreeFarmerModule m) {
        status = "Chopping";
        if (tick - phaseSeit > 20 * 120) { melde(p, "Tree took too long -- skipping it."); wechsel(Phase.ABSTEIGEN); return; }
        stamm.removeIf(b -> !mc.level.getBlockState(b).is(BlockTags.LOGS));
        if (stamm.isEmpty()) { MOTOR.anhalten(mc); baeume++; wechsel(Phase.ABSTEIGEN); return; }

        if (abbau != null) {
            MOTOR.anhalten(mc);
            boolean holz = mc.level.getBlockState(abbau).is(BlockTags.LOGS);
            if (holz && m.useAxe.get()) axt(p);
            if (MOTOR.abbauen(mc, p, abbau, tick, abbauPunkt)) {
                if (holz && mc.level.getBlockState(abbau).isAir()) stammBloecke++;
                abbau = null;
            }
            return;
        }
        boolean inSpalte = Math.abs(p.getX() - (basis.getX() + 0.5)) < 0.3 && Math.abs(p.getZ() - (basis.getZ() + 0.5)) < 0.3;
        // Offen = die untersten zwei Stammbloecke sind weg, man kann in die Spalte treten
        boolean offen = !stamm.contains(basis) && !stamm.contains(basis.above());
        // Laub wegschlagen: fuer die untersten zwei Bloecke und aus der Spalte heraus --
        // von draussen sonst nicht (dafuer geht der Bot lieber in die Spalte).
        boolean laubOk = laubGeschlagen < 64;
        Schritt best = null;
        BlockPos bestZiel = null;
        Vec3 auge = p.getEyePosition();
        for (BlockPos b : stamm) {
            boolean unten = b.equals(basis) || b.equals(basis.above());
            if (!offen && !unten) continue;
            if (bestZiel != null && (b.getY() > bestZiel.getY()
                    || (b.getY() == bestZiel.getY() && auge.distanceToSqr(Vec3.atCenterOf(b)) >= auge.distanceToSqr(Vec3.atCenterOf(bestZiel))))) continue;
            Schritt s = schritt(mc, p, b, laubOk && (unten || inSpalte));
            if (s == null) continue;
            best = s;
            bestZiel = b;
        }
        if (best != null) {
            MOTOR.anhalten(mc);
            abbau = best.pos();
            abbauPunkt = best.punkt();
            boolean holz = mc.level.getBlockState(abbau).is(BlockTags.LOGS);
            if (!holz) { laubGeschlagen++; dbg("leaves in the way at " + abbau.toShortString() + " (for log " + bestZiel.toShortString() + ")"); }
            if (holz && m.useAxe.get()) axt(p);
            if (MOTOR.abbauen(mc, p, abbau, tick, abbauPunkt)) {
                if (holz && mc.level.getBlockState(abbau).isAir()) stammBloecke++;
                abbau = null;
            }
            return;
        }
        if (!offen) {
            // Die untersten Bloecke sind von hier nicht zu sehen/erreichen: naeher heran
            if (tick - phaseSeit > 20 * 30) { GESPERRT.put(basis, tick + 2400); melde(p, "Can't reach the trunk at " + basis.toShortString() + " -- skipping that tree."); wechsel(Phase.ABSTEIGEN); return; }
            Vec3 c = Vec3.atCenterOf(basis);
            if (MOTOR.laufe(mc, p, BotWeg.nahBei(c.x, basis.getY() + 0.5, c.z, 1.5), basis.below(100), tick) == BotMotor.Lauf.UNERREICHBAR) {
                GESPERRT.put(basis, tick + 2400);
                wechsel(Phase.ABSTEIGEN);
            }
            return;
        }
        // Nichts mehr zu sehen: in die Stammspalte treten (Laub auf dem Weg wird weggeschlagen), dann hochbauen.
        if (!inSpalte) {
            boolean imFeld = p.blockPosition().getX() == basis.getX() && p.blockPosition().getZ() == basis.getZ();
            if (imFeld) {
                double d = MOTOR.gehe(mc, p, Vec3.atBottomCenterOf(basis), false);
                if (d < 0.25) MOTOR.anhalten(mc);
                if (MOTOR.steckt(p, tick)) wechsel(Phase.ABSTEIGEN);
                return;
            }
            BotMotor.Lauf l = MOTOR.laufe(mc, p, BotWeg.feld(basis.getX(), basis.getY(), basis.getZ()), basis.below(200), tick);
            if (l == BotMotor.Lauf.UNERREICHBAR) { dbg("cannot step into the trunk column " + basis.toShortString()); wechsel(Phase.ABSTEIGEN); }
            return;
        }
        MOTOR.anhalten(mc);
        if (!m.pillar.get() || !lohntHochbauen(p)) { wechsel(Phase.ABSTEIGEN); return; }
        status = "Building up";
        hochbauen(mc, p);
    }

    /** Springen und im hoechsten Punkt einen Block unter die Fuesse setzen. */
    private static void hochbauen(Minecraft mc, LocalPlayer p) {
        int slot = Inv.hotbar(p, TreeFarmer::turmBlock);
        if (slot < 0) slot = BotMotor.holeInHotbar(mc, p, TreeFarmer::turmBlock);
        if (slot < 0) {
            if (!keinTurmGemeldet) { keinTurmGemeldet = true; melde(p, "Tree is taller than my reach and I have no blocks to build up -- leaving the top."); }
            wechsel(Phase.ABSTEIGEN);
            return;
        }
        if (p.onGround()) {
            // Laub (oder anderes) ueber dem Kopf? Erst weg damit -- sonst springt
            // der Bot nur auf der Stelle.
            BlockPos kopf = new BlockPos(basis.getX(), (int) Math.floor(p.getY() + 0.01) + 2, basis.getZ());
            BlockState ks = mc.level.getBlockState(kopf);
            if (!ks.isAir() && !ks.canBeReplaced()) {
                MOTOR.springen(mc, false);
                if (!(ks.getBlock() instanceof LeavesBlock)) werkzeug(p, ks);   // Schere auf Laub: keine Setzlinge
                MOTOR.abbauen(mc, p, kopf, tick);
                return;
            }
            standY = (int) Math.floor(p.getY() + 0.01);
            MOTOR.blickeRichtung(p.getYRot(), 90f);
            MOTOR.springen(mc, true);
            return;
        }
        MOTOR.springen(mc, false);
        BlockPos fuss = new BlockPos(basis.getX(), standY, basis.getZ());
        if (p.getY() >= standY + 1.0 && mc.level.getBlockState(fuss).canBeReplaced()) {
            BlockPos unten = fuss.below();
            Vec3 treffer = new Vec3(unten.getX() + 0.5, unten.getY() + 1.0, unten.getZ() + 0.5);
            int vorher = p.getInventory().getSelectedSlot();
            try {
                p.getInventory().setSelectedSlot(slot);
                mc.gameMode.useItemOn(p, InteractionHand.MAIN_HAND, new BlockHitResult(treffer, Direction.UP, unten, false));
                p.swing(InteractionHand.MAIN_HAND);
            } finally {
                p.getInventory().setSelectedSlot(vorher);
            }
        }
    }

    /**
     * Nur Bloecke, die sich mit Axt oder Hand schnell wieder abbauen lassen --
     * mit Stein oder Obsidian unter den Fuessen kaeme der Bot nicht mehr
     * herunter.
     */
    private static boolean turmBlock(ItemStack st) {
        return st.is(ItemTags.LOGS) || st.is(ItemTags.PLANKS) || st.is(Items.DIRT) || st.is(Items.COARSE_DIRT)
                || st.is(Items.ROOTED_DIRT);
    }

    /** Gibt es noch Stamm ueber dem Kopf, den man vom Turm aus erreicht? */
    private static boolean lohntHochbauen(LocalPlayer p) {
        double reichweite = p.blockInteractionRange() - 1.0;
        double augeY = p.getEyeY();
        for (BlockPos b : stamm) {
            double dx = b.getX() - basis.getX(), dz = b.getZ() - basis.getZ();
            if (b.getY() + 0.5 > augeY && Math.sqrt(dx * dx + dz * dz) <= reichweite) return true;
        }
        return false;
    }

    /** Schnellstes Werkzeug der Hotbar fuer diesen Block in die Hand. */
    private static void werkzeug(LocalPlayer p, BlockState st) {
        int best = -1;
        float bestTempo = 1.0f;
        for (int i = 0; i < 9; i++) {
            ItemStack it = p.getInventory().getItem(i);
            if (it.isEmpty() || (it.isDamageableItem() && it.getMaxDamage() - it.getDamageValue() <= 3)) continue;
            float tempo = it.getDestroySpeed(st);
            if (tempo > bestTempo) { bestTempo = tempo; best = i; }
        }
        if (best >= 0 && p.getInventory().getSelectedSlot() != best) p.getInventory().setSelectedSlot(best);
    }

    private static void absteigen(Minecraft mc, LocalPlayer p) {
        MOTOR.anhalten(mc);
        status = "Coming down";
        if (basis == null) { wechsel(Phase.SUCHEN); return; }
        if (tick - phaseSeit > 20 * 60) {
            // Nie oben auf dem Turm stehen lassen -- von dort findet kein Weg herunter.
            if (p.getY() <= basis.getY() + 1.5) { wechsel(Phase.PFLANZEN); return; }
            if (!turmGemeldet) { turmGemeldet = true; melde(p, "I can't get down from my tower -- please help (break the block under me)."); }
        }
        // Nur den eigenen Turm in der Stammspalte abbauen -- steht der Bot
        // woanders (z. B. hangaufwaerts), wird NICHT in den Boden gegraben.
        boolean inSpalte = p.blockPosition().getX() == basis.getX() && p.blockPosition().getZ() == basis.getZ();
        if (!inSpalte) { wechsel(Phase.PFLANZEN); return; }
        if (p.getY() <= basis.getY() + 0.1) {
            if (p.onGround()) wechsel(Phase.PFLANZEN);
            return;
        }
        if (abbau == null) {
            if (!p.onGround()) return;
            BlockPos unter = p.blockPosition().below();
            if (unter.getY() < basis.getY()) { wechsel(Phase.PFLANZEN); return; }
            abbau = unter;
        }
        werkzeug(p, mc.level.getBlockState(abbau));
        if (MOTOR.abbauen(mc, p, abbau, tick)) abbau = null;
    }

    private static void pflanzen(Minecraft mc, LocalPlayer p, TreeFarmerModule m) {
        if (!m.replant.get() || basis == null || !mc.level.getBlockState(basis).isAir()
                || !pflanzboden(mc.level.getBlockState(basis.below()))) {
            // Steht dort noch etwas (unterster Turmblock, ein Stamm, den der Server
            // noch nicht bestaetigt hat ...)? Trotzdem vormerken -- sonst bliebe die
            // Stelle fuer immer leer (gefunden im Bot-Test).
            if (m.replant.get() && basis != null && pflanzboden(mc.level.getBlockState(basis.below()))) OFFEN.add(basis.immutable());
            dbg("replant skipped at " + (basis == null ? "-" : basis.toShortString() + " (block there: " + mc.level.getBlockState(basis) + ", below: " + mc.level.getBlockState(basis.below()) + ")") + " -> queued=" + (basis != null && OFFEN.contains(basis)));
            nichtsLiegtSeit = -1;
            wechsel(Phase.SAMMELN);
            return;
        }
        status = "Replanting";
        int slot = setzlingPlatz(mc, p, setzling);
        if (slot < 0) {
            // Kein Setzling (noch): merken und spaeter nachpflanzen.
            OFFEN.add(basis.immutable());
            dbg("no sapling for " + basis.toShortString() + " -> queued");
            nichtsLiegtSeit = -1;
            wechsel(Phase.SAMMELN);
            return;
        }
        // Steht der Bot noch in der Stammspalte, erst einen Schritt zur Seite.
        if (p.getBoundingBox().intersects(new AABB(basis))) {
            BlockPos seite = sichereSeite(mc, basis);
            if (seite == null || tick - phaseSeit > 100) { OFFEN.add(basis.immutable()); wechsel(Phase.SAMMELN); return; }
            MOTOR.gehe(mc, p, Vec3.atBottomCenterOf(seite), false);
            return;
        }
        MOTOR.anhalten(mc);
        double reichweite = p.blockInteractionRange() - 0.5;
        Vec3 boden = new Vec3(basis.getX() + 0.5, basis.getY(), basis.getZ() + 0.5);
        boolean nah = p.getEyePosition().distanceToSqr(boden) <= reichweite * reichweite;
        if (nah) setzen(mc, p, basis, slot);
        dbg("planting at " + basis.toShortString() + (nah ? " (clicked)" : " (out of reach)") + " -> queued for confirmation");
        // Erst als erledigt zaehlen, wenn der Setzling wirklich steht (prueft SUCHEN) --
        // ein still abgelehnter Klick liesse die Stelle sonst leer.
        OFFEN.add(basis.immutable());
        nichtsLiegtSeit = -1;
        wechsel(Phase.SAMMELN);
    }

    private static void nachpflanzen(Minecraft mc, LocalPlayer p) {
        BlockPos z = pflanzZiel;
        if (z == null || !mc.level.getBlockState(z).isAir() || !pflanzboden(mc.level.getBlockState(z.below()))) {
            wechsel(Phase.SUCHEN);                              // abgehakt wird in SUCHEN (nach Bestaetigung)
            return;
        }
        status = "Replanting a missing sapling";
        int slot = setzlingPlatz(mc, p, null);
        if (slot < 0) { wechsel(Phase.SUCHEN); return; }
        double reichweite = p.blockInteractionRange() - 0.5;
        Vec3 boden = new Vec3(z.getX() + 0.5, z.getY(), z.getZ() + 0.5);
        boolean steht = p.getBoundingBox().intersects(new AABB(z));
        if (!steht && p.getEyePosition().distanceToSqr(boden) <= reichweite * reichweite && p.onGround()) {
            MOTOR.anhalten(mc);
            setzen(mc, p, z, slot);
            dbg("replanting later at " + z.toShortString() + " (try " + (PFLANZ_VERSUCHE.getOrDefault(z, 0) + 1) + ")");
            // Bleibt in OFFEN, bis der Setzling wirklich steht; nach 3 vergeblichen Versuchen aufgeben
            if (PFLANZ_VERSUCHE.merge(z, 1, Integer::sum) > 3) {
                OFFEN.remove(z);
                PFLANZ_VERSUCHE.remove(z);
                melde(p, "Could not plant a sapling at " + z.toShortString() + " -- skipping that spot.");
            }
            wechsel(Phase.SUCHEN);
            return;
        }
        if (steht) {                                                    // erst vom Fleck gehen
            BlockPos seite = sichereSeite(mc, z);
            if (seite == null || tick - phaseSeit > 20 * 10) { GESPERRT.put(z, tick + 2400); wechsel(Phase.SUCHEN); return; }
            MOTOR.gehe(mc, p, Vec3.atBottomCenterOf(seite), false);
            return;
        }
        BotMotor.Lauf l = MOTOR.laufe(mc, p, BotWeg.inReichweite(boden.x, boden.y, boden.z, reichweite - 0.4, 1.62), z, tick);
        if (l == BotMotor.Lauf.UNERREICHBAR || tick - phaseSeit > 20 * 45) {
            GESPERRT.put(z, tick + 2400);
            wechsel(Phase.SUCHEN);
        }
    }

    /** Mitte des Arbeitsbereichs: der Startpunkt (oder der Spieler, wenn "Stay Near Start" aus ist). */
    private static BlockPos mitte(LocalPlayer p, TreeFarmerModule m) {
        return m.stayNearStart.get() && startPos != null ? startPos : p.blockPosition();
    }

    /**
     * Setzling, der mit Knochenmehl sicher waechst: keine 2x2-Arten
     * (Dunkel-/Blasseiche wachsen einzeln nie -- das Knochenmehl waere weg) und
     * keine, die breit wachsen (Kirsche, Akazie, Mangrove -- deren Holz koennte
     * in den Spieler hineinwachsen).
     */
    private static boolean duengbar(BlockState st) {
        if (!(st.getBlock() instanceof net.minecraft.world.level.block.SaplingBlock)) return false;
        return !(st.is(net.minecraft.world.level.block.Blocks.DARK_OAK_SAPLING) || st.is(net.minecraft.world.level.block.Blocks.PALE_OAK_SAPLING)
                || st.is(net.minecraft.world.level.block.Blocks.CHERRY_SAPLING) || st.is(net.minecraft.world.level.block.Blocks.ACACIA_SAPLING)
                || st.is(net.minecraft.world.level.block.Blocks.MANGROVE_PROPAGULE));
    }

    /** Mit Abstand (3.6-4 Bloecke, gleiche Hoehe) an den Setzling stellen und Knochenmehl geben. */
    private static void duengen(Minecraft mc, LocalPlayer p) {
        BlockPos z = duengZiel;
        if (z == null || !duengbar(mc.level.getBlockState(z))) { wechsel(Phase.SUCHEN); return; }      // gewachsen
        int slot = Inv.hotbar(p, st -> st.is(Items.BONE_MEAL));
        if (slot < 0 && Inv.inventar(p, st -> st.is(Items.BONE_MEAL)) < 0) { wechsel(Phase.SUCHEN); return; }
        if (tick - phaseSeit > 20 * 40) { GESPERRT.put(z, tick + 2400); wechsel(Phase.SUCHEN); return; }
        status = "Using bone meal on a sapling";
        Vec3 c = Vec3.atCenterOf(z);
        double fx = c.x - p.getX(), fz = c.z - p.getZ();
        double flach = Math.sqrt(fx * fx + fz * fz);
        double reichweite = p.blockInteractionRange() - 0.2;
        boolean bereit = flach >= 3.4 && Math.abs(BotMotor.fussY(mc.level, p) - z.getY()) <= 1
                && p.getEyePosition().distanceToSqr(c) <= reichweite * reichweite && p.onGround();
        if (bereit) {
            MOTOR.anhalten(mc);
            MOTOR.blicke(p, c);
            if (tick - duengZuletzt < 10) return;
            duengZuletzt = tick;
            int n = DUENG_ZAEHLER.merge(z, 1, Integer::sum);
            if (n > 8) {
                DUENG_ZAEHLER.remove(z);
                GESPERRT.put(z, tick + 12000);
                melde(p, "Sapling at " + z.toShortString() + " does not grow (not enough room?) -- skipping it.");
                wechsel(Phase.SUCHEN);
                return;
            }
            if (slot < 0) slot = BotMotor.holeInHotbar(mc, p, st -> st.is(Items.BONE_MEAL));
            if (slot < 0) return;
            int vorher = p.getInventory().getSelectedSlot();
            try {
                p.getInventory().setSelectedSlot(slot);
                mc.gameMode.useItemOn(p, InteractionHand.MAIN_HAND,
                        new BlockHitResult(c, Direction.getApproximateNearest(p.getEyePosition().subtract(c)), z, false));
                p.swing(InteractionHand.MAIN_HAND);
            } finally {
                p.getInventory().setSelectedSlot(vorher);
            }
            return;
        }
        BotMotor.Lauf l = MOTOR.laufe(mc, p, BotWeg.ring(c.x, c.z, z.getY(), 3.6, 4.0), z, tick);
        if (l == BotMotor.Lauf.UNERREICHBAR) { GESPERRT.put(z, tick + 2400); wechsel(Phase.SUCHEN); }
    }

    /**
     * Boden, auf dem ein Baum steht bzw. ein Setzling waechst. NICHT nur
     * BlockTags.DIRT: seit 26.1 enthaelt dieser Tag nur noch Erde, grobe Erde
     * und Wurzelerde -- Gras, Podsol, Moos ... fehlen. Waechst nach dem Faellen
     * Gras ueber die Erde, galt die Stelle sonst als ungeeignet (gefunden im
     * Bot-Test im echten 26.2).
     */
    private static boolean pflanzboden(BlockState st) {
        return st.is(BlockTags.DIRT) || st.is(net.minecraft.world.level.block.Blocks.GRASS_BLOCK)
                || st.is(net.minecraft.world.level.block.Blocks.PODZOL) || st.is(net.minecraft.world.level.block.Blocks.MYCELIUM)
                || st.is(net.minecraft.world.level.block.Blocks.MOSS_BLOCK) || st.is(net.minecraft.world.level.block.Blocks.PALE_MOSS_BLOCK)
                || st.is(net.minecraft.world.level.block.Blocks.MUD) || st.is(net.minecraft.world.level.block.Blocks.MUDDY_MANGROVE_ROOTS);
    }

    /** Nachbarfeld, auf dem man sicher stehen kann (Boden, keine Gefahr, Kopf frei) -- oder null. */
    private static BlockPos sichereSeite(Minecraft mc, BlockPos mitte) {
        BotMotor.McWelt welt = new BotMotor.McWelt(mc.level);
        for (Direction d : Direction.Plane.HORIZONTAL) {
            BlockPos n = mitte.relative(d);
            if (BotWeg.stehen(welt, n.getX(), n.getY(), n.getZ())) return n;
        }
        return null;
    }

    /** Setzling auf die Erde unter "stelle" setzen. */
    private static void setzen(Minecraft mc, LocalPlayer p, BlockPos stelle, int slot) {
        BlockPos boden = stelle.below();
        Vec3 treffer = new Vec3(boden.getX() + 0.5, boden.getY() + 1.0, boden.getZ() + 0.5);
        MOTOR.blicke(p, treffer);
        int vorher = p.getInventory().getSelectedSlot();
        try {
            p.getInventory().setSelectedSlot(slot);
            mc.gameMode.useItemOn(p, InteractionHand.MAIN_HAND, new BlockHitResult(treffer, Direction.UP, boden, false));
            p.swing(InteractionHand.MAIN_HAND);
        } finally {
            p.getInventory().setSelectedSlot(vorher);
        }
    }

    private static boolean hatSetzling(LocalPlayer p, Item wunsch) {
        for (int i = 0; i < 36; i++) {
            ItemStack st = p.getInventory().getItem(i);
            if (!st.isEmpty() && (wunsch == null ? einzelSetzling(st) : st.is(wunsch))) return true;
        }
        return false;
    }

    /** Hotbar-Platz mit Setzling (bevorzugt die passende Art), notfalls aus dem Rucksack geholt; -1 = keiner. */
    private static int setzlingPlatz(Minecraft mc, LocalPlayer p, Item wunsch) {
        int slot = wunsch == null ? -1 : Inv.hotbar(p, st -> st.is(wunsch));
        if (slot < 0 && wunsch != null) slot = BotMotor.holeInHotbar(mc, p, st -> st.is(wunsch));
        if (slot < 0) slot = Inv.hotbar(p, TreeFarmer::einzelSetzling);
        if (slot < 0) slot = BotMotor.holeInHotbar(mc, p, TreeFarmer::einzelSetzling);
        return slot;
    }

    /** Setzling, der auch einzeln waechst (Dunkel-/Blasseiche brauchen 2x2). */
    private static boolean einzelSetzling(ItemStack st) {
        return st.is(ItemTags.SAPLINGS) && !st.is(Items.DARK_OAK_SAPLING) && !st.is(Items.PALE_OAK_SAPLING);
    }

    private static void sammeln(Minecraft mc, LocalPlayer p, TreeFarmerModule m) {
        status = "Collecting wood and saplings";
        // Setzling eingesammelt und eine Stelle wartet? Erst pflanzen -- zerfallendes
        // Laub wirft laufend Neues ab, sonst kaeme der Bot nie dazu.
        if (m.replant.get() && !OFFEN.isEmpty() && hatSetzling(p, null)) {
            BlockPos naechste = null;
            for (BlockPos b : OFFEN) {
                if (GESPERRT.containsKey(b) || !mc.level.getBlockState(b).isAir()) continue;
                if (naechste == null || p.distanceToSqr(Vec3.atCenterOf(b)) < p.distanceToSqr(Vec3.atCenterOf(naechste))) naechste = b;
            }
            if (naechste != null) { pflanzZiel = naechste; wechsel(Phase.NACHPFLANZEN); return; }
        }
        if (!m.collect.get() || tick - phaseSeit > 20 * 45 || BotMotor.freiePlaetze(p) == 0) {
            MOTOR.anhalten(mc);
            wechsel(Phase.SUCHEN);
            return;
        }
        // Laub unter einem Gegenstand wird gerade weggeschlagen
        if (abbau != null) {
            MOTOR.anhalten(mc);
            if (MOTOR.abbauen(mc, p, abbau, tick, abbauPunkt)) abbau = null;
            return;
        }
        AABB bereich = basis != null ? new AABB(basis).inflate(8, 12, 8) : new AABB(mitte(p, m)).inflate(m.range.get(), 10, m.range.get());
        ItemEntity naechstes = sammelZiel != null && sammelZiel.isAlive() && !GESPERRT.containsKey(sammelZiel.blockPosition())
                ? sammelZiel : naechsterDrop(mc, p, bereich);
        sammelZiel = naechstes;
        if (naechstes == null) {
            MOTOR.anhalten(mc);
            // Kurz warten: frisch zerfallendes Laub wirft noch Setzlinge ab.
            if (nichtsLiegtSeit < 0) nichtsLiegtSeit = tick;
            if (tick - nichtsLiegtSeit > 40) wechsel(Phase.SUCHEN);
            return;
        }
        nichtsLiegtSeit = -1;
        BlockPos b = naechstes.blockPosition();
        // Liegt der Gegenstand oben auf Laub (zu hoch zum Aufheben)? Das Laub darunter
        // wegschlagen, dann faellt er herunter.
        BlockPos auf = BlockPos.containing(naechstes.getX(), naechstes.getY() - 0.05, naechstes.getZ());
        if (BotMotor.natuerlichesLaub(mc.level.getBlockState(auf)) && hochImLaub(mc, auf)) {
            status = "Knocking down wood stuck in the leaves";
            Schritt s = schritt(mc, p, auf, true);
            if (s != null && p.onGround()) {
                MOTOR.anhalten(mc);
                abbau = s.pos();
                abbauPunkt = s.punkt();
                dbg("item on leaves at " + auf.toShortString() + " -> breaking " + abbau.toShortString());
                if (MOTOR.abbauen(mc, p, abbau, tick, abbauPunkt)) abbau = null;
                return;
            }
            Vec3 c = Vec3.atCenterOf(auf);
            if (MOTOR.laufe(mc, p, BotWeg.inReichweite(c.x, c.y, c.z, p.blockInteractionRange() - 0.8, 1.62), auf, tick) == BotMotor.Lauf.UNERREICHBAR) {
                GESPERRT.put(b, tick + 1200);
                sammelZiel = null;
            }
            return;
        }
        if (MOTOR.laufe(mc, p, BotWeg.nahBei(naechstes.getX(), naechstes.getY(), naechstes.getZ(), 1.05), naechstes.getUUID(), tick) == BotMotor.Lauf.UNERREICHBAR) {
            dbg("item unreachable at " + b.toShortString());
            GESPERRT.put(b, tick + 1200);
            sammelZiel = null;
        }
    }

    /**
     * Liegt ein Gegenstand auf diesem Laubblock so hoch, dass man ihn vom Boden
     * aus nicht aufheben kann? (Mehr als ein Block Laub bzw. Luft darunter.)
     */
    private static boolean hochImLaub(Minecraft mc, BlockPos laub) {
        int frei = 0;
        for (int dy = 1; dy <= 12; dy++) {
            BlockState st = mc.level.getBlockState(laub.below(dy));
            if (!st.isAir() && !BotMotor.natuerlichesLaub(st) && !st.canBeReplaced()) break;   // Boden
            frei++;
        }
        return frei >= 1;
    }

    private static ItemEntity naechsterDrop(Minecraft mc, LocalPlayer p, AABB bereich) {
        ItemEntity naechstes = null;
        for (ItemEntity ie : mc.level.getEntitiesOfClass(ItemEntity.class, bereich)) {
            ItemStack st = ie.getItem();
            if (!(st.is(ItemTags.LOGS) || st.is(ItemTags.SAPLINGS) || st.is(Items.APPLE) || st.is(Items.STICK))) continue;
            if (GESPERRT.containsKey(ie.blockPosition())) continue;
            if (!ie.onGround() && !ie.isInWater()) continue;        // faellt noch (z. B. durch die Stammspalte)
            if (naechstes == null || ie.distanceToSqr(p) < naechstes.distanceToSqr(p)) naechstes = ie;
        }
        return naechstes;
    }

    /** Was in die Truhe darf: Holz, Aepfel, Stoecke, ueberzaehlige Setzlinge. */
    private static boolean einlagern(ItemStack st) {
        return st.is(ItemTags.LOGS) || st.is(Items.APPLE) || st.is(Items.STICK) || st.is(ItemTags.SAPLINGS);
    }

    /** Je ein Stapel jeder Setzlingsart bleibt; ebenso ein Stapel Holz fuer den Turm. */
    private static Set<Item> behalten() {
        Set<Item> s = new HashSet<>();
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            for (int i = 0; i < 36; i++) {
                ItemStack st = mc.player.getInventory().getItem(i);
                if (st.is(ItemTags.SAPLINGS)) s.add(st.getItem());
            }
            // Ein Stapel Stammholz als Turmbaumaterial (falls keine anderen Bloecke da sind)
            if (Inv.hotbar(mc.player, st -> turmBlock(st) && !st.is(ItemTags.LOGS)) < 0
                    && Inv.inventar(mc.player, st -> turmBlock(st) && !st.is(ItemTags.LOGS)) < 0) {
                for (int i = 0; i < 36; i++) {
                    ItemStack st = mc.player.getInventory().getItem(i);
                    if (st.is(ItemTags.LOGS)) { s.add(st.getItem()); break; }
                }
            }
        }
        return s;
    }

    /** Beste Axt aus der Hotbar in die Hand (echt gewechselt -- der Server rechnet mit dem gehaltenen Werkzeug). */
    private static void axt(LocalPlayer p) {
        int best = -1;
        float bestTempo = 0;
        for (int i = 0; i < 9; i++) {
            ItemStack st = p.getInventory().getItem(i);
            if (!st.is(ItemTags.AXES)) continue;
            if (st.isDamageableItem() && st.getMaxDamage() - st.getDamageValue() <= 3) continue;
            float tempo = st.getDestroySpeed(net.minecraft.world.level.block.Blocks.OAK_LOG.defaultBlockState());
            if (tempo > bestTempo) { bestTempo = tempo; best = i; }
        }
        if (best >= 0 && p.getInventory().getSelectedSlot() != best) p.getInventory().setSelectedSlot(best);
    }
}
