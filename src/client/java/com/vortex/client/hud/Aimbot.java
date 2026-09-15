package com.vortex.client.hud;

import com.vortex.client.module.Module;
import com.vortex.client.module.ModuleManager;
import com.vortex.client.module.modules.AimbotModule;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * Nahkampf-Aimbot mit sanftem, FLUESSIGEM Gleiten.
 *
 * Wichtiger Unterschied zur ersten Version: Die Rotation wird PRO FRAME
 * angepasst (LevelRenderEvents.START_MAIN), nicht mehr pro Tick. Ticks
 * laufen nur 20x/Sekunde -- bei 60-140 FPS blieb der Blick zwischen den Ticks
 * starr und sprang dann, was aus Spielersicht ruckelte. Pro Frame gleitet der
 * Blick durchgehend weich.
 *
 * Damit die Ziel-Geschwindigkeit NICHT von der FPS abhaengt (sonst zielt man bei
 * 140 FPS doppelt so schnell wie bei 70), wird die Bewegung ueber die echte
 * Frame-Zeit (nanoTime-Delta) skaliert und auf eine feste "Tick-Rate" von 20/s
 * normiert. Ergebnis: exakt dieselbe Effizienz/Staerke wie vorher, nur eben
 * fluessig ueber alle Frames verteilt statt in 20 Spruengen.
 *
 * Da LevelRenderEvents.START_MAIN vor dem eigentlichen Rendern und laufend
 * vor den Tick-Bewegungspaketen liegt, uebernimmt der naechste Paket-Versand
 * automatisch die aktuelle (bereits naeher am Ziel liegende) Rotation.
 */
public final class Aimbot {

    private static long lastFrameNano = 0L;

    private Aimbot() {}

    public static void register() {
        LevelRenderEvents.START_MAIN.register(context -> {
            long pvpT0 = System.nanoTime();
            try {
            Minecraft client = Minecraft.getInstance();

            AimbotModule mod = (AimbotModule) find(AimbotModule.class);
            if (mod == null || !mod.isEnabled()) { lastFrameNano = 0L; return; }
            if (client.player == null || client.level == null) { lastFrameNano = 0L; return; }

            // Optional nur ziehen, wenn die Angriffstaste gehalten wird.
            if (mod.onlyWhenAttacking() && !client.options.keyAttack.isDown()) {
                lastFrameNano = 0L;
                return;
            }

            LocalPlayer self = client.player;
            Vec3 eye = self.getEyePosition();

            // Bestes Ziel bestimmen (gleiche Logik wie zuvor).
            AbstractClientPlayer target = findBestTarget(client, mod, self, eye);
            if (target == null) { lastFrameNano = 0L; return; }

            // Frame-Zeit messen (Sekunden), gegen Spruenge begrenzt.
            long now = System.nanoTime();
            double dt;
            if (lastFrameNano == 0L) {
                dt = 1.0 / 20.0; // erster Frame: wie ein Tick behandeln
            } else {
                dt = (now - lastFrameNano) / 1_000_000_000.0;
            }
            lastFrameNano = now;
            if (dt > 0.1) dt = 0.1;
            // Auf Tick-Rate normieren: 1.0 = genau ein 20-tel Sekunde (ein Tick).
            double tickScale = dt * 20.0;

            float curYaw = self.getYRot();
            float curPitch = self.getXRot();

            // Zielpunkt am Gegner (Naechster / Kopf / Koerper / Fuesse).
            Vec3 aimPoint = aimPointFor(target, mod.getTargetPoint(),
                    eye, curYaw, curPitch);

            double dx = aimPoint.x - eye.x;
            double dy = aimPoint.y - eye.y;
            double dz = aimPoint.z - eye.z;
            double horizontal = Math.sqrt(dx * dx + dz * dz);

            float targetYaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
            float targetPitch = (float) (-Math.toDegrees(Math.atan2(dy, horizontal)));

            float yawDiff = Mth.wrapDegrees(targetYaw - curYaw);
            float pitchDiff = targetPitch - curPitch;

            // Staerke/Glaettung wie zuvor -- aber pro Frame skaliert (tickScale),
            // damit die effektive Geschwindigkeit identisch zur Tick-Version ist.
            double strength = mod.getStrength();      // 0..1 (pro Tick)
            int smoothness = Math.max(1, mod.getSmoothness());
            double factor = (strength / smoothness) * tickScale;
            if (factor > 1.0) factor = 1.0; // nie ueber das Ziel hinaus

            double stepYaw = yawDiff * factor;
            double stepPitch = pitchDiff * factor;

            // Max Drehung ist als Grad/Tick definiert -> ebenfalls skalieren.
            double maxTurn = mod.getMaxTurn() * tickScale;
            stepYaw = clamp(stepYaw, -maxTurn, maxTurn);
            stepPitch = clamp(stepPitch, -maxTurn, maxTurn);

            float newYaw = curYaw + (float) stepYaw;
            float newPitch = curPitch + (float) stepPitch;
            newPitch = Mth.clamp(newPitch, -90.0f, 90.0f);

            self.setYRot(newYaw);
            self.setXRot(newPitch);
                    } finally {
                com.vortex.client.core.Profiler.record("Aimbot",
                        System.nanoTime() - pvpT0);
            }
        });
    }

