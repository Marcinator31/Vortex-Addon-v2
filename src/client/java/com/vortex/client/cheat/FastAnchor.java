package com.vortex.client.cheat;

import com.vortex.client.module.ModuleManager;
import com.vortex.client.module.modules.FastAnchorModule;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.Map;

/**
 * Logik von Fast Anchor (siehe FastAnchorModule).
 *
 * WARUM ALLES IN EINEM TICK GEHT: Der Server arbeitet die Pakete eines
 * Spielers der Reihe nach ab. "Anker setzen", "mit Glowstone klicken",
 * "mit Schwert klicken" kommen in genau dieser Reihenfolge an -- beim zweiten
 * Paket steht der Anker schon, beim dritten ist er geladen. Der Client sagt
 * jeden Schritt ausserdem selbst voraus, darum stimmt auch seine Anzeige.
 *
 * ZUENDEN: Rechtsklick auf einen geladenen Anker mit etwas, das KEIN
 * Glowstone ist. Liegt Glowstone in der Nebenhand, wuerde der Klick nur
 * weiterladen -- dann wird nicht gezuendet.
 */
public final class FastAnchor {

    private FastAnchor() {}

    private static long tick = 0;
    private static long zuletzt = -100;
    private static String letzteWarnung = null;

    /** Fast-Modus: laufender Ablauf ueber mehrere Ticks. */
    private static int schritt = -1;
    private static BlockPos anker, schild, setzAuf;
    private static Direction setzSeite;
    private static int vorherSlot = -1;

    public static void register() {
        ClientTickEvents.START_CLIENT_TICK.register(mc -> {
            tick++;
            try { vorDemTick(mc); } catch (Throwable e) { com.vortex.client.core.Errors.report("FastAnchor", e); }
        });
    }

    private static FastAnchorModule modul() {
        FastAnchorModule m = ModuleManager.INSTANCE.get(FastAnchorModule.class);
        return (m != null && m.isEnabled()) ? m : null;
    }

    private static void warne(Minecraft mc, String text) {
        if (text.equals(letzteWarnung) || mc.player == null) return;
        letzteWarnung = text;
        mc.player.sendSystemMessage(Component.literal("§d[Fast Anchor]§7 " + text));
    }

    /**
     * Vor Minecrafts eigener Tastenauswertung: Rechtsklicks, die uns
     * gehoeren, selbst verbrauchen -- sonst setzt Minecraft zusaetzlich
     * einen Anker oder laedt weiter.
     */
    private static void vorDemTick(Minecraft mc) {
        FastAnchorModule m = modul();
        LocalPlayer p = mc.player;
        if (m == null || p == null || mc.level == null || mc.gameMode == null) { schritt = -1; return; }

        if (schritt >= 0) {                 // Fast-Modus: naechster Schritt
            weiter(mc, p, m);
            haltRechtsklick(mc);
            return;
        }
        if (mc.gui.screen() != null) return;
        if (!(mc.hitResult instanceof BlockHitResult bhr) || bhr.getType() != HitResult.Type.BLOCK) return;
        if (m.onlyHolding.get()) {
            ItemStack hand = p.getMainHandItem();
            if (!hand.is(Items.RESPAWN_ANCHOR) && !hand.is(Items.GLOWSTONE)) return;
        }
        boolean zielIstAnker = mc.level.getBlockState(bhr.getBlockPos()).is(Blocks.RESPAWN_ANCHOR);
        if (!zielIstAnker && !p.getMainHandItem().is(Items.RESPAWN_ANCHOR)) return;

        boolean klick = false;
        while (mc.options.keyUse.consumeClick()) klick = true;
        boolean gehalten = m.repeat.get() && mc.options.keyUse.isDown()
                && tick - zuletzt >= m.repeatDelay.getInt();
        if (mc.options.keyUse.isDown()) haltRechtsklick(mc);
        if (!klick && !gehalten) return;

        if (mc.level.dimension() == Level.NETHER) {
            warne(mc, "Im Nether explodieren Anker nicht.");
            return;
        }
        zuletzt = tick;
        starte(mc, p, m, bhr, zielIstAnker);
    }

    /** Minecrafts Wiederholen beim Gedrueckthalten unterdruecken. */
    private static void haltRechtsklick(Minecraft mc) {
        ((com.vortex.client.mixin.client.RightClickDelayAccessor) mc).vortex$setRightClickDelay(4);
    }

