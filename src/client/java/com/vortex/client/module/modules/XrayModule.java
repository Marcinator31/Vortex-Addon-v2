package com.vortex.client.module.modules;

import com.vortex.client.core.setting.BooleanSetting;
import com.vortex.client.module.Module;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Xray: blendet alle Bloecke aus, die nicht in deiner Liste stehen -- Erze,
 * Spawner usw. bleiben sichtbar. Die Liste stellst du mit "Select blocks" ein
 * (leer = Standardliste mit allen Erzen, Ancient Debris und Spawnern).
 *
 * Rein optisch: der Server merkt davon nichts. Server mit Anti-Xray schicken
 * aber falsche Erze -- die siehst du dann auch.
 */
public class XrayModule extends Module implements com.vortex.client.module.ExtraData,
        com.vortex.client.module.HasOwnScreen {

    public final BooleanSetting fullbright = new BooleanSetting("Fullbright", true);

    private final Set<String> bloecke = new LinkedHashSet<>();

    public XrayModule() {
        super("Xray", Category.CHEATS);
        addSetting(fullbright);
    }

    public Set<String> getBlocks() { return bloecke; }

    public void toggleBlock(String id) {
        if (!bloecke.add(id)) bloecke.remove(id);
        com.vortex.client.cheat.Xray.listeGeaendert();
    }

    @Override
    protected void onEnable() { com.vortex.client.cheat.Xray.an(this); }

    @Override
    protected void onDisable() { com.vortex.client.cheat.Xray.aus(this); }

    @Override public String extraKey() { return "__xray__"; }
    @Override public String serializeExtra() { return String.join(",", bloecke); }
    @Override public void deserializeExtra(String value) {
        bloecke.clear();
        if (value != null) for (String s : value.split(",")) if (!s.isBlank()) bloecke.add(s.trim());
        com.vortex.client.cheat.Xray.listeGeaendert();
    }
    @Override public void clearExtra() {
        bloecke.clear();
        com.vortex.client.cheat.Xray.listeGeaendert();
    }

    @Override public String screenButtonLabel() { return "Select blocks"; }
    @Override public net.minecraft.client.gui.screens.Screen createScreen(net.minecraft.client.gui.screens.Screen parent) {
        return new com.vortex.client.gui.XrayScreen(parent);
    }
}
