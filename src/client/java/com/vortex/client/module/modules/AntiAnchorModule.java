package com.vortex.client.module.modules;

import com.vortex.client.core.setting.BooleanSetting;
import com.vortex.client.core.setting.NumberSetting;
import com.vortex.client.module.Module;

/**
 * Anti Anchor: Konter gegen Seelenanker-PvP (seit Addon 2.42).
 *
 *   Block Head        Obsidian direkt ueber deinen Kopf. Dorthin kann dann
 *                     kein Anker mehr -- der staerkste Platz fuer den Gegner.
 *                     Setzt er ihn eins hoeher, schirmt der Block ab.
 *   Shield Anchors    Steht ein fremder Anker bei dir, kommt ein Glowstone
 *                     zwischen ihn und dich -- derselbe Trick, den sein
 *                     "Safe Anchor" fuer ihn macht, nur jetzt fuer dich.
 *   Detonate Anchors  Danach zuendest du seinen Anker selbst (bei Bedarf
 *                     erst laden): du stehst hinter dem Schild, er nicht.
 *                     Nur, wenn dein Schaden unter "Max Self Damage" bleibt.
 *
 * Als "fremd" zaehlt ein Anker, der naeher bei dir steht als beim
 * naechsten Gegner -- deine eigenen (Auto Anchor setzt sie an den Gegner)
 * bleiben unangetastet. Nur aktiv, wenn ein Gegner in der Naehe ist.
 */
public class AntiAnchorModule extends Module {

    public final BooleanSetting blockHead = new BooleanSetting("Block Head", true);
    public final BooleanSetting shield = new BooleanSetting("Shield Anchors", true);
    public final BooleanSetting detonate = new BooleanSetting("Detonate Anchors", true);
    public final NumberSetting maxSelfDamage = new NumberSetting("Max Self Damage", 6, 0, 36, 0.5);
    public final NumberSetting range = new NumberSetting("Range", 4.5, 2.0, 6.0, 0.5);
    public final NumberSetting enemyRange = new NumberSetting("Enemy Range", 10, 4, 20, 1);
    public final BooleanSetting swing = new BooleanSetting("Swing", true);
    /** Glowstone und Obsidian aus dem Inventar in die Hotbar holen. */
    public final BooleanSetting restock = new BooleanSetting("Restock", true);

    public AntiAnchorModule() {
        super("Anti Anchor", Category.CHEATS);
        addSetting(blockHead);
        addSetting(shield);
        addSetting(detonate);
        addSetting(maxSelfDamage);
        addSetting(range);
        addSetting(enemyRange);
        addSetting(swing);
        addSetting(restock);
    }
}
