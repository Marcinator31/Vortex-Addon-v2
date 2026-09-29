package com.vortex.client.bot;

import com.vortex.client.cheat.Inv;
import java.util.Objects;
import java.util.function.Predicate;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Gemeinsame "Muskeln" der Lauf-Bots (Crop Farmer, Tree Farmer):
 * weich blicken, einen Weg laufen, Bloecke abbauen.
 *
 * Laufen geht ueber die Bewegungstasten (wie ein Spieler), Abbauen direkt
 * ueber den GameMode (startDestroyBlock/continueDestroyBlock) -- das braucht
 * keine gedrueckte Maustaste und klappt auch, wenn das Fenster nicht im
 * Vordergrund ist.
 *
 * Seit 2.33: {@link #laufe} folgt einem Weg aus {@link BotWeg} (um Hindernisse
 * herum, Stufen hoch, sicher herunter) statt einer geraden Linie.
 */
final class BotMotor {

    private float wunschYaw, wunschPitch;
    private boolean wunsch = false;
    private boolean tastenGesetzt = false;

    private BlockPos abbauPos = null;
    private long abbauSeit = 0;

    // Feststecken
    private Vec3 fortschrittPos = null;
    private long fortschrittSeit = 0;

    void blicke(LocalPlayer p, Vec3 ziel) {
        float[] r = Inv.blickZu(p, ziel);
        wunschYaw = r[0];
        wunschPitch = r[1];
        wunsch = true;
    }

    void blickeRichtung(float yaw, float pitch) {
        wunschYaw = yaw;
        wunschPitch = pitch;
        wunsch = true;
    }

    /** Einmal je Tick: Blick um hoechstens "grad" auf den Wunsch zu drehen. */
    void drehen(LocalPlayer p, float grad) {
        if (!wunsch) return;
        wunsch = false;
        float dy = Mth.wrapDegrees(wunschYaw - p.getYRot());
        float dp = wunschPitch - p.getXRot();
        p.setYRot(p.getYRot() + Mth.clamp(dy, -grad, grad));
        p.setXRot(Mth.clamp(p.getXRot() + Mth.clamp(dp, -grad, grad), -90f, 90f));
    }

    /** Laeuft auf den Punkt zu (nur waagerecht, ohne Weg). Gibt den waagerechten Abstand zurueck. */
    double gehe(Minecraft mc, LocalPlayer p, Vec3 ziel, boolean springenErlaubt) {
        double dx = ziel.x - p.getX(), dz = ziel.z - p.getZ();
        double abstand = Math.sqrt(dx * dx + dz * dz);
        float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
        blickeRichtung(yaw, 15f);
        float rest = Math.abs(Mth.wrapDegrees(yaw - p.getYRot()));
        mc.options.keyUp.setDown(rest < 60f);
        mc.options.keyJump.setDown(springenErlaubt && p.onGround() && p.horizontalCollision);
        tastenGesetzt = true;
        return abstand;
    }

    void anhalten(Minecraft mc) {
        if (!tastenGesetzt) return;
        mc.options.keyUp.setDown(false);
        mc.options.keyJump.setDown(false);
        mc.options.keyDown.setDown(false);
        tastenGesetzt = false;
    }

    void springen(Minecraft mc, boolean an) {
        mc.options.keyJump.setDown(an);
        tastenGesetzt = true;
    }

    /** Alles loslassen (Bot aus). */
    void aus(Minecraft mc) {
        anhalten(mc);
        if (abbauPos != null && mc.gameMode != null) {
            try { mc.gameMode.stopDestroyBlock(); } catch (Throwable ignored) { }
        }
        abbauPos = null;
        fortschrittPos = null;
        vergiss();
    }

