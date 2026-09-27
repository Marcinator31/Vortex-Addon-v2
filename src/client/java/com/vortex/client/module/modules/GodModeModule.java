package com.vortex.client.module.modules;

import com.vortex.client.module.Module;

/**
 * God Mode -- NUR im Einzelspieler (und als LAN-Gastgeber fuer dich selbst).
 *
 * Schaden berechnet immer der Server. Im Einzelspieler laeuft dieser Server in
 * deinem eigenen Minecraft -- hier wird jeder Schaden an dir verworfen (siehe
 * GodModeServerMixin). Ausnahmen, wie bei Vanilla-Unverwundbarkeit: /kill und
 * der Sturz ins Void.
 *
 * Auf fremden Servern ist das unmoeglich: dort entscheidet deren Code ueber
 * deine Lebenspunkte, kein Client kann das aendern. Was dort hilft: No Fall,
 * Anti Knockback und Auto Totem.
 */
public class GodModeModule extends Module {

    public GodModeModule() {
        super("God Mode", Category.CHEATS);
    }
}
