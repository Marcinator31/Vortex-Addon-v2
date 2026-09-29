package com.vortex.client.cheat;

import com.vortex.client.module.ModuleManager;
import com.vortex.client.module.modules.XrayModule;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Xray (siehe XrayModule).
 *
 * Zwei Eingriffe beim Bau der Chunk-Modelle (XrayRenderMixin):
 *   1. Bloecke, die nicht in der Liste stehen, melden "unsichtbar" -- sie
 *      werden gar nicht erst ins Modell gebaut (kostet nichts pro Bild).
 *   2. Bloecke AUS der Liste zeichnen alle Seiten, auch die, die an Stein
 *      grenzen -- sonst saehe man von einem Erz nur die Seite zur Hoehle.
 * Danach muessen alle Chunks neu gebaut werden (allChanged).
 */
public final class Xray {

    private Xray() {}

    public static final Set<String> STANDARD = Set.copyOf(List.of(
            "minecraft:coal_ore", "minecraft:deepslate_coal_ore", "minecraft:iron_ore", "minecraft:deepslate_iron_ore",
            "minecraft:copper_ore", "minecraft:deepslate_copper_ore", "minecraft:gold_ore", "minecraft:deepslate_gold_ore",
            "minecraft:redstone_ore", "minecraft:deepslate_redstone_ore", "minecraft:lapis_ore", "minecraft:deepslate_lapis_ore",
            "minecraft:diamond_ore", "minecraft:deepslate_diamond_ore", "minecraft:emerald_ore", "minecraft:deepslate_emerald_ore",
            "minecraft:nether_gold_ore", "minecraft:nether_quartz_ore", "minecraft:ancient_debris", "minecraft:spawner",
            "minecraft:trial_spawner", "minecraft:vault", "minecraft:raw_iron_block", "minecraft:raw_copper_block",
            "minecraft:raw_gold_block", "minecraft:diamond_block", "minecraft:netherite_block", "minecraft:budding_amethyst"));

    private static volatile boolean aktiv = false;
    private static volatile Set<Block> sichtbar = Set.of();
    private static boolean fullbrightVonUns = false;

    /** Vom Mixin: soll dieser Block unsichtbar sein? (Chunk-Bau, auch in Neben-Threads) */
    public static boolean versteckt(BlockState st) {
        return aktiv && !sichtbar.contains(st.getBlock());
    }

    /** Vom Mixin: soll dieser Block alle Seiten zeigen? */
    public static boolean zeigeAlles(BlockState st) {
        return aktiv && sichtbar.contains(st.getBlock());
    }

    public static void an(XrayModule m) {
        listeUebernehmen(m);
        aktiv = true;
        try {
            var fb = ModuleManager.INSTANCE.get(com.vortex.client.module.modules.FullbrightModule.class);
            if (m.fullbright.get() && fb != null && !fb.isEnabled()) {
                fb.setEnabled(true);
                fullbrightVonUns = true;
            }
        } catch (Throwable e) {
            com.vortex.client.core.Errors.report("Xray.fullbright", e);
        }
        neuZeichnen();
    }

    public static void aus(XrayModule m) {
        boolean war = aktiv;
        aktiv = false;
        try {
            var fb = ModuleManager.INSTANCE.get(com.vortex.client.module.modules.FullbrightModule.class);
            if (fullbrightVonUns && fb != null && fb.isEnabled()) fb.setEnabled(false);
        } catch (Throwable e) {
            com.vortex.client.core.Errors.report("Xray.fullbright", e);
        }
        fullbrightVonUns = false;
        if (war) neuZeichnen();
    }

    public static void listeGeaendert() {
        XrayModule m = ModuleManager.INSTANCE.get(XrayModule.class);
        if (m != null) listeUebernehmen(m);
    }

    private static void listeUebernehmen(XrayModule m) {
        Set<String> ids = m.getBlocks().isEmpty() ? STANDARD : m.getBlocks();
        Set<Block> neu = new HashSet<>();
        for (String id : ids) {
            try {
                Identifier rl = Identifier.tryParse(id);
                if (rl == null) continue;
                BuiltInRegistries.BLOCK.getOptional(rl).ifPresent(neu::add);
            } catch (Throwable ignored) { }
        }
        sichtbar = neu;
    }

    /**
     * Sichtbare Chunks neu bauen lassen.
     *
     * Ueber Reflection: der Name der Methode hat sich in 26.2 geaendert
     * (allChanged gibt es dort nicht mehr), und ein harter Aufruf wuerde den
     * ganzen Build kippen. Zuerst die schonende Variante (Abschnitte als
     * veraendert markieren, wie bei einem Block-Update), dann allChanged.
     * Klappt beides nicht: Hinweis auf F3+A.
     */
    public static void neuZeichnen() {
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> {
            try {
                if (mc.level == null || mc.levelRenderer == null || mc.player == null) return;
                Object lr = mc.levelRenderer;
                int r = mc.options.renderDistance().get() + 1;
                int sx = mc.player.getBlockX() >> 4, sz = mc.player.getBlockZ() >> 4;
                int y0 = mc.level.getMinY() >> 4, y1 = (mc.level.getMaxY() - 1) >> 4;
                //#if 26.2
                //#else
                //$ // 1.21.11: Namen sind zur Laufzeit verschleiert -- direkt aufrufen.
                //$ mc.levelRenderer.setSectionRangeDirty(sx - r, y0, sz - r, sx + r, y1, sz + r);
                //$ if (true) return;
                //#endif
                try {
                    var m = lr.getClass().getMethod("setSectionRangeDirty", int.class, int.class, int.class, int.class, int.class, int.class);
                    m.invoke(lr, sx - r, y0, sz - r, sx + r, y1, sz + r);
                    return;
                } catch (NoSuchMethodException ignored) { }
                try {
                    lr.getClass().getMethod("allChanged").invoke(lr);
                    return;
                } catch (NoSuchMethodException ignored) { }
                mc.player.sendOverlayMessage(net.minecraft.network.chat.Component.literal("§dXray: press F3+A to reload the chunks."));
            } catch (Throwable e) {
                com.vortex.client.core.Errors.report("Xray.reload", e);
            }
        });
    }
}