    /**
     * Baut pos ab (einen Tick lang). Blick muss nicht exakt stimmen -- der
     * Server prueft beim Abbauen nur den Abstand.
     *
     * @return true, sobald der Block weg ist oder aufgegeben wurde
     */
    boolean abbauen(Minecraft mc, LocalPlayer p, BlockPos pos, long tick) {
        if (mc.level.getBlockState(pos).isAir()) {
            if (pos.equals(abbauPos)) abbauPos = null;
            return true;
        }
        Vec3 mitte = Vec3.atCenterOf(pos);
        blicke(p, mitte);
        Direction seite = Direction.getApproximateNearest(p.getEyePosition().subtract(mitte));
        if (!pos.equals(abbauPos)) {
            abbauPos = pos.immutable();
            abbauSeit = tick;
            mc.gameMode.startDestroyBlock(pos, seite);
        } else {
            mc.gameMode.continueDestroyBlock(pos, seite);
        }
        p.swing(InteractionHand.MAIN_HAND);
        if (tick - abbauSeit > 200) {          // 10 s: klappt nicht
            abbauPos = null;
            try { mc.gameMode.stopDestroyBlock(); } catch (Throwable ignored) { }
            return true;
        }
        if (mc.level.getBlockState(pos).isAir()) {
            abbauPos = null;                // sonst gaebe ein spaeterer Abbau derselben Stelle sofort auf
            return true;
        }
        return false;
    }

    /**
     * Kommt der Bot noch voran? Merkt sich alle 3 s die Position; hat er sich
     * seitdem kaum bewegt, obwohl er laufen soll, steckt er fest.
     */
    boolean steckt(LocalPlayer p, long tick) {
        if (fortschrittPos == null || p.position().distanceToSqr(fortschrittPos) > 1.0) {
            fortschrittPos = p.position();
            fortschrittSeit = tick;
            return false;
        }
        if (tick - fortschrittSeit > 60) {
            fortschrittPos = null;
            return true;
        }
        return false;
    }

    void fortschrittZuruecksetzen() {
        fortschrittPos = null;
    }

    // ==================================================================
    // Laufen mit Weg
    // ==================================================================

    enum Lauf { UNTERWEGS, DA, UNERREICHBAR }

    private BotWeg.Pfad pfad = null;
    private int pfadIndex = 0;
    private Object pfadFuer = null;
    private long pfadZeit = 0;
    private int festgesteckt = 0;
    /** Wie lange schon "angekommen" fuer dasselbe Ziel (der Bot kommt dort nicht weiter). */
    private Object daFuer = null;
    private long daSeit = 0, daZuletzt = -100;
    private int zentrieren = 0;

    /** Fuss-Block des Spielers (auf Ackerboden/Seelensand/Stufen richtig). */
    static int fussY(LocalPlayer p) {
        return Mth.floor(p.getY() + 0.2);
    }

    /**
     * Fuss-Block wie die Wegsuche ihn sieht: steht der Spieler AUF einem Block,
     * den die Suche als "fest" zaehlt (z. B. 5-7 Schneeschichten), ist das Feld
     * darueber gemeint -- sonst haelt er jeden Schritt fuer eine Stufe.
     */
    static int fussY(Level l, LocalPlayer p) {
        int y = fussY(p);
        if (new McWelt(l).art(Mth.floor(p.getX()), y, Mth.floor(p.getZ())) == BotWeg.FEST) y++;
        return y;
    }

    /** Vergisst den aktuellen Weg (neues Ziel, Bot aus). */
    void vergiss() {
        pfad = null;
        pfadIndex = 0;
        pfadFuer = null;
        festgesteckt = 0;
    }

    /** Wie viele Felder der aktuelle Weg noch hat (fuer die Statuszeile), -1 = keiner. */
    int restFelder() {
        return pfad == null ? -1 : Math.max(0, pfad.felder.size() - pfadIndex);
    }

