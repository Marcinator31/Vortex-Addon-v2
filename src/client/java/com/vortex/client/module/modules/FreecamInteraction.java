package com.vortex.client.module.modules;

import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.core.BlockPos;
import com.vortex.client.freecam.Freecam;

/**
 * Erweitert die Freecam um kontextbezogenes Raycasting.
 * Ermöglicht es, Blöcke aus der virtuellen Kameraperspektive zu identifizieren.
 */
public final class FreecamInteraction {

    private FreecamInteraction() {}

    /**
     * Berechnet den Trefferpunkt vom aktuellen virtuellen Kamerapunkt aus.
     * @return Das HitResult oder null, wenn nichts getroffen wurde.
     */
    public static HitResult getVirtualRaycast(double reach) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || !Freecam.isActive()) return null;

        Vec3 startPos = Freecam.getPos();
        float yaw = Freecam.getYaw();
        float pitch = Freecam.getPitch();

        // Umrechnung von Grad in Bogenmaß
        double yawRad = Math.toRadians(yaw);
        double pitchRad = Math.toRadians(pitch);

        // Richtungsvektor berechnen
        double dx = -Math.sin(yawRad) * Math.cos(pitchRad);
        double dy = -Math.sin(pitchRad);
        double dz = Math.cos(yawRad) * Math.cos(pitchRad);

        Vec3 endPos = startPos.add(new Vec3(dx, dy, dz).scale(reach));

        // Raytrace durch die Welt
        return mc.level.clip(new net.minecraft.world.phys.ClipContext(
                startPos, 
                endPos, 
                net.minecraft.world.phys.ClipContext.Block.COLLIDER, 
                net.minecraft.world.phys.ClipContext.Fluid.NONE, 
                mc.player
        ));
    }

    /**
     * Prüft, ob die virtuelle Kamera gerade einen Block ansieht.
     * @return Die Position des Blocks oder null.
     */
    public static BlockPos getLookedAtBlock() {
        HitResult hit = getVirtualRaycast(5.0); // Standard Reichweite 5 Blöcke
        if (hit instanceof BlockHitResult bhr) {
            return bhr.getBlockPos();
        }
        return null;
    }
}
