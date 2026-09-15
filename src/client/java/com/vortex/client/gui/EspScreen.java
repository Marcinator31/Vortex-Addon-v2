package com.vortex.client.gui;

import com.vortex.client.module.ModuleManager;
import com.vortex.client.module.modules.EspModule;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.SpawnEggItem;

/**
 * Auswahl der Mob-Typen fuers Mob-ESP.
 *
 * Gezeigt werden nur Mobs, die ein Spawn-Ei haben (plus der Spieler) -- das sind
 * genau die lebenden Wesen, die man sinnvoll hervorheben will. Darstellung und
 * Bedienung kommen komplett aus {@link SelectionScreen}.
 */
public class EspScreen extends SelectionScreen {

    public EspScreen(Screen parent) {
        super(parent, "Select mobs");
    }

    @Override
    protected void buildEntries() {
        entries.add(new Entry(Items.PLAYER_HEAD, "minecraft:player", "Players"));
        for (EntityType<?> type : BuiltInRegistries.ENTITY_TYPE) {
            var egg = SpawnEggItem.byId(type);
            if (egg.isEmpty()) continue; // kein Spawn-Ei -> kein Mob
            Identifier id = BuiltInRegistries.ENTITY_TYPE.getKey(type);
            if (id == null) continue;
            entries.add(new Entry(egg.get().value(), id.toString(), type.getDescription().getString()));
        }
    }

    private EspModule mod() {
        return ModuleManager.INSTANCE.get(EspModule.class);
    }

    @Override
    protected boolean isOn(String id) {
        EspModule m = mod();
        return m != null && m.isMobEnabled(id);
    }

    @Override
    protected void toggle(String id) {
        EspModule m = mod();
        if (m != null) m.toggleMob(id);
    }

    @Override
    protected void clearAll() {
        EspModule m = mod();
        if (m != null) m.getEnabledMobs().clear();
    }

    @Override
    protected String hint() {
        return "highlighted";
    }
}
