package com.vortex.client.module.modules;

import com.vortex.client.core.setting.NumberSetting;
import com.vortex.client.module.Module;

/**
 * No Fall: verhindert Fallschaden.
 *
 * Funktionsweise: Der Server berechnet Fallschaden aus der Fallhoehe, die er aus
 * den Bewegungspaketen des Clients ableitet. Meldet der Client waehrend des Falls
 * "ich stehe auf dem Boden", setzt der Server die Fallhoehe immer wieder zurueck
 * -- am Ende kommt kein Schaden an. Genau das macht dieses Modul: es setzt
 * waehrend eines Falls die Bodenmarkierung und die gezaehlte Fallhoehe zurueck.
 *
 * ACHTUNG: Das ist ein klassisches Cheat-Merkmal. Anticheats pruefen genau diesen
 * Widerspruch (Spieler faellt, meldet aber Bodenkontakt) und erkennen es sehr
 * zuverlaessig. Auf Servern mit Anticheat ist das ein hohes Bann-Risiko.
 */
public class NoFallModule extends Module {

    /**
     * Ab welcher Fallhoehe (in Bloecken) eingegriffen wird. Etwas Abstand ist
     * besser als sofort ab dem ersten Block: normales Huepfen und kleine Stufen
     * bleiben dadurch voellig unauffaellig.
     */
    /**
     * Fall distance from which it takes effect.
     *
     * Vanilla starts hurting above three blocks, so anything below that needs
     * no help. Keeping the floor there means the client tells the truth for
     * every ordinary step and hop -- and every packet that says something
     * untrue is one more chance to be noticed.
     */
    public final NumberSetting minHeight =
            new NumberSetting("Min Fall Height", 3.0, 0.5, 10.0, 0.5);

    public NoFallModule() {
        super("No Fall", Category.CHEATS);
        addSetting(minHeight);
    }
}
