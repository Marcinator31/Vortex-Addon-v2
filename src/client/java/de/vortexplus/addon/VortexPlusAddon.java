package de.vortexplus.addon;

import com.vortex.client.gui.ModuleInfo;
import com.vortex.client.module.Module;
import com.vortex.client.module.ModuleManager;
import net.fabricmc.api.ClientModInitializer;

/**
 * Vortex Plus Addon fuer Minecraft 26.2.
 *
 * Enthaelt die Module der Kategorie Cheats. Sie lagen bis 2.28.17 im Vortex
 * Client selbst; seit 2.29.0 wird der Client ohne sie ausgeliefert.
 *
 * WARUM DIE PAKETNAMEN GLEICH BLEIBEN: die Dateien heissen weiterhin
 * com.vortex.client.*. Sie umzubenennen haette in jeder der 31 Dateien jede
 * Zeile beruehrt -- und damit die wahrscheinlichste Fehlerquelle geschaffen.
 * Java erlaubt dasselbe Paket in zwei Jars.
 *
 * OHNE DIESES ADDON: der Client blendet die leere Kategorie aus, und die
 * gespeicherten Cheat-Einstellungen bleiben in den Presets erhalten -- der
 * ConfigManager bewahrt Zeilen auf, zu denen er kein Modul kennt.
 */
public class VortexPlusAddon implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        register(new com.vortex.client.module.modules.AimbotModule(),
                "Aims at opponents automatically. Very high ban risk.");
        register(new com.vortex.client.module.modules.AutoHitModule(),
                "Attacks automatically when a target is in range. Very high ban risk.");
        register(new com.vortex.client.module.modules.AutoTotemModule(),
                "Moves a totem into your off hand automatically. High ban risk.");
        register(new com.vortex.client.module.modules.BlockEspModule(),
                "Highlights selected blocks, such as ores, through walls.");
        register(new com.vortex.client.module.modules.ContainerEspModule(),
                "Highlights chests, barrels and shulker boxes.");
        register(new com.vortex.client.module.modules.CrystalMacroModule(),
                "Places end crystals on obsidian and breaks them instantly. Extreme ban risk.");
        register(new com.vortex.client.module.modules.EspModule(),
                "Highlights mobs through walls.");
        register(new com.vortex.client.module.modules.FlyModule(),
                "Lets you fly. Detected almost immediately on most servers.");
        register(new com.vortex.client.module.modules.FreecamModule(),
                "Detaches the camera and lets you fly around freely.");
        register(new com.vortex.client.module.modules.ItemEspModule(),
                "Highlights dropped items on the ground.");
        register(new com.vortex.client.module.modules.NoFallModule(),
                "Prevents fall damage. High ban risk.");
        register(new com.vortex.client.module.modules.SpawnerEspModule(),
                "Highlights monster spawners.");
        register(new com.vortex.client.module.modules.StashFinderModule(),
                "Finds unusual accumulations of chests -- useful for locating bases.");
        register(new com.vortex.client.module.modules.SusChunksModule(),
                "Marks chunks that look suspicious based on their contents.");
        register(new com.vortex.client.module.modules.TunnelDetectorModule(),
                "Finds long straight tunnels that were dug by players.");

        // --- Bots ------------------------------------------------------
        register(new com.vortex.client.module.modules.NetheriteFarmerModule(),
                "Digs a tunnel at the set height until it hits Ancient Debris. Eats, "
                + "repairs with XP bottles, avoids lava and stops when something runs out.");

        registriereRenderer();

        // --- Einstellungen ERNEUT laden --------------------------------
        //
        // HIER LAG DER SPEICHERFEHLER.
        //
        // Der Client laedt die Konfiguration in seiner eigenen
        // Initialisierung. Fabric ruft die Initialisierungen der Mods aber
        // in beliebiger Reihenfolge auf -- laeuft der Client zuerst, gibt es
        // die Cheat-Module in diesem Moment noch gar nicht.
        //
        // Ihre Zeilen wandern dann in die Aufbewahrung fuer unbekannte
        // Module: sie gehen nicht verloren, werden aber auch nicht
        // angewendet. Nach aussen sah es aus, als wuerden ESP-Farben,
        // Mob-Auswahl und Tastenbelegungen bei jedem Start zurueckgesetzt.
        //
        // Jetzt wird nach dem Anmelden noch einmal geladen. Dann sind die
        // Module da und bekommen ihre gespeicherten Werte.
        try {
            com.vortex.client.core.ConfigManager.load();
            System.out.println("[vortex-plus-addon] Einstellungen nachgeladen.");
        } catch (Throwable pvpErr) {
            com.vortex.client.core.Errors.report("VortexPlusAddon.reload", pvpErr);
        }
        System.out.println("[vortex-plus-addon] 15 Module angemeldet.");
    }

    /**
     * Renderer und Tick-Aufgaben der Module.
     *
     * Diese Aufrufe standen vorher in VortexClientMod. Ohne sie werden die
     * Module zwar angemeldet, zeichnen aber nichts -- ein Fehler, der keine
     * Meldung erzeugt und deshalb leicht uebersehen wird.
     */
    private static void registriereRenderer() {
        try {
            com.vortex.client.hud.BlockEspRenderer.register();
            com.vortex.client.hud.StashFinder.register();
            com.vortex.client.hud.BlockEntityEsp.register();
            com.vortex.client.hud.ItemEsp.register();
            com.vortex.client.hud.SusChunks.register();
            com.vortex.client.hud.TunnelDetector.register();
            com.vortex.client.hud.AutoTotem.register();
            com.vortex.client.hud.Aimbot.register();
            com.vortex.client.hud.AutoHit.register();
            com.vortex.client.hud.Fly.register();
            com.vortex.client.hud.WorldScan.register();
            com.vortex.client.hud.CrystalMacro.register();
            com.vortex.client.bot.NetheriteFarmer.register();
            // registerSafety ruft der Client selbst -- hier wuerde sie
            // ein zweites Mal laufen und den Ereignis-Handler doppelt anmelden.
        } catch (Throwable pvpErr) {
            com.vortex.client.core.Errors.report("VortexPlusAddon.renderer", pvpErr);
        }
    }

    private static void register(Module module, String description) {
        try {
            ModuleManager.INSTANCE.register(module);
            ModuleInfo.register(module.getName(), description);
        } catch (Throwable pvpErr) {
            com.vortex.client.core.Errors.report(
                    "VortexPlusAddon.register:" + module.getName(), pvpErr);
        }
    }
}
