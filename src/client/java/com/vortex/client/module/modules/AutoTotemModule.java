package com.vortex.client.module.modules;

import com.vortex.client.core.setting.BooleanSetting;
import com.vortex.client.core.setting.NumberSetting;
import com.vortex.client.module.Module;

/**
 * Moves a totem into the off hand by itself.
 *
 * The delay used to be fixed at three ticks. That is a compromise nobody chose:
 * too slow when it counts, and faster than any hand could manage, which is
 * exactly the sort of thing an anti-cheat looks for. Now it is a number you
 * decide on.
 */
public class AutoTotemModule extends Module {

    /**
     * Ticks to wait before swapping in the next totem.
     *
     * A tick is 50 ms. Zero means the very next tick, which is faster than a
     * person can click and stands out accordingly.
     */
    public final NumberSetting delay = new NumberSetting("Delay (ticks)", 3, 0, 20, 1);

    /**
     * Extra random spread on the delay, in ticks.
     *
     * Identical gaps are the most obvious sign of automation -- no hand
     * produces the same interval twice. This does not make it undetectable,
     * and nothing here is built to.
     */
    public final NumberSetting jitter = new NumberSetting("Random Spread (ticks)", 0, 0, 10, 1);

    /**
     * Only act while holding something in the main hand worth protecting.
     *
     * Swapping a totem in while you are placing blocks is rarely what you
     * meant, and it costs you the off hand slot you were using.
     */
    public final BooleanSetting onlyWithWeapon = new BooleanSetting("Only With a Weapon", false);

    /** Health below which it acts. 20 means always. */
    public final NumberSetting healthBelow = new NumberSetting("Only Below Health", 20, 1, 20, 1);

    /** Say something in chat when the last totem is gone. */
    public final BooleanSetting warnEmpty = new BooleanSetting("Warn When Out", true);

    /**
     * Wie das Totem in die Off-Hand kommt.
     *
     *   Normal          wie bisher: sofort, auch bei geschlossenem Inventar
     *   Hover           Inventar oeffnen und mit der Maus ueber ein Totem
     *                   fahren -- es wandert in die Off-Hand (wie Maus
     *                   drauf + F-Taste, nur ohne Taste)
     *   Inventory Open  sobald das Inventar offen ist, kommt ein Totem in die
     *                   Off-Hand -- oeffnen, zu, fertig
     *
     * Hover und Inventory Open klicken nur, waehrend das Inventar wirklich
     * offen ist. Das sieht fuer den Server aus wie echtes Umraeumen; Normal
     * klickt bei geschlossenem Inventar, was manche Anti-Cheats erkennen.
     */
    public final com.vortex.client.core.setting.ModeSetting mode =
            new com.vortex.client.core.setting.ModeSetting("Mode", 0, "Normal", "Hover", "Inventory Open");

    /**
     * Sofort nachlegen (seit 2.41): Sobald ein Totem platzt, kommt das naechste
     * im selben Moment, in dem die Nachricht vom Server ankommt -- ohne auf die
     * Verzoegerung oder den naechsten Tick zu warten. Ein einziger Tausch-Klick.
     */
    public final BooleanSetting instant = new BooleanSetting("Instant Refill", true);

    /**
     * Totem auch in der Haupthand (seit 2.41).
     *
     * Minecraft prueft beim toedlichen Treffer ZUERST die Haupthand, dann die
     * Off-Hand. Mit einem Totem in beiden Haenden ueberlebst du zwei Treffer
     * kurz hintereinander, ohne dass dafuer irgendetwas zum Server muss --
     * genau dann, wenn das Nachlegen wegen Ping zu spaet kaeme.
     *
     *   Off           wie bisher
     *   Below Health  nur, wenn dein Leben unter "Main Hand Below Health" faellt
     *   Always        immer
     *
     * Die Haupthand wechselt dafuer auf einen Hotbar-Platz mit Totem und danach
     * wieder zurueck. Crystal Aura mit "Switch: Silent" arbeitet weiter.
     */
    public final com.vortex.client.core.setting.ModeSetting mainHand =
            new com.vortex.client.core.setting.ModeSetting("Main Hand Totem", 1, "Off", "Below Health", "Always");
    public final NumberSetting mainHandHealth = new NumberSetting("Main Hand Below Health", 10, 1, 20, 1);

    public AutoTotemModule() {
        super("Auto Totem", Category.CHEATS);
        addSetting(mode);
        addSetting(instant);
        addSetting(mainHand);
        addSetting(mainHandHealth);
        addSetting(delay);
        addSetting(jitter);
        addSetting(onlyWithWeapon);
        addSetting(healthBelow);
        addSetting(warnEmpty);
    }
}
