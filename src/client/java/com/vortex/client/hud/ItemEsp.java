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
                for (ItemEntity e : com.vortex.client.core.EntityCache.items()) {
                    // Distant items are dots on the screen and cost the same to
                    // draw as close ones. In a stash they are the whole cost.
                    if (e.distanceToSqr(client.player) > maxSq) continue;

                    // AABB aus Position + Groesse (kein getBoundingBox noetig).
                    double w = e.getBbWidth() / 2.0;
                    double h = e.getBbHeight();
                    double ex = e.getX(), ey = e.getY(), ez = e.getZ();
                    AABB box = new AABB(ex - w, ey, ez - w, ex + w, ey + h, ez + w);
                    EspRender.submitBox(collector, matrices, box, cam, color, 1.5f);

                    if (tracer) {
                        final Vec3 tracerStart = start;
                        final Vec3 tracerTarget = new Vec3(ex, ey + h / 2.0, ez);
                        final Vec3 tracerCam = cam;
                        final int tracerColor = color;
                        EspRender.submitLines(collector, matrices,
                                (matrix, lines) -> EspRender.drawTracer(matrix, lines,
                                        tracerStart, tracerTarget, tracerCam, tracerColor, 1.5f));
                    }
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

    private static Module find(Class<? extends Module> type) {
        // Konstante Laufzeit statt die ganze Liste zu durchlaufen.
        return ModuleManager.INSTANCE.get(type);
    }
}
