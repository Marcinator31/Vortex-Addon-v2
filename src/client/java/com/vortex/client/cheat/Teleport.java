package com.vortex.client.cheat;

import com.mojang.blaze3d.platform.InputConstants;
import com.vortex.client.core.PacketHooks;
import com.vortex.client.module.ModuleManager;
import com.vortex.client.module.modules.ClickTpModule;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Teleport in Spruengen -- fuer Click TP und Anti Void (Return).
 *
 * WAS DER SERVER ZULAESST (Vanilla, ServerGamePacketListenerImpl.handleMovePlayer):
 *   - Abstand zur Position am Anfang des Server-Ticks: hoechstens 100 x n
 *     (Bloecke zum Quadrat), n = Bewegungspakete in diesem Tick (ab 6 wieder 1).
 *     Ein Paket: ~10 Bloecke. Drei stehende Pakete + der Sprung: ~20.
 *   - Der Weg des Sprungs wird mit Kollision nachgerechnet ("moved wrongly")
 *     -- er muss frei sein. Ecken werden nicht abgekuerzt: ein Sprung endet
 *     hoechstens an der naechsten Ecke.
 *   - Alle Pakete melden "am Boden" -- sonst zaehlt der Server die Hoehe als
 *     Fall und berechnet beim Landen Fallschaden.
 *
 * Setzt der Server zurueck (Positions-Paket), wird abgebrochen.
 */
public final class Teleport {

    private Teleport() {}

    private static final List<Vec3> WEG = new ArrayList<>();
    private static double schritt = 9.0;
    private static int fueller = 0;
    private static volatile boolean zurueckgesetzt = false;
    private static boolean tasteVorher = false;
    private static String wofuer = "";

    public static boolean aktiv() {
        return !WEG.isEmpty();
    }