    private static void starte(Minecraft mc, LocalPlayer p, FastAnchorModule m, BlockHitResult bhr, boolean zielIstAnker) {
        int ankerSlot = Inv.hotbar(p, st -> st.is(Items.RESPAWN_ANCHOR));
        int glowSlot = Inv.hotbar(p, st -> st.is(Items.GLOWSTONE));
        if (glowSlot < 0) { warne(mc, "Glowstone in die Hotbar legen."); return; }

        BlockPos a;
        if (zielIstAnker) {
            a = bhr.getBlockPos();
            setzAuf = null;
        } else {
            if (ankerSlot < 0) return;
            BlockPos geklickt = bhr.getBlockPos();
            BlockState st = mc.level.getBlockState(geklickt);
            a = st.canBeReplaced() ? geklickt : geklickt.relative(bhr.getDirection());
            BlockState dort = mc.level.getBlockState(a);
            if (!dort.isAir() && !dort.canBeReplaced()) return;
            if (p.getBoundingBox().intersects(new AABB(a))) return;
            setzAuf = geklickt;
            setzSeite = bhr.getDirection();
        }
        anker = a;

        // Schild: der Nachbar des Ankers in Richtung meiner Augen
        schild = null;
        // Schild braucht einen zweiten Glowstone (einer laedt, einer schuetzt)
        if (m.shield.get() && anzahl(p, Items.GLOWSTONE) >= 2) schild = schildPlatz(mc, p, a);

        // Selbstschaden vorher ausrechnen -- mit Schild, ohne Anker
        boolean zuenden = m.detonate.get();
        if (zuenden) {
            Map<BlockPos, BlockState> ersetze = new HashMap<>();
            ersetze.put(a, Blocks.AIR.defaultBlockState());
            if (schild != null) ersetze.put(schild, Blocks.GLOWSTONE.defaultBlockState());
            float selbst = Sprengung.schaden(p, Vec3.atCenterOf(a), Sprengung.ANKER, ersetze, 0);
            if (m.showDamage.get()) {
                p.sendSystemMessage(Component.literal(String.format(java.util.Locale.ROOT,
                        "§d[Fast Anchor]§7 Selbstschaden: %.1f%s", selbst, schild != null ? " (mit Schild)" : "")));
            }
            if (selbst > m.maxSelfDamage.get()
                    || (m.antiSuicide.get() && selbst >= Sprengung.leben(p) - 1.0f)) {
                warne(mc, String.format(java.util.Locale.ROOT,
                        "Zu gefaehrlich (%.1f Schaden) -- nur gesetzt und geladen.", selbst));
                zuenden = false;
            } else {
                letzteWarnung = null;
            }
            if (zuenden && p.getOffhandItem().is(Items.GLOWSTONE)) {
                warne(mc, "Glowstone in der Nebenhand -- Zuenden wuerde nur laden.");
                zuenden = false;
            }
        }
        vorherSlot = p.getInventory().getSelectedSlot();
        zuendenErlaubt = zuenden;

        if (m.speed.getIndex() == 0) {
            for (schritt = 0; schritt >= 0 && schritt < 4; ) {
                if (!schrittAus(mc, p, m)) break;
            }
            ende(p, m);
        } else {
            schritt = 0;
            weiter(mc, p, m);
        }
    }

    private static boolean zuendenErlaubt = true;

    /** Fast-Modus: pro Tick ein Schritt. */
    private static void weiter(Minecraft mc, LocalPlayer p, FastAnchorModule m) {
        if (!schrittAus(mc, p, m) || schritt >= 4) ende(p, m);
    }

    private static void ende(LocalPlayer p, FastAnchorModule m) {
        if (m.switchBack.get() && vorherSlot >= 0) p.getInventory().setSelectedSlot(vorherSlot);
        schritt = -1;
        vorherSlot = -1;
    }

