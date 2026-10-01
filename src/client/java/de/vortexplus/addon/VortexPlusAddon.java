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
                "Moves a totem into your off hand. Modes: Normal (anytime), Hover (open your inventory and point at a totem), Inventory Open (just open your inventory). Normal: high ban risk, Hover / Inventory Open: lower, they only click while the inventory is open.");
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
                "Detaches the camera and lets you fly around freely. Your player stays visible and keeps sending normal movement packets.");
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

        register(new com.vortex.client.module.modules.AfkBotModule(),
                "Sends /afk after joining and makes a small move now and then "
                + "so the server does not drop you for being idle.");

        // --- 4.4.0: Kampf -------------------------------------------------
        register(new com.vortex.client.module.modules.AntiKnockbackModule(),
                "Reduces knockback from hits and explosions. 0 % = none at all. High ban risk.");
        register(new com.vortex.client.module.modules.AutoArmorModule(),
                "Puts on the best armour from your inventory automatically. Medium ban risk.");
        register(new com.vortex.client.module.modules.AutoEatModule(),
                "Eats the best food in your hotbar when hunger (or health) drops. Low ban risk.");
        register(new com.vortex.client.module.modules.AutoToolModule(),
                "Switches to the fastest tool while mining, optionally the best weapon when hitting.");
        register(new com.vortex.client.module.modules.AutoAnchorModule(),
                "Places, charges and detonates respawn anchors next to enemies (not in the Nether). Glowstone Shield: a glowstone block on your side of the anchor blocks the blast for you. Extreme ban risk.");
        register(new com.vortex.client.module.modules.AutoMendModule(),
                "Throws XP bottles at your feet until your Mending gear is repaired. Pauses near enemies.");

        // --- 4.4.0: Bewegung ----------------------------------------------
        register(new com.vortex.client.module.modules.StepModule(),
                "Walk up full blocks like stairs. Not while sneaking. High ban risk.");
        register(new com.vortex.client.module.modules.JesusModule(),
                "Walk on water (and lava if enabled). Sneak to dive in. High ban risk.");
        register(new com.vortex.client.module.modules.InventoryMoveModule(),
                "Keep walking while an inventory or chest is open. Arrow keys turn the camera.");
        register(new com.vortex.client.module.modules.SpeedModule(),
                "Move faster on the ground. Strafe = fixed speed, Boost = speeds up your own movement. Very high ban risk.");
        register(new com.vortex.client.module.modules.NoSlowModule(),
                "No slowdown while eating, drinking, blocking or drawing a bow. High ban risk.");
        register(new com.vortex.client.module.modules.BoatFlyModule(),
                "Fly with a boat: WASD in look direction, jump up, sprint key down, hovers without keys. No Clip flies through blocks in singleplayer. High ban risk.");
        register(new com.vortex.client.module.modules.GhostViewModule(),
                "In F5 you look like you had Invisibility: your own body no longer blocks the view. Camera Clip lets the F5 camera go through walls, Wall Vision draws the caves behind them. The server cannot detect it (nothing is sent), but seeing through walls counts as x-ray on most servers: a risk in screenshares and recordings.");
        register(new com.vortex.client.module.modules.FastUseModule(),
                "Removes the delay between right clicks: XP bottles, snowballs, eggs, splash potions, wind charges, or everything; optionally blocks. Medium ban risk.");
        register(new com.vortex.client.module.modules.ElytraFlyModule(),
                "Better elytra flight: Control (fly freely, hover), Boost (accelerate), Firework (automatic rockets). High ban risk.");

        // --- 4.4.0: Welt ----------------------------------------------------
        register(new com.vortex.client.module.modules.ScaffoldModule(),
                "Places blocks under your feet while you walk. Tower builds straight up. Very high ban risk.");
        register(new com.vortex.client.module.modules.ChestStealerModule(),
                "Empties an opened chest into your inventory and closes it. Medium ban risk.");
        register(new com.vortex.client.module.modules.NukerModule(),
                "Breaks every block around you. Flatten keeps the ground, Instant breaks many soft blocks at once. Extreme ban risk.");
        register(new com.vortex.client.module.modules.AntiHungerModule(),
                "Hides sprinting and jumping from the server so they cost less hunger. Medium ban risk.");
        register(new com.vortex.client.module.modules.KillAuraModule(),
                "Attacks the nearest target in range automatically, even through walls. With Auto Mace + Mace Kill: one hit. Extreme ban risk.");
        register(new com.vortex.client.module.modules.MaceKillModule(),
                "Every mace hit counts as a smash from up to 22 blocks, without jumping. Needs free space above you. Extreme ban risk.");
        register(new com.vortex.client.module.modules.ReachCheatModule(),
                "Longer reach for hits (up to 6 blocks) and blocks (up to 5.5) -- the most the server accepts. High ban risk.");
        register(new com.vortex.client.module.modules.CriticalsModule(),
                "Every fully charged hit becomes a critical hit (x1.5 damage) without jumping. High ban risk.");
        register(new com.vortex.client.module.modules.AttributeSwapModule(),
                "Hold a sword: sword damage and speed, but the hit swaps to your mace (smash) or axe (breaks shields) for one packet. Axe vs Shield picks the axe automatically. Extreme ban risk.");
        // --- 2.24.0 ---------------------------------------------------------
        register(new com.vortex.client.module.modules.WTapModule(),
                "Every hit gets full sprint knockback. Packet re-sends sprint before the hit, Legit releases W for a few ticks. Medium ban risk.");
        register(new com.vortex.client.module.modules.ElytraSwapModule(),
                "Swaps chestplate and elytra: on a key, when you press jump while falling (starts gliding) and back to the best chestplate when you land.");
        register(new com.vortex.client.module.modules.AutoPotModule(),
                "Throws splash potions at your feet: healing below X HP (several in a row), regeneration, strength, speed, fire resistance before they run out. Pulls potions from your inventory. Medium ban risk.");
        register(new com.vortex.client.module.modules.AutoWebModule(),
                "Places cobwebs on your target's feet (and head). High ban risk.");
        register(new com.vortex.client.module.modules.AutoLogModule(),
                "Disconnects at low health, low totems, on a totem pop or when a player (not a friend) comes near. Turns off Auto Reconnect.");
        register(new com.vortex.client.module.modules.SpiderModule(),
                "Climb up walls by walking into them. Sneak to hold still. High ban risk.");
        register(new com.vortex.client.module.modules.SafeWalkModule(),
                "You cannot fall off edges -- like sneaking, but at full speed. Low ban risk.");
        register(new com.vortex.client.module.modules.ParkourModule(),
                "Jumps at the last moment on edges, only if there is a safe landing; stops at unsafe gaps, jumps up single blocks, auto sprint.");
        register(new com.vortex.client.module.modules.LogoutSpotsModule(),
                "Marks where players logged out near you, with a message in chat. Removed when they come back.");
        register(new com.vortex.client.module.modules.AutoRespawnModule(),
                "Respawns right after death and writes your death coordinates to chat.");
        register(new com.vortex.client.module.modules.AutoFishModule(),
                "Reels in on a bite and casts again. Detects the bite the same way the server reports it. Stops before the rod breaks.");
        register(new com.vortex.client.module.modules.SpeedMineModule(),
                "Mines faster: finishes a block at 70 % (the most the server accepts, no ghost blocks) and removes the pause between blocks. Medium ban risk.");
        // --- 2.25.0 ---------------------------------------------------------
        register(new com.vortex.client.module.modules.CrystalAuraModule(),
                "Places and breaks end crystals by itself. Calculates the damage like the server does (distance, cover, armour, protection, resistance, difficulty) and only places where the target takes enough and you do not. Face place, instant break, anti suicide, anti weakness. Extreme ban risk.");
        register(new com.vortex.client.module.modules.FastAnchorModule(),
                "One right click: place, charge and detonate a respawn anchor -- in one tick. Glowstone Shield puts a glowstone block between you and the anchor, which blocks part of the blast. Won't detonate if it would hurt you too much. Extreme ban risk.");
        register(new com.vortex.client.module.modules.GodModeModule(),
                "No damage. Singleplayer only: on servers the server calculates damage, no client can change that.");

        // --- 2.30.0 ---------------------------------------------------------
        register(new com.vortex.client.module.modules.OffhandModule(),
                "Keeps the right item in your off hand: totem, crystal or golden apple. Always a totem at low health or before a deadly fall; golden apple while you hold right click with a sword. Pauses while Auto Totem is on. High ban risk.");
        register(new com.vortex.client.module.modules.BedAuraModule(),
                "Places beds at opponents in the Nether/End and blows them up. Picks the spot with the most damage and only fires when your own damage stays under Max Self Damage (Anti Suicide). Does nothing while you sneak or in the Overworld. Extreme ban risk.");
        register(new com.vortex.client.module.modules.MiddleClickPearlModule(),
                "Middle click throws an ender pearl from anywhere in your inventory, then you hold your previous item again. Middle click on a player still adds a friend.");
        register(new com.vortex.client.module.modules.NewChunksModule(),
                "Marks chunks that were just generated (red) and chunks that existed before (green). Reads the block palette the server sends with every chunk -- works everywhere, not only near water. Servers with anti-xray can hide it.");
        register(new com.vortex.client.module.modules.XrayModule(),
                "Hides every block that is not on your list, so ores and spawners show through the ground. Select blocks to change the list (empty = all ores, debris, spawners). Servers with anti-xray show fake ores.");
        register(new com.vortex.client.module.modules.BlinkModule(),
                "Holds back your movement: the server sees you standing still until you switch Blink off, then the whole way arrives at once. The box shows where the server thinks you are. Detected by most anticheats.");
        register(new com.vortex.client.module.modules.AntiVoidModule(),
                "Catches you before you fall into the void: back to the last safe spot, a bounce, or hovering. Return uses the same steps as Click TP.");
        register(new com.vortex.client.module.modules.GhostHandModule(),
                "Right click opens chests, furnaces and other containers through walls -- the first one on your line of sight within reach. Anticheats that check line of sight block it.");
        register(new com.vortex.client.module.modules.ClickTpModule(),
                "Teleports you onto the block you look at: right click with an empty hand or a key. About 10 (Safe) or 20 (Fast) blocks per tick, longer distances in several hops; the path must be free. Detected by most anticheats.");
        register(new com.vortex.client.module.modules.CropFarmerModule(),
                "Harvests ripe wheat, carrots, potatoes, beetroot, nether wart, melons, pumpkins, sugar cane, cocoa and sweet berries around the spot where you start it, replants and collects the drops. Finds its way around fences and water, never tramples farmland, eats, fights off monsters, stops at low health, stores the harvest in a nearby chest and can use bone meal.");
        register(new com.vortex.client.module.modules.TreeFarmerModule(),
                "Chops real trees around the spot where you start it (natural leaves, single trunk), builds up for tall ones, replants a sapling and collects the wood. Finds its way to each tree, eats, fights off monsters, stops at low health, stores wood in a nearby chest, replants later when a sapling was missing and can use bone meal.");
        register(new com.vortex.client.module.modules.BotHudModule(),
                "Shows what each running bot is doing (the same line as on the bot page). Only visible while a bot runs; move it in the HUD editor.");
        register(new com.vortex.client.module.modules.ElytraAutopilotModule(),
                "Flies to a target with your elytra: /autopilot <x> <z> or /autopilot <waypoint>. Takes off, holds the cruise height with rockets, climbs over mountains, puts on a spare elytra when the worn one breaks and lands gently on a safe spot at the target.");

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
        System.out.println("[vortex-plus-addon] 69 Module angemeldet.");
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
            com.vortex.client.bot.AfkBot.register();
            // 4.4.0
            com.vortex.client.cheat.MoveCheats.register();
            com.vortex.client.cheat.PacketCheats.register();
            com.vortex.client.cheat.CombatCheats.register();
            com.vortex.client.cheat.WorldCheats.register();
            com.vortex.client.cheat.KillAura.register();
            // 2.24.0
            com.vortex.client.cheat.ExtraCheats.register();
            com.vortex.client.hud.LogoutSpots.register();
            // 2.25.0
            com.vortex.client.cheat.CrystalAura.register();
            com.vortex.client.cheat.FastAnchor.register();
            // 2.30.0 -- jedes einzeln, ein Fehler legt nicht die anderen lahm
            try { com.vortex.client.cheat.HandCheats.register(); } catch (Throwable e) { com.vortex.client.core.Errors.report("HandCheats", e); }
            try { com.vortex.client.cheat.Teleport.register(); } catch (Throwable e) { com.vortex.client.core.Errors.report("Teleport", e); }
            try { com.vortex.client.cheat.Blink.register(); } catch (Throwable e) { com.vortex.client.core.Errors.report("Blink", e); }
            try { com.vortex.client.cheat.BedAura.register(); } catch (Throwable e) { com.vortex.client.core.Errors.report("BedAura", e); }
            try { com.vortex.client.hud.NewChunks.register(); } catch (Throwable e) { com.vortex.client.core.Errors.report("NewChunks", e); }
            try { com.vortex.client.bot.CropFarmer.register(); } catch (Throwable e) { com.vortex.client.core.Errors.report("CropFarmer", e); }
            try { com.vortex.client.bot.TreeFarmer.register(); } catch (Throwable e) { com.vortex.client.core.Errors.report("TreeFarmer", e); }
            try { com.vortex.client.bot.ElytraPilot.register(); } catch (Throwable e) { com.vortex.client.core.Errors.report("ElytraPilot", e); }
            try { com.vortex.client.bot.BotHud.register(); } catch (Throwable e) { com.vortex.client.core.Errors.report("BotHud", e); }
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