    public static void register() {
        PacketHooks.onReceive(p -> {
            if (p instanceof ClientboundPlayerPositionPacket && aktiv()) zurueckgesetzt = true;
            return false;
        });
        ClientTickEvents.START_CLIENT_TICK.register(mc -> {
            try { ausloeser(mc); } catch (Throwable e) { com.vortex.client.core.Errors.report("ClickTp.trigger", e); }
        });
        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            try { tick(mc); } catch (Throwable e) { com.vortex.client.core.Errors.report("Teleport", e); abbrechen(); }
        });
    }

    // ------------------------------------------------------------------
    // Click TP: Ausloeser und Ziel

    private static void ausloeser(Minecraft mc) {
        ClickTpModule m = ModuleManager.INSTANCE.get(ClickTpModule.class);
        LocalPlayer p = mc.player;
        if (m == null || !m.isEnabled() || p == null || mc.level == null || mc.gui.screen() != null) {
            tasteVorher = false;
            return;
        }
        boolean los = false;
        if (m.trigger.getIndex() == 0) {
            if (p.getMainHandItem().isEmpty()) {
                while (mc.options.keyUse.consumeClick()) los = true;
            }
        } else if (m.key.isBound()) {
            boolean t = InputConstants.isKeyDown(mc.getWindow(), m.key.getKeyCode());
            los = t && !tasteVorher;
            tasteVorher = t;
        }
        if (!los || aktiv()) return;

        HitResult hr = p.pick(m.maxDistance.get(), 1f, false);
        if (!(hr instanceof BlockHitResult bh) || hr.getType() != HitResult.Type.BLOCK) {
            melde(mc, "No block in range (" + m.maxDistance.getInt() + ").");
            return;
        }
        Vec3 ziel = standplatz(mc, p, bh);
        if (ziel == null) {
            melde(mc, "No room to stand there.");
            return;
        }
        boolean schnell = m.speed.getIndex() == 1;
        if (!starte(mc, p, ziel, schnell ? 18.0 : 9.0, schnell ? 3 : 0, "Click TP")) {
            melde(mc, "No free path there -- the server checks walls on the way.");
        }
    }

    /** Wo man bei diesem Treffer stehen kann (Fussposition), sonst null. */
    private static Vec3 standplatz(Minecraft mc, LocalPlayer p, BlockHitResult bh) {
        BlockPos b = bh.getBlockPos();
        var shape = mc.level.getBlockState(b).getCollisionShape(mc.level, b);
        double oben = shape.isEmpty() ? 0.0 : shape.max(Direction.Axis.Y);
        Vec3 aufDemBlock = new Vec3(b.getX() + 0.5, b.getY() + oben, b.getZ() + 0.5);
        if (passt(mc, p, aufDemBlock)) return aufDemBlock;
        BlockPos davor = b.relative(bh.getDirection());
        Vec3 daneben = new Vec3(davor.getX() + 0.5, davor.getY(), davor.getZ() + 0.5);
        if (passt(mc, p, daneben)) return daneben;
        return null;
    }

    private static boolean passt(Minecraft mc, LocalPlayer p, Vec3 fuss) {
        return mc.level.noCollision(p, box(p, fuss));
    }

    private static AABB box(LocalPlayer p, Vec3 fuss) {
        Vec3 d = fuss.subtract(p.position());
        return p.getBoundingBox().move(d.x, d.y, d.z).deflate(0.01);
    }

    // ------------------------------------------------------------------
    // Weg planen

    /**
     * Startet einen Teleport.
     *
     * @param schrittWeite Bloecke je Tick
     * @param fuellPakete  stehende Pakete vor jedem Sprung (erhoehen die Erlaubnis)
     * @return false, wenn kein freier Weg gefunden wurde
     */
    public static boolean starte(Minecraft mc, LocalPlayer p, Vec3 ziel, double schrittWeite, int fuellPakete, String grund) {
        List<Vec3> weg = plane(mc, p, p.position(), ziel);
        if (weg == null) return false;
        WEG.clear();
        WEG.addAll(weg);
        schritt = schrittWeite;
        fueller = fuellPakete;
        zurueckgesetzt = false;
        wofuer = grund;
        return true;
    }

    /** Gerade Linie, sonst hoch -- hinueber -- runter. Liste ohne den Start. */
    private static List<Vec3> plane(Minecraft mc, LocalPlayer p, Vec3 start, Vec3 ziel) {
        if (frei(mc, p, start, ziel)) return List.of(ziel);
        double basis = Math.max(start.y, ziel.y);
        int oberkante = mc.level.getMaxY();
        for (int h = 1; h <= 40 && basis + h < oberkante; h++) {
            Vec3 a = new Vec3(start.x, basis + h, start.z);
            Vec3 b = new Vec3(ziel.x, basis + h, ziel.z);
            if (frei(mc, p, start, a) && frei(mc, p, a, b) && frei(mc, p, b, ziel)) return List.of(a, b, ziel);
        }
        return null;
    }

    /** Ist die Strecke fuer die Spieler-Box frei? (in 0,25er Schritten) */
    private static boolean frei(Minecraft mc, LocalPlayer p, Vec3 a, Vec3 b) {
        double len = a.distanceTo(b);
        int n = Math.max(1, (int) Math.ceil(len / 0.25));
        for (int i = 0; i <= n; i++) {
            Vec3 q = a.lerp(b, i / (double) n);
            if (!mc.level.noCollision(p, box(p, q))) return false;
            if (!mc.level.hasChunk(((int) Math.floor(q.x)) >> 4, ((int) Math.floor(q.z)) >> 4)) return false;
        }
        return true;
    }

    // ------------------------------------------------------------------
    // Ausfuehren: ein Sprung pro Tick

    private static void tick(Minecraft mc) {
        if (WEG.isEmpty()) return;
        LocalPlayer p = mc.player;
        var net = mc.getConnection();
        if (p == null || net == null || mc.level == null) { abbrechen(); return; }
        if (zurueckgesetzt) {
            melde(mc, wofuer + ": the server moved you back -- stopped.");
            abbrechen();
            return;
        }
        Vec3 jetzt = p.position();
        Vec3 naechster = WEG.get(0);
        double d = jetzt.distanceTo(naechster);
        Vec3 neu;
        if (d <= schritt) {
            neu = naechster;
            WEG.remove(0);
        } else {
            neu = jetzt.add(naechster.subtract(jetzt).scale(schritt / d));
        }
        for (int i = 0; i < fueller; i++) {
            net.send(new ServerboundMovePlayerPacket.Pos(jetzt.x, jetzt.y, jetzt.z, true, false));
        }
        net.send(new ServerboundMovePlayerPacket.Pos(neu.x, neu.y, neu.z, true, false));
        p.setPos(neu.x, neu.y, neu.z);
        p.setDeltaMovement(Vec3.ZERO);
        p.resetFallDistance();
        if (WEG.isEmpty()) melde(mc, wofuer + ": done.");
    }

    public static void abbrechen() {
        WEG.clear();
        zurueckgesetzt = false;
    }

    private static void melde(Minecraft mc, String text) {
        if (mc.player != null) mc.player.sendOverlayMessage(Component.literal("§d" + text));
    }
}