    /**
     * Fuehrt den aktuellen Schritt aus und rueckt weiter.
     * 0 = setzen, 1 = Schild, 2 = laden, 3 = zuenden.
     * @return false = abbrechen
     */
    private static boolean schrittAus(Minecraft mc, LocalPlayer p, FastAnchorModule m) {
        switch (schritt) {
            case 0: {
                schritt = 1;
                if (setzAuf == null) return true;              // Anker lag schon
                int s = Inv.hotbar(p, st -> st.is(Items.RESPAWN_ANCHOR));
                if (s < 0) return false;
                klick(mc, p, s, setzAuf, setzSeite, m.swing.get());
                return true;
            }
            case 1: {
                schritt = 2;
                if (schild == null) return true;
                BlockState dort = mc.level.getBlockState(schild);
                if (!dort.isAir() && !dort.canBeReplaced()) return true;
                int s = Inv.hotbar(p, st -> st.is(Items.GLOWSTONE));
                if (s < 0) return true;
                setzeOhneAnker(mc, p, s, schild, m.swing.get());
                return true;
            }
            case 2: {
                schritt = 3;
                BlockState st = mc.level.getBlockState(anker);
                // Direkt nach dem Setzen kennt der Client den Anker schon (Vorhersage).
                if (st.is(Blocks.RESPAWN_ANCHOR) && st.getValue(BlockStateProperties.RESPAWN_ANCHOR_CHARGES) > 0) return true;
                int s = Inv.hotbar(p, x -> x.is(Items.GLOWSTONE));
                if (s < 0) return false;
                klick(mc, p, s, anker, Direction.UP, m.swing.get());
                return true;
            }
            case 3: {
                schritt = 4;
                if (!zuendenErlaubt) return false;
                int s = zuendPlatz(p);
                if (s < 0) { warne(mc, "Einen Hotbar-Platz leer lassen (oder Schwert/Werkzeug) zum Zuenden."); return false; }
                klick(mc, p, s, anker, Direction.UP, m.swing.get());
                return true;
            }
            default:
                return false;
        }
    }

    /** Rechtsklick auf die Seite "seite" von pos, mit dem Gegenstand aus Hotbar-Platz "slot". */
    private static void klick(Minecraft mc, LocalPlayer p, int slot, BlockPos pos, Direction seite, boolean schwingen) {
        p.getInventory().setSelectedSlot(slot);
        Vec3 treffer = Vec3.atCenterOf(pos).add(seite.getStepX() * 0.5, seite.getStepY() * 0.5, seite.getStepZ() * 0.5);
        mc.gameMode.useItemOn(p, InteractionHand.MAIN_HAND, new BlockHitResult(treffer, seite, pos, false));
        if (schwingen) p.swing(InteractionHand.MAIN_HAND);
    }

    /**
     * Glowstone-Block an pos setzen, angelehnt an einen Nachbarn, der NICHT
     * der Anker ist -- ein Klick auf den Anker wuerde ihn laden statt bauen.
     */
    private static void setzeOhneAnker(Minecraft mc, LocalPlayer p, int slot, BlockPos pos, boolean schwingen) {
        for (Direction d : Direction.values()) {
            BlockPos n = pos.relative(d);
            if (n.equals(anker)) continue;
            BlockState st = mc.level.getBlockState(n);
            if (st.isAir() || st.canBeReplaced()) continue;
            klick(mc, p, slot, n, d.getOpposite(), schwingen);
            return;
        }
    }

    /**
     * Wo der Schild hin muss: der Nachbar des Ankers, der zu meinen Augen
     * zeigt (staerkste Achse). Frei, nicht in mir, und mit einem anderen
     * Nachbarn zum Anlehnen.
     */
    private static BlockPos schildPlatz(Minecraft mc, LocalPlayer p, BlockPos a) {
        Vec3 d = p.getEyePosition().subtract(Vec3.atCenterOf(a));
        Direction richtung = Direction.getApproximateNearest(d.x, d.y, d.z);
        BlockPos s = a.relative(richtung);
        BlockState st = mc.level.getBlockState(s);
        // Steht dort schon ein Block, deckt der ohnehin ab.
        if (!st.isAir() && !st.canBeReplaced()) return null;
        if (p.getBoundingBox().intersects(new AABB(s))) return null;
        for (Direction n : Direction.values()) {
            BlockPos q = s.relative(n);
            if (q.equals(a)) continue;
            BlockState qs = mc.level.getBlockState(q);
            if (!qs.isAir() && !qs.canBeReplaced()) return s;
        }
        return null;
    }

    /** Leerer Platz, sonst Schwert/Werkzeug -- nur kein Glowstone und kein Block. */
    private static int zuendPlatz(LocalPlayer p) {
        for (int i = 0; i < 9; i++) if (p.getInventory().getItem(i).isEmpty()) return i;
        return Inv.hotbar(p, st -> !st.is(Items.GLOWSTONE) && !(st.getItem() instanceof BlockItem));
    }

    private static int anzahl(LocalPlayer p, net.minecraft.world.item.Item it) {
        int n = 0;
        for (int i = 0; i < 9; i++) {
            ItemStack st = p.getInventory().getItem(i);
            if (st.is(it)) n += st.getCount();
        }
        return n;
    }
}
