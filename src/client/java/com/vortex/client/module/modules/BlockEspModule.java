package com.vortex.client.module.modules;

import com.vortex.client.core.setting.BooleanSetting;
import com.vortex.client.core.setting.ColorSetting;
import com.vortex.client.core.setting.NumberSetting;
import com.vortex.client.module.Module;
import java.util.HashSet;
import java.util.Set;
import net.minecraft.resources.Identifier;

/**
 * Block-ESP: hebt ausgewaehlte Bloecke in der Welt mit einer farbigen Box-
 * Outline hervor. Welche Bloecke, waehlt man im Block-ESP-Menue (Item-Grid);
 * Farbe und Such-Reichweite sind einstellbar.
 *
 * Optional zeichnet es zusaetzlich Tracer-Linien von der Bildschirmmitte zu den
 * gefundenen Bloecken (an/abschaltbar, eigene Farbe).
 *
 * Funktioniert wie das Mob-ESP, nur fuer Bloecke -- statt eines Glow-Effekts
 * (den Bloecke nicht haben) zeichnet der BlockEspRenderer die Outlines selbst.
 */
public class BlockEspModule extends Module implements com.vortex.client.module.ExtraData, com.vortex.client.module.HasOwnScreen {

    public final ColorSetting color = new ColorSetting("Color", 0xFF00FFFF);
    public final NumberSetting range = new NumberSetting("Range", 64, 16, 512, 16);
    public final NumberSetting lineWidth = new NumberSetting("Line Width", 2.0, 0.5, 5.0, 0.5);
    // Tracer: Linien von der Sicht zu den Bloecken.
    /**
     * Hoehenbereich der Suche. Sehr nuetzlich beim gezielten Suchen: fuer Diamanten
     * z.B. -59 bis 16, statt den gesamten Bereich abzusuchen. Das beschleunigt die
     * Suche stark, weil das abzusuchende Volumen direkt kleiner wird.
     */
    public final NumberSetting minY = new NumberSetting("Min Height", -64, -64, 320, 8);
    public final NumberSetting maxY = new NumberSetting("Max Height", 320, -64, 320, 8);

    /**
     * Nur Bloecke zeigen, die an mindestens einer Seite frei liegen. Blendet
     * komplett eingeschlossene Bloecke aus -- praktisch bei Kisten und Bauwerken,
     * um das Bild aufzuraeumen. Beim Erzsuchen ausgeschaltet lassen, sonst fehlen
     * die im Stein eingeschlossenen Adern.
     */
    public final BooleanSetting onlyExposed = new BooleanSetting("Exposed Only", false);

    /** Ab welcher Entfernung nicht mehr gezeichnet wird (schont die Bildrate). */
    public final NumberSetting drawDistance =
            new NumberSetting("Draw Distance", 96, 32, 256, 16);

    public final BooleanSetting tracers = new BooleanSetting("Tracers", false);
    public final ColorSetting tracerColor = new ColorSetting("Tracer Color", 0xFFFFFF00);

    // Aktive Block-Typen (z.B. "minecraft:diamond_ore").
    private final Set<String> enabledBlocks = new HashSet<>();

    public BlockEspModule() {
        super("Block-ESP", Category.CHEATS);
        addSetting(color);
        addSetting(range);
        addSetting(lineWidth);
        addSetting(minY);
        addSetting(maxY);
        addSetting(onlyExposed);
        addSetting(drawDistance);
        addSetting(tracers);
        addSetting(tracerColor);
    }

    public boolean isBlockEnabled(Identifier id) {
        return id != null && enabledBlocks.contains(id.toString());
    }

    public boolean isBlockEnabled(String id) {
        return enabledBlocks.contains(id);
    }

    public void toggleBlock(String id) {
        if (!enabledBlocks.add(id)) enabledBlocks.remove(id);
    }

    public Set<String> getEnabledBlocks() {
        return enabledBlocks;
    }

    public boolean hasAnyBlock() {
        return !enabledBlocks.isEmpty();
    }

    public int getEspColor() {
        return color.get();
    }

    public boolean tracersEnabled() {
        return tracers.get();
    }

    public int getTracerColor() {
        return tracerColor.get();
    }

    public String serializeBlocks() {
        return String.join(",", enabledBlocks);
    }

    public void deserializeBlocks(String data) {
        enabledBlocks.clear();
        if (data == null || data.isEmpty()) return;
        for (String s : data.split(",")) {
            String t = s.trim();
            if (!t.isEmpty()) enabledBlocks.add(t);
        }
    }

    // --- ExtraData: Zusatzliste ausserhalb der Einstellungen ---------------
    // Schluessel unveraendert, damit bestehende Presets weiter gelesen werden.

    @Override
    public String extraKey() { return "__blocks__"; }

    @Override
    public String serializeExtra() { return serializeBlocks(); }

    @Override
    public void deserializeExtra(String value) { deserializeBlocks(value); }

    @Override
    public void clearExtra() {
        getEnabledBlocks().clear();
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
        return "Select blocks";
    }

    @Override
    public net.minecraft.client.gui.screens.Screen createScreen(net.minecraft.client.gui.screens.Screen parent) {
        return new com.vortex.client.gui.BlockEspScreen(parent);
    }

}
