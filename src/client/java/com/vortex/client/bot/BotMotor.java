package com.vortex.client.bot;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.Vec3;

/**
 * Gemeinsame "Muskeln" der einfachen Bots (Crop Farmer, Tree Farmer):
 * weich blicken, geradeaus laufen, Bloecke abbauen.
 *
 * Laufen geht ueber die Bewegungstasten (wie ein Spieler), Abbauen direkt
 * ueber den GameMode (startDestroyBlock/continueDestroyBlock) -- das braucht
 * keine gedrueckte Maustaste und klappt auch, wenn das Fenster nicht im
 * Vordergrund ist. Ein Pfadfinder ist nicht dabei: die Bots laufen gerade
 * Linien und geben ein Ziel auf, wenn sie nicht vorankommen.
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
        float[] r = com.vortex.client.cheat.Inv.blickZu(p, ziel);
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

    /** Laeuft auf den Punkt zu (nur waagerecht). Gibt den waagerechten Abstand zurueck. */
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
        return mc.level.getBlockState(pos).isAir();
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
}
