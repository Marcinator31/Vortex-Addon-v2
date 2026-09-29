package com.vortex.client.module.modules;

import com.vortex.client.core.setting.BooleanSetting;
import com.vortex.client.core.setting.NumberSetting;
import com.vortex.client.module.Module;

/**
 * AFK-Bot.
 *
 * Fuer Server, die einen nach einiger Zeit aus der AFK-Zone werfen. Der Bot
 * haelt den Kreislauf am Laufen: nach dem Beitritt den AFK-Befehl senden,
 * und solange man steht, in Abstaenden eine kleine Bewegung machen, damit
 * die Leerlauf-Erkennung nicht zuschlaegt.
 *
 * ZUSAMMENSPIEL MIT AUTO RECONNECT
 * Das Wiederverbinden macht der Client bereits -- dieses Modul kuemmert
 * sich nur um das, was DANACH passiert. Beide zusammen ergeben den
 * Kreislauf: rausgeworfen, wieder rein, wieder AFK.
 *
 * Auto Reconnect muss dafuer eingeschaltet sein. Ist es das nicht, sagt der
 * Bot es einmal im Chat, statt still nichts zu tun.
 */
public class AfkBotModule extends Module {

    /**
     * Wie lange nach dem Beitritt gewartet wird, bevor der Befehl kommt.
     *
     * Nicht zu kurz: direkt nach dem Beitritt laedt der Server noch, und
     * Befehle gehen in dieser Zeit gern verloren. Drei Sekunden sind ein
     * Wert, der auf den meisten Servern durchkommt.
     */
    public final NumberSetting joinDelay =
            new NumberSetting("Delay After Join (s)", 3, 1, 15, 1);

    /**
     * Kleine Bewegung gegen die Leerlauf-Erkennung.
     *
     * Viele Server werfen nach einigen Minuten ohne jede Eingabe hinaus.
     * Eine kurze Drehung reicht meist -- der Spieler bleibt dabei stehen,
     * verlaesst die AFK-Zone also nicht.
     */
    public final BooleanSetting antiIdle =
            new BooleanSetting("Anti Idle", true);

    /** Abstand zwischen den Bewegungen. */
    public final NumberSetting idleInterval =
            new NumberSetting("Idle Interval (s)", 45, 10, 300, 5);

    /**
     * Zusaetzlich kurz springen.
     *
     * Manche Server werten nur Positionsaenderungen, nicht Drehungen. Ein
     * Sprung aendert die Position, ohne dass man den Fleck verlaesst.
     */
    public final BooleanSetting jump =
            new BooleanSetting("Jump", false);

    public AfkBotModule() {
        super("AFK Bot", Category.BOTS);
        addSetting(joinDelay);
        addSetting(antiIdle);
        addSetting(idleInterval);
        addSetting(jump);
    }

    @Override
    public void onDisable() {
        com.vortex.client.bot.AfkBot.stop();
    }

    /** Statuszeile auf der Bot-Seite. */
    public String getStatus() {
        return com.vortex.client.bot.AfkBot.status();
    }
}
