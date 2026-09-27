package com.vortex.client.module.modules;

import com.vortex.client.core.setting.BooleanSetting;
import com.vortex.client.core.setting.ModeSetting;
import com.vortex.client.core.setting.NumberSetting;
import com.vortex.client.module.Module;

/**
 * Fast Use -- Gegenstaende schneller hintereinander benutzen.
 *
 * Minecraft wartet nach jedem Rechtsklick 4 Ticks (0,2 s), bevor bei
 * gehaltener Taste der naechste kommt. Fast Use setzt diese Wartezeit
 * herunter: XP-Flaschen, Schneebaelle, Eier, Wurftraenke, Windkugeln -- oder
 * alles, auf Wunsch auch Bloecke.
 *
 *   Items     All / Throwables / XP Bottles Only
 *   Blocks    auch Bloecke schneller setzen (unabhaengig von "Items")
 *   Cooldown  Ticks zwischen zwei Benutzungen (0 = jeder Tick, Vanilla = 4)
 *
 * Essen, Traenke trinken, Bogen usw. werden davon nicht schneller -- die
 * haben ihre eigene Benutzungsdauer, und die prueft der Server.
 */
public class FastUseModule extends Module {

    public final ModeSetting items = new ModeSetting("Items", 1, "All", "Throwables", "XP Bottles Only");
    public final BooleanSetting blocks = new BooleanSetting("Blocks", false);
    public final NumberSetting cooldown = new NumberSetting("Cooldown (ticks)", 0, 0, 4, 1);

    public FastUseModule() {
        super("Fast Use", Category.CHEATS);
        addSetting(items);
        addSetting(blocks);
        addSetting(cooldown);
    }

    private static final java.util.Set<String> WURF = java.util.Set.of(
            "experience_bottle", "snowball", "egg", "blue_egg", "brown_egg",
            "splash_potion", "lingering_potion", "wind_charge");

    /**
     * Wartezeit fuer diesen Gegenstand, oder -1 = nicht anfassen (Vanilla).
     */
    public int wartezeit(net.minecraft.world.item.ItemStack st) {
        if (st == null || st.isEmpty()) return -1;
        boolean passt;
        if (blocks.get() && st.getItem() instanceof net.minecraft.world.item.BlockItem) {
            passt = true;
        } else {
            String id = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(st.getItem()).getPath();
            switch (items.getIndex()) {
                case 0:  passt = !(st.getItem() instanceof net.minecraft.world.item.BlockItem); break;
                case 1:  passt = WURF.contains(id); break;
                default: passt = id.equals("experience_bottle"); break;
            }
        }
        return passt ? cooldown.getInt() : -1;
    }
}