    /** Findet den besten Zielspieler nach den Modul-Einstellungen. */
    private static AbstractClientPlayer findBestTarget(
            Minecraft client, AimbotModule mod,
            LocalPlayer self, Vec3 eye) {

        double range = mod.getRange();
        double rangeSq = range * range;
        double fov = mod.getFov();
        boolean byAngle = mod.chooseByAngle();

        AbstractClientPlayer best = null;
        double bestScore = Double.MAX_VALUE;

        for (AbstractClientPlayer p : client.level.players()) {
            if (p == self) continue;
            if (!p.isAlive()) continue;
            if (p.isSpectator()) continue;

            double distSq = self.distanceToSqr(p);
            if (distSq > rangeSq) continue;

            Vec3 aimPoint = aimPointFor(p, mod.getTargetPoint(),
                    eye, self.getYRot(), self.getXRot());
            double dx = aimPoint.x - eye.x;
            double dy = aimPoint.y - eye.y;
            double dz = aimPoint.z - eye.z;
            double horizontal = Math.sqrt(dx * dx + dz * dz);
            float tYaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
            float tPitch = (float) (-Math.toDegrees(Math.atan2(dy, horizontal)));

            float yawDiff = Math.abs(Mth.wrapDegrees(tYaw - self.getYRot()));
            float pitchDiff = Math.abs(tPitch - self.getXRot());
            double angle = Math.sqrt(yawDiff * yawDiff + pitchDiff * pitchDiff);

            if (angle > fov) continue;

            double score = byAngle ? angle : distSq;
            if (score < bestScore) {
                bestScore = score;
                best = p;
            }
        }
        return best;
    }

    /**
     * Zielpunkt am Gegner je nach Modus:
     *   0 = Naechster (die Koerperstelle Kopf/Koerper/Fuesse, die dem aktuellen
     *       Blick am naechsten liegt -> kuerzeste Drehung), 1 = Kopf,
     *   2 = Koerper, 3 = Fuesse.
     *
     * Fuer "Nearest" braucht es den Augpunkt + die aktuelle Blickrichtung, um
     * die Winkel zu den drei Stellen zu vergleichen.
     */
    private static Vec3 aimPointFor(AbstractClientPlayer target, int mode,
                                     Vec3 eye, float curYaw, float curPitch) {
        double x = target.getX();
        double z = target.getZ();
        double baseY = target.getY();
        double height = target.getBbHeight();

        double headY = baseY + height * 0.9;
        double bodyY = baseY + height * 0.5;
        double feetY = baseY + height * 0.1;

        switch (mode) {
            case 1:
                return new Vec3(x, headY, z);
            case 2:
                return new Vec3(x, bodyY, z);
            case 3:
                return new Vec3(x, feetY, z);
            default: // 0 = Naechster: die Stelle mit der kleinsten Winkeldifferenz
                Vec3 head = new Vec3(x, headY, z);
                Vec3 body = new Vec3(x, bodyY, z);
                Vec3 feet = new Vec3(x, feetY, z);
                double aHead = angleTo(eye, head, curYaw, curPitch);
                double aBody = angleTo(eye, body, curYaw, curPitch);
                double aFeet = angleTo(eye, feet, curYaw, curPitch);
                if (aHead <= aBody && aHead <= aFeet) return head;
                if (aBody <= aHead && aBody <= aFeet) return body;
                return feet;
        }
    }

    /** Winkelabstand (Grad) vom aktuellen Blick zu einem Zielpunkt. */
    private static double angleTo(Vec3 eye, Vec3 point, float curYaw, float curPitch) {
        double dx = point.x - eye.x;
        double dy = point.y - eye.y;
        double dz = point.z - eye.z;
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        float tYaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float tPitch = (float) (-Math.toDegrees(Math.atan2(dy, horizontal)));
        float yawDiff = Math.abs(Mth.wrapDegrees(tYaw - curYaw));
        float pitchDiff = Math.abs(tPitch - curPitch);
        return Math.sqrt(yawDiff * yawDiff + pitchDiff * pitchDiff);
    }

    private static double clamp(double v, double min, double max) {
        return v < min ? min : (v > max ? max : v);
    }

    private static Module find(Class<? extends Module> type) {
        // Konstante Laufzeit statt die ganze Liste zu durchlaufen.
        return ModuleManager.INSTANCE.get(type);
    }
}
