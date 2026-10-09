package com.vortex.client.hud;

import com.vortex.client.module.Module;
import com.vortex.client.module.ModuleManager;
import com.vortex.client.module.modules.ItemEspModule;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Item ESP: zeichnet Boxen um gedroppte Items (ItemEntities) und optional einen
 * Tracer dorthin.
 *
 * Entities sind ohnehin nur in der Render-Distanz geladen und die Iteration ist
 * billig, deshalb scannen wir hier direkt im Render-Thread (kein eigener
 * Worker noetig). Die AABB bauen wir aus Position + Groesse des Items.
 */
public final class ItemEsp {

    private ItemEsp() {}

    public static void register() {
        LevelRenderEvents.AFTER_TRANSLUCENT_FEATURES.register(context -> {
            long pvpT0 = System.nanoTime();
            try {
            ItemEspModule mod = (ItemEspModule) find(ItemEspModule.class);
            if (mod == null || !mod.isEnabled()) return;

            Minecraft client = Minecraft.getInstance();
            if (client.level == null || client.player == null) return;

            PoseStack matrices = context.poseStack();
            SubmitNodeCollector collector = context.submitNodeCollector();
            if (matrices == null || collector == null) return;

            try {
                float tickDelta = client.getDeltaTracker().getGameTimeDeltaPartialTick(false);
                Vec3 cam = EspRender.cameraOffset(client, tickDelta);

                int color = mod.getColor();
                if ((color >>> 24) == 0) color |= 0xFF000000;

                Vec3 start = EspRender.tracerStart(client, cam, tickDelta);
                boolean tracer = mod.tracerEnabled();

                // From the shared list, built once per tick.
                //
                // This walked every entity in the world on every frame -- two
                // hundred times a second for an answer that changes twenty
                // times a second.
                double maxSq = mod.maxDistance() * mod.maxDistance();
                // Alle Items in EINEM Zeichenauftrag (seit 2.44; vorher je Item ein eigener
                // Auftrag fuer Kasten und Tracer -- in einem Lager mit hunderten Items teuer).
                final java.util.List<double[]> kaesten = new java.util.ArrayList<>();
                for (ItemEntity e : com.vortex.client.core.EntityCache.items()) {
                    if (e.distanceToSqr(client.player) > maxSq) continue;
                    double w = e.getBbWidth() / 2.0, h = e.getBbHeight();
                    kaesten.add(new double[] { e.getX() - w, e.getY(), e.getZ() - w, e.getX() + w, e.getY() + h, e.getZ() + w });
                }
                if (!kaesten.isEmpty()) {
                    final Vec3 fc = cam, fs = start;
                    final int fcol = color;
                    final boolean ft = tracer;
                    EspRender.submitLines(collector, matrices, (matrix, lines) -> {
                        float r = ((fcol >> 16) & 0xFF) / 255f, g = ((fcol >> 8) & 0xFF) / 255f, b = (fcol & 0xFF) / 255f, al = ((fcol >>> 24) & 0xFF) / 255f;
                        for (double[] k : kaesten) {
                            float x0 = (float) (k[0] - fc.x), y0 = (float) (k[1] - fc.y), z0 = (float) (k[2] - fc.z);
                            float x1 = (float) (k[3] - fc.x), y1 = (float) (k[4] - fc.y), z1 = (float) (k[5] - fc.z);
                            kante(matrix, lines, x0, y0, z0, x1, y0, z0, r, g, b, al); kante(matrix, lines, x0, y1, z0, x1, y1, z0, r, g, b, al);
                            kante(matrix, lines, x0, y0, z1, x1, y0, z1, r, g, b, al); kante(matrix, lines, x0, y1, z1, x1, y1, z1, r, g, b, al);
                            kante(matrix, lines, x0, y0, z0, x0, y1, z0, r, g, b, al); kante(matrix, lines, x1, y0, z0, x1, y1, z0, r, g, b, al);
                            kante(matrix, lines, x0, y0, z1, x0, y1, z1, r, g, b, al); kante(matrix, lines, x1, y0, z1, x1, y1, z1, r, g, b, al);
                            kante(matrix, lines, x0, y0, z0, x0, y0, z1, r, g, b, al); kante(matrix, lines, x1, y0, z0, x1, y0, z1, r, g, b, al);
                            kante(matrix, lines, x0, y1, z0, x0, y1, z1, r, g, b, al); kante(matrix, lines, x1, y1, z0, x1, y1, z1, r, g, b, al);
                            if (ft) EspRender.drawTracer(matrix, lines, fs, new Vec3((k[0] + k[3]) / 2, (k[1] + k[4]) / 2, (k[2] + k[5]) / 2), fc, fcol, 1.5f);
                        }
                    });
                }
            } catch (Throwable pvpErr) {
                com.vortex.client.core.Errors.report("ItemEsp", pvpErr);
            }
                    } finally {
                com.vortex.client.core.Profiler.record("ItemEsp",
                        System.nanoTime() - pvpT0);
            }
        });
    }

    private static void kante(org.joml.Matrix4f m, com.mojang.blaze3d.vertex.VertexConsumer v, float x1, float y1, float z1,
                              float x2, float y2, float z2, float r, float g, float b, float a) {
        float dx = x2 - x1, dy = y2 - y1, dz = z2 - z1;
        float len = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (len < 1e-6f) return;
        v.addVertex(m, x1, y1, z1).setColor(r, g, b, a).setNormal(dx / len, dy / len, dz / len).setLineWidth(1.5f);
        v.addVertex(m, x2, y2, z2).setColor(r, g, b, a).setNormal(dx / len, dy / len, dz / len).setLineWidth(1.5f);
    }

    private static Module find(Class<? extends Module> type) {
        // Konstante Laufzeit statt die ganze Liste zu durchlaufen.
        return ModuleManager.INSTANCE.get(type);
    }
}
