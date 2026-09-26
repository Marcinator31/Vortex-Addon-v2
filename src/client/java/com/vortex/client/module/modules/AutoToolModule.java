package com.vortex.client.module.modules;

import com.vortex.client.core.setting.BooleanSetting;
import com.vortex.client.core.setting.ModeSetting;
import com.vortex.client.core.setting.NumberSetting;
import com.vortex.client.module.Module;

/**
 * Nimmt beim Abbauen automatisch das schnellste Werkzeug aus der Hotbar.
 *
 * Werkzeuge kurz vor dem Zerbrechen werden geschont. Wenn du aufhoerst,
 * geht es auf Wunsch zurueck zum vorherigen Platz. Beim Angreifen kann
 * zusaetzlich die staerkste Waffe genommen werden.
 */
public class AutoToolModule extends Module {

    public final BooleanSetting switchBack = new BooleanSetting("Switch Back", true);
    public final NumberSetting saveDurability = new NumberSetting("Save Below Durability", 10, 0, 100, 1);
    public final BooleanSetting weapons = new BooleanSetting("Weapons When Attacking", false);

    public AutoToolModule() {
        super("Auto Tool", Category.CHEATS);
        addSetting(switchBack);
        addSetting(saveDurability);
        addSetting(weapons);
    }
}
