package com.vortex.client.module.modules;

import com.vortex.client.core.setting.BooleanSetting;
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
    public final NumberSetting delay = new NumberSetting("Delay (ticks)", 3, 1, 10, 1);
    public final BooleanSetting shield = new BooleanSetting("Glowstone Shield", true);
    public final BooleanSetting antiSuicide = new BooleanSetting("Anti Suicide", true);
    public final NumberSetting maxSelfDamage = new NumberSetting("Max Self Damage", 10, 0, 36, 0.5);
    public final BooleanSetting swing = new BooleanSetting("Swing", true);

    public AutoAnchorModule() {
        super("Auto Anchor", Category.CHEATS);
        addSetting(range);
        addSetting(delay);
        addSetting(shield);
        addSetting(antiSuicide);
        addSetting(maxSelfDamage);
        addSetting(swing);
    }
}
