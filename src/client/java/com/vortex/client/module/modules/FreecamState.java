package com.vortex.client.module.modules;

import net.minecraft.world.phys.Vec3;
import net.minecraft.util.Mth;

/**
 * Verwaltet den virtuellen Zustand der Freecam.
 * Trennt die physische Position des Spielers von der visuellen Position der Kamera.
 */
public class FreecamState {
    public static Vec3 position = Vec3.ZERO;
    public float yaw = 0;
    public float pitch = 0;
    public boolean active = false;

    public void updatePosition(Vec3 newPos) {
        this.position = newPos;
    }

    public void updateRotation(float yaw, float pitch) {
        this.yaw = yaw;
        this.pitch = pitch;
    }

    public void reset(Vec3 playerPos) {
        this.position = playerPos;
        this.active = false;
    }

    /**
     * Berechnet den Richtungsvektor basierend auf Yaw und Pitch.
     */
    public Vec3 getLookVector() {
        float f = Mth.fastCos(this.yaw * 0.017453292F);
        float f1 = Mth.fastSin(this.yaw * 0.017453292F);
        float f2 = Mth.fastCos(this.pitch * 0.017453292F);
        float f3 = Mth.fastSin(this.pitch * 0.017453292F);
        return new Vec3(-f * f2, -f3, -f1 * f2);
    }
}
