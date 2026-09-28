package com.vortex.client.gui;

import com.vortex.client.cheat.Xray;
import com.vortex.client.module.ModuleManager;
import com.vortex.client.module.modules.XrayModule;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;

/** Auswahl der Bloecke, die Xray sichtbar laesst. */
public class XrayScreen extends SelectionScreen {

    public XrayScreen(Screen parent) {
        super(parent, "Xray blocks");
    }

    @Override
    protected void buildEntries() {
        for (Block block : BuiltInRegistries.BLOCK) {
            Item item = block.asItem();
            if (item == Items.AIR) continue;
            Identifier id = BuiltInRegistries.BLOCK.getKey(block);
            if (id == null) continue;
            entries.add(new Entry(item, id.toString(), block.getName().getString()));
        }
    }

    private XrayModule mod() {
        return ModuleManager.INSTANCE.get(XrayModule.class);
    }

    @Override
    protected boolean isOn(String id) {
        XrayModule m = mod();
        return m != null && (m.getBlocks().isEmpty()
                ? Xray.STANDARD.contains(id) : m.getBlocks().contains(id));
    }

    @Override
    protected void toggle(String id) {
        XrayModule m = mod();
        if (m == null) return;
        // Erste Aenderung an der Standardliste: sie als eigene Liste uebernehmen.
        if (m.getBlocks().isEmpty()) m.getBlocks().addAll(Xray.STANDARD);
        m.toggleBlock(id);
    }

    @Override
    protected void clearAll() {
        XrayModule m = mod();
        if (m != null) m.clearExtra();
    }

    @Override
    protected String hint() {
        return "stay visible, everything else disappears";
    }

    @Override
    public void removed() {
        super.removed();
        Xray.neuZeichnen();
    }
}
