package com.vortex.client.hud;

import com.vortex.client.module.modules.FreecamModule;
import com.vortex.client.module.modules.FreecamInteraction;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.world.phys.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import com.mojang.blaze3d.vertex.PoseStack;

/**
 * Rendert ein Highlight-Quadrat um den Block, den die Freecam gerade ansieht.
 */
public final class FreecamHighlightRenderer {

    private FreecamHighlightRenderer() {}

    public static void register() {
        LevelRenderEvents.AFTER_TRANSLUCENT_FEATURES.register(context -> {
            FreecamModule mod = com.vortex.client.module.ModuleManager.INSTANCE.get(FreecamModule.class);
            if (mod == null || !mod.isEnabled() || !mod.highlightBlock.get()) return;

            Minecraft mc = Minecraft.getInstance();
            if (mc.player == null || mc.level == null) return;

            PoseStack matrices = context.poseStack();
            SubmitNodeCollector collector = context.submitNodeCollector();
            if (matrices == null || collector == null) return;

            float tickDelta = mc.getDeltaTracker().getGameTimeDeltaPartialTick(false);
            Vec3 cam = EspRender.cameraOffset(mc, tickDelta);

            // Block finden, den die virtuelle Kamera ansieht
            BlockPos pos = FreecamInteraction.getLookedAtBlock();
            if (pos == null) return;

            // AABB des Blocks erstellen
            AABB box = new AABB(pos);

            // Farbe: Ein helles, semi-transparentes Gelb/Gold
            int color = 0x88FFFF00; 

            // Box über EspRender zeichnen
            EspRender.submitBox(collector, matrices, box, cam, color, 3.0f);
        });
    }
}
