package com.vortex.client.module.modules;

import com.vortex.client.core.setting.ColorSetting;
import com.vortex.client.core.setting.NumberSetting;
import com.vortex.client.module.Module;

/**
 * Cave/Tunnel Detector: erkennt von Spielern gegrabene gerade Tunnel.
 *
 * Menschen graben gerade, 1 Block breite und 2 Block hohe Gaenge -- so etwas
 * entsteht in der Natur praktisch nie. Der Detektor sucht solche geraden Luft-
 * Linien (mit festem Boden, Decke und Seitenwaenden) und markiert sie. Da
 * Tunnel oft zu Basen oder Stashes fuehren, ist das ein Base-Hunting-Werkzeug.
 *
 * Hinweis: Rein geometrische Erkennung -- natuerliche Hoehlen koennen vereinzelt
 * Fehlalarme ausloesen. Die Mindestlaenge reduziert das.
 */
public class TunnelDetectorModule extends Module {

    // Ab welcher Laenge (in Bloecken) eine gerade Luftlinie als Tunnel zaehlt.
    public final NumberSetting minLength = new NumberSetting("Min Length", 6, 4, 32, 1);
    // Nur unterhalb dieser Hoehe suchen (Tunnel sind unterirdisch).
    public final NumberSetting maxY = new NumberSetting("Max Y", 60, -64, 320, 4);
    public final ColorSetting color = new ColorSetting("Color", 0xFF00FFFF);

    public TunnelDetectorModule() {
        super("Tunnel Detector", Category.CHEATS);
        addSetting(minLength);
        addSetting(maxY);
        addSetting(color);
    }

    public int getMinLength() { return minLength.getInt(); }
    public int getMaxY() { return maxY.getInt(); }
    public int getColor() { return color.get(); }
}
