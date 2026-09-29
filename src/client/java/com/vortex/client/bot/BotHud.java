package com.vortex.client.bot;

import com.vortex.client.hud.HudStyle;
import com.vortex.client.hud.HudText;
import com.vortex.client.module.ModuleManager;
import com.vortex.client.module.modules.AfkBotModule;
import com.vortex.client.module.modules.BotHudModule;
import com.vortex.client.module.modules.CropFarmerModule;
import com.vortex.client.module.modules.ElytraAutopilotModule;
import com.vortex.client.module.modules.NetheriteFarmerModule;
import com.vortex.client.module.modules.TreeFarmerModule;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;

/**
 * Zeichnet "Bot Status" (siehe BotHudModule): je laufendem Bot eine Zeile
 * mit dem, was er gerade tut.
 */
public final class BotHud {

    private BotHud() {}

    public static void register() {
        HudElementRegistry.attachElementAfter(VanillaHudElements.MISC_OVERLAYS,
                Identifier.fromNamespaceAndPath("vortexplusaddon", "bot_status"),
                (ctx, tick) -> {
                    try { render(ctx); } catch (Throwable e) { com.vortex.client.core.Errors.report("BotHud", e); }
                });
    }

    private static void render(GuiGraphicsExtractor ctx) {
        Minecraft mc = Minecraft.getInstance();
        BotHudModule m = ModuleManager.INSTANCE.get(BotHudModule.class);
        if (m == null || !m.isEnabled() || mc.font == null || mc.player == null) return;
        List<HudText.Zeile> z = new ArrayList<>();
        var crop = ModuleManager.INSTANCE.get(CropFarmerModule.class);
        if (crop != null && crop.isEnabled()) z.add(new HudText.Zeile("Crop Farmer", crop.getStatus()));
        var tree = ModuleManager.INSTANCE.get(TreeFarmerModule.class);
        if (tree != null && tree.isEnabled()) z.add(new HudText.Zeile("Tree Farmer", tree.getStatus()));
        var elytra = ModuleManager.INSTANCE.get(ElytraAutopilotModule.class);
        if (elytra != null && elytra.isEnabled()) z.add(new HudText.Zeile("Autopilot", elytra.getStatus()));
        var afk = ModuleManager.INSTANCE.get(AfkBotModule.class);
        if (afk != null && afk.isEnabled()) z.add(new HudText.Zeile("AFK Bot", afk.getStatus()));
        var nf = ModuleManager.INSTANCE.get(NetheriteFarmerModule.class);
        if (nf != null && nf.isEnabled()) z.add(new HudText.Zeile("Netherite Farmer", nf.getStatus()));
        // Im HUD-Editor ein Beispiel zeigen, damit man das Element platzieren kann
        if (z.isEmpty() && mc.gui.screen() instanceof com.vortex.client.gui.HudEditorScreen) {
            z.add(new HudText.Zeile("Crop Farmer", "Harvesting  |  42 harvested  |  3 min"));
        }
        if (z.isEmpty()) return;
        // Nie ausserhalb des Bildschirms (kleines Fenster, grosser GUI-Massstab)
        int hoehe = Math.round((z.size() * 10 + 8) * m.scale.getFloat());
        int x = Math.max(0, Math.min(m.x.getInt(), mc.getWindow().getGuiScaledWidth() - 60));
        int y = Math.max(0, Math.min(m.y.getInt(), mc.getWindow().getGuiScaledHeight() - hoehe));
        HudText.block(ctx, mc.font, x, y, m.scale.getFloat(), m.style, m.color, z,
                HudStyle.FORM_LABEL_FIRST, 0, 1f, null);
    }
}
