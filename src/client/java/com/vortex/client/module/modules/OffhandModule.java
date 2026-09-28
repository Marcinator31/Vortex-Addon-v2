package com.vortex.client.module.modules;

import com.vortex.client.core.setting.BooleanSetting;
import com.vortex.client.core.setting.ModeSetting;
import com.vortex.client.core.setting.NumberSetting;
import com.vortex.client.module.Module;

/**
 * Offhand: haelt automatisch das Richtige in der zweiten Hand.
 *
 *  - Item: was normalerweise drin sein soll (Totem, Kristall, Goldapfel)
 *  - wenig Leben oder toedlicher Fall: immer Totem
 *  - Sword Gapple: Schwert/Axt in der Hand + Rechtsklick halten = Goldapfel
 *
 * Getauscht wird mit einem Inventarklick (wie die F-Taste), auch ohne
 * offenes Inventar. Laeuft Auto Totem, macht Offhand nichts -- beide wuerden
 * sich sonst gegenseitig die Hand wegnehmen.
 */
public class OffhandModule extends Module {

    public final ModeSetting item = new ModeSetting("Item", 0, "Totem", "Crystal", "Gapple");
    public final NumberSetting totemHealth = new NumberSetting("Totem Below Health", 12, 0, 36, 1);
    public final BooleanSetting totemFalling = new BooleanSetting("Totem When Falling", true);
    public final BooleanSetting swordGap = new BooleanSetting("Sword Gapple", true);
    public final BooleanSetting fallback = new BooleanSetting("Fallback To Totem", true);
    public final NumberSetting delay = new NumberSetting("Delay Ticks", 1, 0, 10, 1);

    public OffhandModule() {
        super("Offhand", Category.CHEATS);
        addSetting(item);
        addSetting(totemHealth);
        addSetting(totemFalling);
        addSetting(swordGap);
        addSetting(fallback);
        addSetting(delay);
    }
}