    /**
     * Einen Tick lang Richtung Ziel laufen.
     *
     * @param fuer Schluessel des Ziels (z. B. die Blockposition). Aendert er
     *             sich, wird ein neuer Weg gesucht.
     */
    Lauf laufe(Minecraft mc, LocalPlayer p, BotWeg.Ziel ziel, Object fuer, long tick) {
        int fx = Mth.floor(p.getX()), fy = fussY(mc.level, p), fz = Mth.floor(p.getZ());
        if (ziel.erreicht(fx, fy, fz) && (p.onGround() || p.isInWater())) {
            // Erst in die Feldmitte (das Ziel wurde fuer die Mitte berechnet) -- hoechstens 1 s lang.
            double mx = fx + 0.5 - p.getX(), mz = fz + 0.5 - p.getZ();
            if (mx * mx + mz * mz > 0.16 && zentrieren < 20) {
                zentrieren++;
                float yaw = (float) (Math.toDegrees(Math.atan2(mz, mx)) - 90.0);
                blickeRichtung(yaw, 15f);
                mc.options.keyUp.setDown(Math.abs(Mth.wrapDegrees(yaw - p.getYRot())) < 50f);
                mc.options.keyJump.setDown(false);
                tastenGesetzt = true;
                return Lauf.UNTERWEGS;
            }
            anhalten(mc);
            vergiss();
            zentrieren = 0;
            // Immer wieder "da", aber der Bot kommt nicht weiter (Ziel doch ausser
            // Reichweite, Gegenstand nicht aufsammelbar): nach 2 s aufgeben.
            if (!Objects.equals(fuer, daFuer) || tick - daZuletzt > 5) { daFuer = fuer; daSeit = tick; }
            daZuletzt = tick;
            if (tick - daSeit > 40) { daFuer = null; return Lauf.UNERREICHBAR; }
            return Lauf.DA;
        }
        zentrieren = 0;
        if (!Objects.equals(fuer, pfadFuer)) {
            vergiss();
            pfadFuer = fuer;
        }
        boolean neu = pfad == null || tick - pfadZeit > 200;
        if (!neu && pfadIndex >= pfad.felder.size()) neu = true;     // Ende (Teilweg) erreicht
        if (!neu) {
            int[] f = pfad.felder.get(pfadIndex);
            double dx = f[0] + 0.5 - p.getX(), dz = f[2] + 0.5 - p.getZ();
            if (dx * dx + dz * dz > 9 || Math.abs(f[1] - fy) > 3) neu = true;   // vom Weg abgekommen
        }
        if (neu) {
            pfad = BotWeg.suche(new McWelt(mc.level), fx, fy, fz, ziel, 6000, 48);
            pfadIndex = 1;
            pfadZeit = tick;
            fortschrittPos = null;
            if (pfad == null || pfad.felder.size() < 2) {
                anhalten(mc);
                Object f = pfadFuer;
                vergiss();
                pfadFuer = f;
                return Lauf.UNERREICHBAR;
            }
        }
        // Erreichte Felder ueberspringen (auch wenn eines "abgekuerzt" wurde)
        for (int j = pfadIndex; j < Math.min(pfad.felder.size(), pfadIndex + 4); j++) {
            int[] f = pfad.felder.get(j);
            if (f[0] == fx && f[2] == fz && Math.abs(f[1] - fy) <= 1) pfadIndex = j + 1;
        }
        if (pfadIndex >= pfad.felder.size()) {
            anhalten(mc);
            return Lauf.UNTERWEGS;                  // naechster Tick: DA oder neuer Teilweg
        }
        int[] f = pfad.felder.get(pfadIndex);
        double dx = f[0] + 0.5 - p.getX(), dz = f[2] + 0.5 - p.getZ();
        double flach = Math.sqrt(dx * dx + dz * dz);
        float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
        blickeRichtung(yaw, 15f);
        float rest = Math.abs(Mth.wrapDegrees(yaw - p.getYRot()));
        mc.options.keyUp.setDown(rest < 50f);
        // Springen NUR fuer eine Stufe hoch (oder aus dem Wasser) -- nie sonst,
        // damit kein Ackerboden zertreten wird. Und nur, wenn der Bot schon zur
        // Stufe schaut und vorwaerts laeuft: ein Sprung auf der Stelle landet
        // wieder auf demselben Block (auf Acker: zertreten).
        boolean hoch = f[1] > fy;
        boolean laeuft = p.getDeltaMovement().horizontalDistanceSqr() > 0.0009;
        boolean aufAcker = mc.level.getBlockState(new BlockPos(fx, fy - 1, fz)).is(Blocks.FARMLAND)
                || mc.level.getBlockState(new BlockPos(fx, fy, fz)).is(Blocks.FARMLAND);
        boolean sprung = hoch && p.onGround() && flach < 1.4 && rest < 20f && (laeuft || !aufAcker);
        mc.options.keyJump.setDown(sprung || (p.isInWater() && f[1] >= fy));
        tastenGesetzt = true;

        if (steckt(p, tick)) {
            if (++festgesteckt >= 3) {
                anhalten(mc);
                Object k = pfadFuer;
                vergiss();
                pfadFuer = k;
                return Lauf.UNERREICHBAR;
            }
            pfad = null;                            // neuer Weg ab hier
        }
        return Lauf.UNTERWEGS;
    }

