package com.vortex.client.module.modules;

import com.vortex.client.core.setting.BooleanSetting;
import com.vortex.client.core.setting.ColorSetting;
import com.vortex.client.core.setting.ModeSetting;
import com.vortex.client.core.setting.NumberSetting;
import com.vortex.client.module.Module;

/**
 * Bed Aura (smart und sicher): setzt im Nether/End ein Bett an den Gegner und
 * sprengt es.
 *
 * SMART: jede moegliche Bett-Stellung um das Ziel wird mit derselben Formel
 * durchgerechnet wie der Server (Ruestung, Schutz, Deckung). Genommen wird die
 * mit dem meisten Schaden am Ziel.
 *
 * SICHER: gesprengt wird nur, wenn dein eigener Schaden unter "Max Self
 * Damage" bleibt und dich nicht toetet (Anti Suicide). Nie in der Oberwelt,
 * nie beim Schleichen (dann wuerde ein zweites Bett gesetzt statt gesprengt).
 */
public class BedAuraModule extends Module {

    public final NumberSetting targetRange = new NumberSetting("Target Range", 10, 4, 16, 1);
    public final ModeSetting targets = new ModeSetting("Targets", 0, "Players", "Players + Mobs");
    public final NumberSetting placeRange = new NumberSetting("Place Range", 4.5, 2, 5.5, 0.1);
    public final NumberSetting minDamage = new NumberSetting("Min Damage", 7, 1, 36, 0.5);
    public final NumberSetting maxSelfDamage = new NumberSetting("Max Self Damage", 5, 0, 36, 0.5);
    public final BooleanSetting antiSuicide = new BooleanSetting("Anti Suicide", true);
    public final NumberSetting delay = new NumberSetting("Delay Ticks", 5, 1, 20, 1);
    public final NumberSetting explodeDelay = new NumberSetting("Explode Delay", 1, 0, 5, 1);
    public final BooleanSetting pauseEating = new BooleanSetting("Pause While Eating", true);
    public final NumberSetting pauseHealth = new NumberSetting("Pause Below Health", 6, 0, 20, 1);
    public final BooleanSetting swing = new BooleanSetting("Swing", true);
    public final BooleanSetting render = new BooleanSetting("Render", true);
    public final ColorSetting color = new ColorSetting("Render Color", 0xFFFF4040);

    public BedAuraModule() {
        super("Bed Aura", Category.CHEATS);
        addSetting(targetRange);
        addSetting(targets);
        addSetting(placeRange);
        addSetting(minDamage);
        addSetting(maxSelfDamage);
        addSetting(antiSuicide);
        addSetting(delay);
        addSetting(explodeDelay);
        addSetting(pauseEating);
        addSetting(pauseHealth);
        addSetting(swing);
        addSetting(render);
        addSetting(color);
    }
}
