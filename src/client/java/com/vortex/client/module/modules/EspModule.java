package com.vortex.client.module.modules;

import com.vortex.client.core.setting.ColorSetting;
import com.vortex.client.module.Module;
import java.util.HashSet;
import java.util.Set;
import net.minecraft.resources.Identifier;

/**
 * ESP: laesst ausgewaehlte Mobs mit einer leuchtenden Outline durch Waende
 * erscheinen. Welche Mobs glühen, waehlt man im ESP-Menue (Spawn-Egg-Grid);
 * die Farbe ist einstellbar.
 *
 * Die Auswahl wird als Menge von Entity-Type-IDs gehalten und ueber das
 * id-Setting (kommasepariert) persistiert.
 */
public class EspModule extends Module implements com.vortex.client.module.ExtraData, com.vortex.client.module.HasOwnScreen {

    public final ColorSetting color = new ColorSetting("Glow Color", 0xFFFF0000);

    // Aktive Mob-Typen (z.B. "minecraft:zombie"). Wird im ESP-Menue umgeschaltet.
    private final Set<String> enabledMobs = new HashSet<>();

    public EspModule() {
        super("ESP", Category.CHEATS);
        addSetting(color);
    }

    /** Ist dieser Entity-Typ (per Identifier) fuer ESP aktiviert? */
    public boolean isMobEnabled(Identifier id) {
        return id != null && enabledMobs.contains(id.toString());
    }

    public boolean isMobEnabled(String id) {
        return enabledMobs.contains(id);
    }

    public void toggleMob(String id) {
        if (!enabledMobs.add(id)) enabledMobs.remove(id);
        TYP_CACHE.clear();
    }

    public void setMob(String id, boolean on) {
        if (on) enabledMobs.add(id); else enabledMobs.remove(id);
        TYP_CACHE.clear();
    }

    /** Ergebnis je Entity-Typ (laeuft pro Entity pro Bild -- kein String-Bau). */
    private final java.util.Map<net.minecraft.world.entity.EntityType<?>, Boolean> TYP_CACHE = new java.util.concurrent.ConcurrentHashMap<>();

    public boolean isTypeEnabled(net.minecraft.world.entity.EntityType<?> typ) {
        if (typ == null) return false;
        Boolean b = TYP_CACHE.get(typ);
        if (b == null) {
            Identifier id = net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(typ);
            b = isMobEnabled(id);
            TYP_CACHE.put(typ, b);
        }
        return b;
    }

    public Set<String> getEnabledMobs() {
        return enabledMobs;
    }

    public int getGlowColor() {
        return color.get();
    }

    /** Fuer die Persistenz: aktive Mobs als kommaseparierte Liste. */
    public String serializeMobs() {
        return String.join(",", enabledMobs);
    }

    public void deserializeMobs(String data) {
        enabledMobs.clear();
        TYP_CACHE.clear();
        if (data == null || data.isEmpty()) return;
        for (String s : data.split(",")) {
            String t = s.trim();
            if (!t.isEmpty()) enabledMobs.add(t);
        }
    }

    // --- ExtraData: Zusatzliste ausserhalb der Einstellungen ---------------
    // Schluessel unveraendert, damit bestehende Presets weiter gelesen werden.

    @Override
    public String extraKey() { return "__mobs__"; }

    @Override
    public String serializeExtra() { return serializeMobs(); }

    @Override
    public void deserializeExtra(String value) { deserializeMobs(value); }

    @Override
    public void clearExtra() {
        getEnabledMobs().clear();
        TYP_CACHE.clear();
    }


    // --- HasOwnScreen: eigener Auswahlbildschirm ---------------------------
    //
    // Ohne diese beiden Methoden erscheint im ClickGUI KEIN Auswahlknopf --
    // das Modul laesst sich dann nur an- und ausschalten, aber man kann
    // nichts auswaehlen. Genau das war nach dem Umzug ins Addon der Fall:
    // die Bildschirme sind mitgewandert, aber kein Modul verwies mehr auf
    // sie, weil der Client die Klassen nicht mehr kennt.

    @Override
    public String screenButtonLabel() {
        return "Select mobs";
    }

    @Override
    public net.minecraft.client.gui.screens.Screen createScreen(net.minecraft.client.gui.screens.Screen parent) {
        return new com.vortex.client.gui.EspScreen(parent);
    }

}
