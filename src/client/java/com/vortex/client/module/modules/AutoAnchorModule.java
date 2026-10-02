package com.vortex.client.module.modules;

import com.vortex.client.core.setting.BooleanSetting;
import com.vortex.client.core.setting.ModeSetting;
import com.vortex.client.core.setting.NumberSetting;
import com.vortex.client.module.Module;

/**
 * Seelenanker-PvP: setzt einen Anker neben den Gegner, laedt ihn mit
 * Glowstone und bringt ihn zur Explosion.
 *
 * Schutz wie bei Fast Anchor: bevor gezuendet wird, kommt ein Glowstone-Block
 * auf die Seite des Ankers, die zu DIR zeigt ("Glowstone Shield"). Der Server
 * rechnet den Explosionsschaden aus, BEVOR Bloecke zerstoert werden -- der
 * Glowstone steht in dem Moment noch und deckt dich ab, den Gegner auf der
 * anderen Seite nicht. Dein Schaden wird vorher (mit Schild) ausgerechnet;
 * gezuendet wird nur, wenn er unter "Max Self Damage" bleibt und dich nicht
 * toetet ("Anti Suicide").
 *
 * Braucht Seelenanker und Glowstone in der Hotbar (zwei Glowstone: einer
 * laedt, einer schuetzt). Nur in Oberwelt und End -- im Nether explodiert der
 * Anker nicht. Freunde werden nie angegriffen. Extremes Bann-Risiko.
 */
public class AutoAnchorModule extends Module {

    public final NumberSetting range = new NumberSetting("Range", 4.5, 2.0, 6.0, 0.5);
    /** 0 = setzen, Schild, laden und zuenden im SELBEN Tick (wie Fast Anchor "Instant"). */
    public final NumberSetting delay = new NumberSetting("Delay (ticks)", 3, 0, 10, 1);
    /**
     * Wohin der Anker kommt (seit 2.42).
     *   Head First  zuerst direkt ueber den Kopf des Gegners (staerkster Platz:
     *               die Explosion trifft ihn voll, er steht genau darunter),
     *               sonst daneben
     *   Head Only   nur ueber den Kopf
     *   Sides Only  nur neben ihn (wie frueher ohne Kopf)
     */
    public final ModeSetting placement = new ModeSetting("Placement", 0, "Head First", "Head Only", "Sides Only");
    public final BooleanSetting shield = new BooleanSetting("Glowstone Shield", true);
    public final BooleanSetting antiSuicide = new BooleanSetting("Anti Suicide", true);
    public final NumberSetting maxSelfDamage = new NumberSetting("Max Self Damage", 10, 0, 36, 0.5);
    public final BooleanSetting swing = new BooleanSetting("Swing", true);

    /**
     * Restock (seit 2.42): fehlt in der Hotbar Nachschub (unter 16 Stueck),
     * wird er aus dem Inventar geholt -- siehe cheat/Nachschub.
     */
    public final BooleanSetting restock = new BooleanSetting("Restock", true);

    public AutoAnchorModule() {
        super("Auto Anchor", Category.CHEATS);
        addSetting(range);
        addSetting(delay);
        addSetting(placement);
        addSetting(shield);
        addSetting(antiSuicide);
        addSetting(maxSelfDamage);
        addSetting(swing);
        addSetting(restock);
    }
}