    /** Die Welt fuer die Wegsuche, aus den geladenen Bloecken. */
    static final class McWelt implements BotWeg.Welt {
        private final Level l;
        private final BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();

        McWelt(Level l) { this.l = l; }

        private BlockState st(int x, int y, int z) {
            m.set(x, y, z);
            return l.getBlockState(m);
        }

        @Override public int art(int x, int y, int z) {
            if (!l.hasChunk(x >> 4, z >> 4)) return BotWeg.HOCH;
            BlockState s = st(x, y, z);
            if (s.isAir()) return BotWeg.FREI;
            VoxelShape v = s.getCollisionShape(l, m);
            if (v.isEmpty()) return BotWeg.FREI;
            double oben = v.max(Direction.Axis.Y);
            if (oben > 1.0) return BotWeg.HOCH;                 // Zaun, Mauer, Tor
            return oben <= 0.6 ? BotWeg.NIEDRIG : BotWeg.FEST;
        }

        @Override public boolean gefahr(int x, int y, int z) {
            BlockState s = st(x, y, z);
            if (s.isAir()) return false;
            if (s.getFluidState().is(FluidTags.LAVA)) return true;
            return s.is(Blocks.FIRE) || s.is(Blocks.SOUL_FIRE) || s.is(Blocks.CACTUS) || s.is(Blocks.SWEET_BERRY_BUSH)
                    || s.is(Blocks.MAGMA_BLOCK) || s.is(Blocks.COBWEB) || s.is(Blocks.POWDER_SNOW)
                    || s.is(Blocks.CAMPFIRE) || s.is(Blocks.SOUL_CAMPFIRE) || s.is(Blocks.WITHER_ROSE);
        }

        @Override public boolean wasser(int x, int y, int z) {
            return st(x, y, z).getFluidState().is(FluidTags.WATER);
        }

        @Override public boolean acker(int x, int y, int z) {
            return st(x, y, z).is(Blocks.FARMLAND);
        }
    }

    // ==================================================================
    // Inventar-Handgriffe
    // ==================================================================

    /**
     * Holt einen passenden Gegenstand aus dem Rucksack in die Hotbar
     * (bevorzugt auf einen leeren Platz). Gibt den Hotbar-Platz zurueck oder -1.
     */
    static int holeInHotbar(Minecraft mc, LocalPlayer p, Predicate<ItemStack> passt) {
        int quelle = Inv.inventar(p, passt);
        if (quelle < 0) return -1;
        int ziel = -1;
        for (int i = 8; i >= 0; i--) if (p.getInventory().getItem(i).isEmpty()) { ziel = i; break; }
        // Kein leerer Platz: einen nehmen, auf dem kein Werkzeug und kein Essen liegt
        for (int i = 8; i >= 0 && ziel < 0; i--) {
            ItemStack st = p.getInventory().getItem(i);
            if (!st.isDamageableItem() && !BotEssen.gutesEssen(st) && i != p.getInventory().getSelectedSlot()) ziel = i;
        }
        if (ziel < 0) ziel = 8;
        mc.gameMode.handleContainerInput(p.inventoryMenu.containerId, Inv.fensterPlatz(quelle), ziel,
                net.minecraft.world.inventory.ContainerInput.SWAP, p);
        return ziel;
    }

    /** Freie Plaetze im Hauptinventar (0..35). */
    static int freiePlaetze(LocalPlayer p) {
        int n = 0;
        for (int i = 0; i < 36; i++) if (p.getInventory().getItem(i).isEmpty()) n++;
        return n;
    }
}
