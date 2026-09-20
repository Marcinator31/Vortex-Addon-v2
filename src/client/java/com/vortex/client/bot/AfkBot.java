package com.vortex.client.bot;

import com.vortex.client.module.ModuleManager;
import com.vortex.client.module.modules.AfkBotModule;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;

/**
 * AFK-Bot.
 *
 * Zwei Aufgaben, sonst nichts:
 *
 *   1. Nach dem Beitritt den AFK-Befehl senden.
 *   2. Solange man steht, in Abstaenden eine kleine Bewegung machen.
 *
 * Das Wiederverbinden nach einem Rauswurf macht Auto Reconnect im Client.
 * Dieses Modul greift danach.
 */
public final class AfkBot {

    private AfkBot() {}

    /** Tick, an dem der AFK-Befehl faellig ist. 0 = nichts geplant. */
    private static int befehlBei = 0;
    private static int letzteBewegung = 0;
    private static int tick = 0;
    private static boolean gewarnt = false;
    /** Lief das Modul im vorigen Tick schon? Erkennt das Einschalten. */
    private static boolean lief = false;
    private static boolean afkGemeldet = false;

    public static void stop() {
        befehlBei = 0;
        letzteBewegung = 0;
        gewarnt = false;
        lief = false;
        afkGemeldet = false;
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc != null && mc.options != null) mc.options.keyJump.setDown(false);
        } catch (Throwable ignored) { }
    }

    private static AfkBotModule modul() {
        try {
            return ModuleManager.INSTANCE.get(AfkBotModule.class);
        } catch (Throwable pvpErr) {
            return null;
        }
    }

    public static void register() {
        // Beitritt: Befehl einplanen.
        net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents.JOIN
                .register((handler, sender, client) -> {
                    AfkBotModule mod = modul();
                    if (mod == null || !mod.isEnabled()) return;
                    // Nicht sofort senden: direkt nach dem Beitritt laedt der
                    // Server noch, und Befehle gehen dann gern verloren.
                    befehlBei = tick + (int) (mod.joinDelay.get() * 20);
                    letzteBewegung = tick;
                });

        net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
                .END_CLIENT_TICK.register(AfkBot::tick);
    }

    private static void tick(Minecraft mc) {
        try {
            AfkBotModule mod = modul();
            if (mod == null || !mod.isEnabled()) {
                if (befehlBei != 0 || letzteBewegung != 0) stop();
                return;
            }
            LocalPlayer player = mc.player;
            if (player == null || mc.level == null) return;
            tick++;

            // LUECKE: Einschalten im laufenden Spiel tat gar nichts.
            //
            // Der Befehl wurde bisher NUR beim Beitritt eingeplant. Wer das
            // Modul einschaltet, waehrend er schon im Spiel ist -- also der
            // Normalfall -- wartete vergeblich: es passierte nie etwas, bis
            // er einmal rausflog und neu verband.
            //
            // Jetzt wird beim Einschalten genauso eingeplant wie beim
            // Beitritt.
            if (!lief) {
                lief = true;
                befehlBei = tick + (int) (mod.joinDelay.get() * 20);
                letzteBewegung = tick;
            }

            // Einmal darauf hinweisen, dass ohne Auto Reconnect der halbe
            // Kreislauf fehlt. Still nichts zu tun waere schlechter.
            if (!gewarnt) {
                gewarnt = true;
                if (!autoReconnectAn()) {
                    melde(mc, "Hinweis: Auto Reconnect ist aus -- "
                            + "nach einem Rauswurf verbindet niemand neu.");
                }
            }

            // --- 1. AFK-Befehl nach dem Beitritt --------------------------
            if (befehlBei != 0 && tick >= befehlBei) {
                befehlBei = 0;
                sendeBefehl(mc, "afk");
                // Nur beim ersten Mal melden -- sonst schreibt der Bot alle
                // 45 Sekunden in den Chat.
                if (!afkGemeldet) {
                    afkGemeldet = true;
                    melde(mc, "AFK gesendet. Halte den Zustand.");
                }
                letzteBewegung = tick;
                return;
            }

            // --- 2. Gegen die Leerlauf-Erkennung --------------------------
            if (!mod.antiIdle.get()) return;
            int abstand = (int) (mod.idleInterval.get() * 20);
            if (tick - letzteBewegung < abstand) {
                // Sprungtaste nach einem kurzen Moment wieder loslassen.
                if (mod.jump.get() && tick - letzteBewegung == 4) {
                    mc.options.keyJump.setDown(false);
                }
                return;
            }
            letzteBewegung = tick;

            // Kleine Drehung. Der Spieler bleibt stehen und verlaesst die
            // AFK-Zone nicht -- nur der Blickwinkel aendert sich.
            //
            // Der Betrag wechselt das Vorzeichen, damit er sich langfristig
            // nicht im Kreis dreht.
            float richtung = ((tick / Math.max(1, abstand)) % 2 == 0) ? 12f : -12f;
            player.setYRot(player.getYRot() + richtung);

            // WICHTIG: Die meisten Server beenden den AFK-Zustand, sobald man
            // sich bewegt. Die Bewegung gegen den Leerlauf-Rauswurf wuerde
            // also genau das aufheben, wofuer sie da ist.
            //
            // Deshalb kurz danach erneut /afk. Der Abstand von 20 Ticks gibt
            // dem Server Zeit, die Bewegung zu verarbeiten -- sonst kommt der
            // Befehl vor der Bewegung an und wird gleich wieder aufgehoben.
            befehlBei = tick + 20;

            if (mod.jump.get()) {
                // Manche Server werten nur Positionsaenderungen. Ein Sprung
                // aendert die Position, ohne den Fleck zu verlassen.
                mc.options.keyJump.setDown(true);
            }
        } catch (Throwable pvpErr) {
            com.vortex.client.core.Errors.report("AfkBot", pvpErr);
            stop();
        }
    }

    /** Ist das Auto-Reconnect-Modul eingeschaltet? */
    private static boolean autoReconnectAn() {
        try {
            for (com.vortex.client.module.Module m : ModuleManager.INSTANCE.getModules()) {
                if (m.getName().toLowerCase().contains("reconnect")) return m.isEnabled();
            }
        } catch (Throwable ignored) { }
        return false;
    }

    private static void sendeBefehl(Minecraft mc, String befehl) {
        try {
            if (mc.getConnection() != null) mc.getConnection().sendCommand(befehl);
        } catch (Throwable pvpErr) {
            com.vortex.client.core.Errors.report("AfkBot.befehl", pvpErr);
        }
    }

    private static void melde(Minecraft mc, String text) {
        try {
            if (mc.player != null) {
                mc.player.sendSystemMessage(
                        net.minecraft.network.chat.Component.literal("[AFK] " + text));
            }
        } catch (Throwable ignored) { }
    }
}
