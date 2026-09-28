package com.vortex.client.bot;

import com.vortex.client.module.ModuleManager;
import com.vortex.client.module.modules.NetheriteFarmerModule;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

import java.util.ArrayList;
import java.util.List;

/**
 * Netherite-Farmer.
 *
 * AUFBAU: ein Zustandsautomat mit fester Rangfolge. Jeder Tick fragt der
 * Reihe nach:
 *
 *   0. Totem in die Off-Hand   (AUTO TOTEM -- laeuft jeden Tick nebenher)
 *   1. Muss ich aufhoeren?      (Spieler in der Naehe, Vorrat leer, Inventar voll)
 *   2. Muss ich essen?          (ESSEN HAT IMMER VORRANG -- auch vor Lava)
 *   3. Muss ich ueberleben?     (Lava, Feststecken)
 *   3. Muss ich reparieren?     (Mending per XP-Flasche)
 *   4. Kann ich abbauen?        (Ziel suchen, hingehen, graben)
 *
 * Diese Reihenfolge ist der Kern. Ohne sie kaempfen die Teilaufgaben
 * gegeneinander: der Bot faengt an zu essen, bricht ab, um zu graben, und
 * verhungert dabei mit halbvollem Balken.
 *
 * EINGABE ueber die Tastenbelegung des Spiels (keyAttack, keyUp ...), nicht
 * ueber direkte Bewegungsbefehle. Das ist der Weg, den auch der uebrige
 * Client benutzt, und er kann nicht mit der Spielphysik in Streit geraten.
 */
public final class NetheriteFarmer {

    private NetheriteFarmer() {}

    /** Was der Bot gerade tut -- fuer Anzeige und Protokoll. */
    public enum Zustand { AUS, SUCHT, GEHT, GRAEBT, ISST, REPARIERT, FERTIG }

    // --- Feste Werte ------------------------------------------------------

    // Ess-Grenzen stehen jetzt in den Einstellungen (Eat Below Hunger,
    // Golden Apple Below Health).
    /** Unter dieser Haltbarkeit wird repariert. */
    private static final double REPARIEREN_UNTER = 0.90;
    /** Abstand zwischen XP-Flaschen in Ticks (eine halbe Sekunde). */
    private static final int FLASCHEN_ABSTAND = 10;
    /** Wie lange der Flaschen-Platz gehalten wird. */
    private static final int FLASCHEN_HALTEN = 4;

    // --- Zustand ----------------------------------------------------------

    private static Zustand zustand = Zustand.AUS;
    private static BlockPos ziel = null;
    private static int tick = 0;
    private static int letzteFlasche = -1000;
    private static boolean gemeldet = false;

    public static Zustand zustand() { return zustand; }

    /** Beim Ausschalten des Moduls: alle Tasten loslassen. */
    public static void stop() {
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc != null && mc.options != null) {
                mc.options.keyAttack.setDown(false);
                mc.options.keyUse.setDown(false);
                mc.options.keyUp.setDown(false);
                mc.options.keyDown.setDown(false);
                mc.options.keyJump.setDown(false);
                mc.options.keyShift.setDown(false);
            }
        } catch (Throwable ignored) { }
        if (zustand != Zustand.AUS) statistikMelden(true);
        zustand = Zustand.AUS;
        ziel = null;
        richtung = null;
        gemeldet = false;
        laufSeit = 0;
        fertigGrund = null;
        debrisGefunden = 0;
        debrisZuletzt = -1;
        aktionSlot = -1;
        stehtSeit = 0;
        drehVersuche = 0;
        drinSeit = 0;
        nahSeit = 0;
        fluchtWeg = null;
        drehungenZuletzt = 0;
        ebenenWechsel = 0;
        erholungen = 0;
        letzterFund = 0;
        bautGerade = false;
        bauPhase = 0;
        bauFortschrittY = Integer.MIN_VALUE;
        bodenY = Integer.MIN_VALUE;
        aktuell = null;
        naechsteSuche = 0;
        achse = null;
        seite = null;
        musterGesetzt = null;
        querWechsel = false;
        bahnen = 0;
        BESUCHT.clear();
        fastVollGemeldet = false;
        neueFelder = 0;
        felderBeiLetzterPruefung = 0;
        echteFunde = 0;
        fakeFunde = 0;
        nurFreigelegt = false;
        BROCKEN_GESPERRT.clear();
        brockenId = null;
        aktionGrund = null;
        spielerWarNah = false;
        letzteErholung = 0;
        absichtlichWarten = -100;
        letztesAfk = -100000;
        lavaRuheBis = 0;
        Unfokussiert.stop();
    }

    /** Fuer BotAttackMixin: haelt der Bot gerade die Angriffstaste (ohne Menue)? */
    public static boolean botHaeltAngriff() {
        if (zustand == Zustand.AUS || zustand == Zustand.FERTIG) return false;
        Minecraft mc = Minecraft.getInstance();
        return mc.player != null && mc.options.keyAttack.isDown() && mc.gui.screen() == null;
    }

    /**
     * Im Hintergrund weiterlaufen: "Pause bei Fokusverlust" waehrend der Bot
     * laeuft ausschalten -- sonst oeffnet Alt-Tab das Pausemenue und im
     * Einzelspieler steht das Spiel still. Beim Stoppen wieder wie vorher.
     */
    static final class Unfokussiert {
        private static Boolean vorher = null;
        static void start(Minecraft mc) {
            if (vorher != null || mc.options == null) return;
            vorher = mc.options.pauseOnLostFocus;
            mc.options.pauseOnLostFocus = false;
        }
        static void stop() {
            if (vorher == null) return;
            try { Minecraft.getInstance().options.pauseOnLostFocus = vorher; } catch (Throwable ignored) { }
            vorher = null;
        }
    }

    private static NetheriteFarmerModule modul() {
        try {
            return ModuleManager.INSTANCE.get(NetheriteFarmerModule.class);
        } catch (Throwable pvpErr) {
            return null;
        }
    }

    public static void register() {
        net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
                .END_CLIENT_TICK.register(NetheriteFarmer::tick);
    }

    private static void tick(Minecraft mc) {
        try {
            NetheriteFarmerModule mod = modul();
            if (mod == null || !mod.isEnabled()) {
                if (zustand != Zustand.AUS) stop();
                return;
            }
            LocalPlayer player = mc.player;
            if (player == null || mc.level == null) return;
            if (player.isDeadOrDying()) {
                if (zustand != Zustand.AUS) {
                    melde(mc, "Gestorben bei " + player.blockPosition().toShortString() + " -- Bot aus.");
                    mod.setEnabled(false);
                }
                return;
            }

            // Nur im Nether -- Ancient Debris gibt es nirgendwo sonst.
            if (!imNether(mc)) {
                if (!gemeldet) {
                    gemeldet = true;
                    melde(mc, "Nur im Nether. Modul bleibt aus.");
                    tastenLos(mc);
                }
                return;
            }
            tick++;
            if (zustand == Zustand.AUS) zustand = Zustand.SUCHT;
            Unfokussiert.start(mc);

            // AUTO TOTEM: vor allem anderen und unabhaengig vom Schiedsrichter.
            // Ein Totem nachzulegen ist ein einziger Inventarklick -- dafuer
            // muss der Bot nichts unterbrechen, auch nicht das Essen.
            if (mod.autoTotem.get()) autoTotem(mc, player);
            statistik(mc, player, mod);

            // Spieler in der Naehe -> /afk
            //
            // Mit Hysterese (weg erst ab Reichweite + 8) und hoechstens ein
            // /afk pro Minute -- ein Spieler an der Grenze der Reichweite
            // loeste vorher bei jedem Hin und Her ein neues /afk aus.
            // In der Pause bleibt die Selbsterhaltung aktiv (Lava, Essen).
            if (mod.afkOnPlayer.get() && fremderSpielerNah(mc, mod)) {
                if (!gemeldet) {
                    gemeldet = true;
                    melde(mc, "Spieler in der Naehe -- gehe auf /afk.");
                    tastenLos(mc);
                    if (tick - letztesAfk > 1200) { letztesAfk = tick; sendeBefehl(mc, "afk"); }
                }
                if (inGefahr(mc, player)) { try { entscheiden(mc, player, mod); } finally { wendeBlick(player, mod); } }
                return;
            }
            // Vorrat leer -> /afk
            String fehlt = wasFehlt(player, mod);
            if (fehlt != null) {
                if (!gemeldet) {
                    gemeldet = true;
                    zustand = Zustand.FERTIG;
                    fertigGrund = fehlt;
                    melde(mc, "Fertig: " + fehlt);
                    statistikMelden(true);
                    tastenLos(mc);
                    if (mod.afkWhenOut.get() && tick - letztesAfk > 1200) { letztesAfk = tick; sendeBefehl(mc, "afk"); }
                }
                if (inGefahr(mc, player)) { try { entscheiden(mc, player, mod); } finally { wendeBlick(player, mod); } }
                return;
            }
            gemeldet = false;
            if (zustand == Zustand.FERTIG) {       // Vorrat wieder da: weiter
                zustand = Zustand.SUCHT;
                fertigGrund = null;
            }

            // Ab hier entscheidet der Schiedsrichter. try/finally sorgt
            // dafuer, dass Blick, Feststeck-Pruefung und Sprungsperre IMMER
            // laufen, egal wie ein Zweig endet.
            try {
                entscheiden(mc, player, mod);
            } finally {
                pruefeFeststecken(mc, player);
                wachhund(mc, player);
                // Kein Springen beim Abbauen (ein Sprung bricht den Schlag ab) --
                // AUSSER in Lava: dort ist Springen Schwimmen, sonst sinkt er.
                if (mc.options.keyAttack.isDown() && !player.isInLava()) {
                    mc.options.keyJump.setDown(false);
                }
                wendeBlick(player, mod);
            }
        } catch (Throwable pvpErr) {
            com.vortex.client.core.Errors.report("NetheriteFarmer", pvpErr);
            stop();
        }
    }

    /**
     * Graebt in Blickrichtung weiter.
     *
     * @param dy -1 = leicht abwaerts, 0 = waagerecht, +1 = leicht aufwaerts
     *
     * Die Richtung wird auf eine der vier Himmelsrichtungen gerundet und
     * festgehalten. Ohne das dreht sich der Bot bei jeder kleinen Abweichung
     * weiter und graebt im Kreis.
     */
    private static void grabeRichtung(Minecraft mc, LocalPlayer player,
                                      NetheriteFarmerModule mod, int dy) {
        if (richtung == null) richtung = himmelsrichtung(player.getYRot());
        // Fest auf die gemerkte Richtung ausrichten.
        willBlicken(richtungZuYaw(richtung), wunschPitch);

        int px = (int) Math.floor(player.getX());
        int py = (int) Math.floor(player.getY());
        int pz = (int) Math.floor(player.getZ());
        int vx = px + richtung[0];
        int vz = pz + richtung[1];

        // Lava vor oder neben dem Stollen: anhalten statt hineinzugraben.
        if (mod.avoidLava.get() && lavaUm(mc, vx, py + dy, vz)) {
            absichtlichWarten = tick;
            mc.options.keyAttack.setDown(false);
            mc.options.keyUp.setDown(false);
            // NUR EINMAL drehen, dann eine Weile Ruhe.
            //
            // Frueher wurde hier jeden Tick gedreht und gemeldet: der Bot
            // schrieb "Lava voraus" in Dauerschleife und drehte sich dabei
            // im Kreis, weil nach der Drehung sofort wieder Lava im neuen
            // Blickfeld lag.
            if (tick - letzteDrehung > 20) {
                letzteDrehung = tick;
                drehVersuche++;
                richtung = neueRichtung(mc, richtung);
                if (drehVersuche == 1) melde(mc, "Lava -- weiche aus.");
                // Nach vier Drehungen ist man einmal im Kreis: hier kommt
                // man nicht weiter. Lieber aufhoeren als verbrennen.
                if (drehVersuche >= 4) {
                    // Im Kreis gedreht: eine Ebene tiefer weitergraben statt
                    // aufzugeben. Unten ist meist frei, wo oben Lava steht.
                    drehVersuche = 0;
                    ebenenWechsel = tick;
                    melde(mc, "Ringsum Lava -- weiche nach unten aus.");
                }
            }
            return;
        }
        drehVersuche = 0;

        // --- Gefahr von OBEN pruefen, bevor der Block faellt --------------
        //
        // Zwei Dinge toeten den Bot beim Vorwaertsgraben:
        //
        //  - Lava ueber dem Stollen. Bricht man den Block darunter weg,
        //    laeuft sie herein. Das merkt man erst, wenn man drinsteht.
        //  - Kies oder Sand. Der faellt nach, verschuettet den Gang und den
        //    Bot gleich mit -- genau der Fall, der ihn ersticken liess.
        //
        // Deshalb wird die Decke geprueft, BEVOR gegraben wird.
        // Oberster Block, der gleich wegkommt: beim Runtergraben die
        // Kopfhoehe (py+1), beim Hochgraben py+2. Darueber darf keine Lava sein.
        int oberster = py + (dy > 0 ? 2 : 1);
        BlockPos ueberKopf = new BlockPos(vx, oberster + 1, vz);
        if (mod.avoidLava.get() && istLava(mc, ueberKopf)) {
            absichtlichWarten = tick;
            mc.options.keyAttack.setDown(false);
            mc.options.keyUp.setDown(false);
            if (tick - letzteDrehung > 20) {
                letzteDrehung = tick;
                drehVersuche++;
                richtung = neueRichtung(mc, richtung);
                if (drehVersuche == 1) melde(mc, "Lava ueber dem Stollen -- weiche aus.");
                if (drehVersuche >= 4) {
                    drehVersuche = 0;
                    ebenenWechsel = tick;
                    melde(mc, "Ringsum Lava -- weiche nach unten aus.");
                }
            }
            return;
        }

        // WAS MUSS WEG, DAMIT ER IN DIE NAECHSTE SPALTE KOMMT?
        //
        // Hier lag der Fehler beim Runter- und Hochgraben: es wurden immer nur
        // zwei Bloecke frei gemacht, eine Stufe tiefer bzw. hoeher. Beim
        // Hineinlaufen steckt der Spieler aber noch auf SEINER Hoehe -- der
        // Kopf stiess an (vx, py+1), und er kam nie in die Stufe hinein.
        // Beim Hochgraben fehlte der Platz ueber dem eigenen Kopf zum
        // Springen. Die Feststeck-Erkennung sprang dann dazwischen, und es
        // wurde das bekannte Durcheinander.
        //
        //   eben   (dy= 0): naechste Spalte py+1, py
        //   runter (dy=-1): naechste Spalte py+1, py, py-1  (drei hoch)
        //   hoch   (dy=+1): ueber mir py+2, naechste Spalte py+2, py+1
        //                   (py bleibt als Stufe stehen)
        //
        // Immer von oben nach unten: so faellt nichts nach, und der Blick
        // wandert gleichmaessig.
        BlockPos[] frei;
        if (dy < 0) {
            frei = new BlockPos[]{ new BlockPos(vx, py + 1, vz), new BlockPos(vx, py, vz),
                                   new BlockPos(vx, py - 1, vz) };
        } else if (dy > 0) {
            frei = new BlockPos[]{ new BlockPos(px, py + 2, pz), new BlockPos(vx, py + 2, vz),
                                   new BlockPos(vx, py + 1, vz) };
        } else {
            frei = new BlockPos[]{ new BlockPos(vx, py + 1, vz), new BlockPos(vx, py, vz) };
        }

        // Fallendes Material zuerst wegraeumen.
        //
        // Liegt Kies oder Sand ueber dem Stollen, faellt er beim Graben nach.
        // Ihn von OBEN abzubauen statt von vorne loest den Stau, statt ihn
        // immer wieder nachrutschen zu lassen.
        BlockPos faellt = fallendesUeber(mc, vx, py + dy, vz);
        BlockPos zielBlock;
        if (faellt != null) {
            zielBlock = faellt;
        } else {
            zielBlock = null;
            for (BlockPos q : frei) {
                if (fest(mc, q)) { zielBlock = q; break; }
            }
        }

        waehleSpitzhacke(player);
        if (zielBlock != null) {
            if (!schlagen(mc, zielBlock)) {
                // Kommt nicht durch -- andere Richtung versuchen.
                melde(mc, "Block bricht nicht -- neue Richtung.");
                richtung = neueRichtung(mc, richtung);
                letzteDrehung = tick;
                return;
            }
            blickeAuf(player, zielBlock);
            mc.options.keyUp.setDown(false);
            mc.options.keyAttack.setDown(blickFertig(player));
        } else {
            schlagenZuruecksetzen();
            // Frei: vorruecken.
            mc.options.keyAttack.setDown(false);
            willBlicken(richtungZuYaw(richtung), dy < 0 ? 30f : (dy > 0 ? -30f : 0f));
            // Loch voraus? Dann NICHT hineinlaufen.
            //
            // Unter einem Loch liegt im Nether oft Lava. Zwei Bloecke tief
            // ist ein Sprung noch harmlos, darunter wird es gefaehrlich --
            // dann lieber die Richtung wechseln.
            BlockPos boden = new BlockPos(vx, py + dy - 1, vz);
            if (!sichererBoden(mc, vx, py + dy, vz)) {
                absichtlichWarten = tick;
                mc.options.keyUp.setDown(false);
                // Soll er ohnehin nach unten (z. B. oben auf der eigenen
                // Saeule nach dem Hochbauen)? Dann den Block unter sich
                // abbauen und einen Block tiefer fallen -- solange darunter
                // fester Boden ist und keine Lava.
                BlockPos unterMir = new BlockPos(px, py - 1, pz);
                BlockPos darunter = new BlockPos(px, py - 2, pz);
                if (dy < 0 && fest(mc, unterMir) && fest(mc, darunter)
                        && !istLava(mc, darunter.below()) && darfAbbauen(mc, unterMir)
                        && schlagen(mc, unterMir)) {
                    willBlicken(player.getYRot(), 90f);
                    waehleSpitzhacke(player);
                    mc.options.keyAttack.setDown(blickFertig(player));
                    return;
                }
                if (tick - letzteDrehung > 20) {
                    letzteDrehung = tick;
                    richtung = neueRichtung(mc, richtung);
                    melde(mc, "Abgrund voraus -- neue Richtung.");
                }
                return;
            }

            // Faellt gerade Kies/Sand in die naechste Spalte? Kurz warten --
            // sonst landet er auf dem Kopf (als fallender Block ist er fuer
            // die Blockpruefung noch unsichtbar).
            if (fallendesImAnflug(mc, vx, py + dy, vz)) {
                absichtlichWarten = tick;
                mc.options.keyUp.setDown(false);
                return;
            }
            // Erst ausrichten, dann laufen -- sonst laeuft er schraeg aus
            // dem Stollen heraus, waehrend sich der Blick noch dreht.
            mc.options.keyUp.setDown(Math.abs(restWinkel(player)) < 30f);
            // Liegt eine Stufe voraus, druebersteigen statt dagegenzulaufen.
            // Nur wenn oben wirklich Platz ist, sonst springt er gegen die
            // Decke und kommt nicht weiter.
            //
            // NIEMALS gleichzeitig mit dem Abbauen: ein Sprung unterbricht
            // den Schlag, und der Block faengt von vorne an. Hier wird nicht
            // abgebaut, deshalb ist es an dieser Stelle unbedenklich.
            boolean stufe = fest(mc, new BlockPos(vx, py, vz))
                    && !fest(mc, new BlockPos(vx, py + 1, vz))
                    && !fest(mc, new BlockPos(vx, py + 2, vz))
                    && !fest(mc, new BlockPos(px, py + 2, pz));
            mc.options.keyJump.setDown(stufe);
        }
    }


    // ======================================================================
    // Sicherheit beim Abbauen (2.27.0)
    // ======================================================================
    //
    // Vorher hatte jeder Abbau-Weg seine eigene (oder gar keine) Pruefung:
    // der Debris-Abbau, das Freigraben und die Flucht bauten Bloecke ab, hinter
    // denen Lava stand. Jetzt gibt es EINE Pruefung fuer alle.

    /** Darf dieser Block weg? Nein bei Grundgestein und bei Lava darueber/daneben/darunter. */
    private static boolean darfAbbauen(Minecraft mc, BlockPos p) {
        var b = mc.level.getBlockState(p).getBlock();
        if (b == Blocks.BEDROCK) return false;
        if (lavaDran(mc, p) || istLava(mc, p.below())) return false;
        return !fallendesImAnflug(mc, p.getX(), p.getY(), p.getZ());
    }

    /** Ist der naechste Schritt Richtung (zx, zz) sicher? (Boden da, keine Lava) */
    private static boolean sichererSchritt(Minecraft mc, LocalPlayer player, int zx, int zz) {
        int px = (int) Math.floor(player.getX()), py = (int) Math.floor(player.getY()), pz = (int) Math.floor(player.getZ());
        int dx = zx - px, dz = zz - pz;
        if (dx == 0 && dz == 0) return true;
        int rx, rz;
        if (Math.abs(dx) >= Math.abs(dz)) { rx = Integer.signum(dx); rz = 0; } else { rx = 0; rz = Integer.signum(dz); }
        return sichererBoden(mc, px + rx, py, pz + rz);
    }

    /** Fester Boden unter (x, y, z)? Ein Block Luft ist ok (kleiner Sprung), Lava nie. */
    private static boolean sichererBoden(Minecraft mc, int x, int y, int z) {
        BlockPos b1 = new BlockPos(x, y - 1, z), b2 = new BlockPos(x, y - 2, z);
        if (istLava(mc, b1) || istLava(mc, new BlockPos(x, y, z))) return false;
        if (fest(mc, b1)) return true;
        return fest(mc, b2) && !istLava(mc, b2.below());
    }

    /**
     * Faellt gerade Kies/Sand (als fallender Block) auf diese Spalte zu?
     * Als Entity ist er fuer getBlockState unsichtbar -- genau so wurde der
     * Bot frueher verschuettet: die Spalte sah frei aus.
     */
    private static boolean fallendesImAnflug(Minecraft mc, int x, int y, int z) {
        try {
            for (var e : com.vortex.client.core.EntityCache.all()) {
                if (!(e instanceof net.minecraft.world.entity.item.FallingBlockEntity)) continue;
                if (Math.abs(e.getX() - (x + 0.5)) < 1.2 && Math.abs(e.getZ() - (z + 0.5)) < 1.2
                        && e.getY() > y - 1 && e.getY() < y + 8) return true;
            }
        } catch (Throwable ignored) { }
        return false;
    }

    // ======================================================================
    // Feuerschutz-Trank (2.27.0)
    // ======================================================================

    /** Slot eines Feuerschutz-Tranks (trinkbar), oder -1. */
    private static int feuerTrank(LocalPlayer player, boolean nurHotbar) {
        int bis = nurHotbar ? 9 : 36;
        for (int i = 0; i < bis; i++) {
            ItemStack st = player.getInventory().getItem(i);
            if (st == null || st.isEmpty() || st.getItem() != Items.POTION) continue;
            var pc = st.get(net.minecraft.core.component.DataComponents.POTION_CONTENTS);
            if (pc != null && (pc.is(net.minecraft.world.item.alchemy.Potions.FIRE_RESISTANCE)
                    || pc.is(net.minecraft.world.item.alchemy.Potions.LONG_FIRE_RESISTANCE))) return i;
        }
        return -1;
    }

    /** Restzeit Feuerschutz in Ticks (0 = keiner). */
    private static int feuerschutzRest(LocalPlayer player) {
        var eff = player.getEffect(net.minecraft.world.effect.MobEffects.FIRE_RESISTANCE);
        return eff == null ? 0 : eff.getDuration();
    }

    /** Steht dort ein fester Block, der abgebaut werden muss? */
    private static boolean fest(Minecraft mc, BlockPos p) {
        var st = mc.level.getBlockState(p);
        if (st.isAir()) return false;
        // Fluessigkeiten nicht anschlagen -- Lava wird vorher geprueft.
        if (st.getBlock() == Blocks.LAVA) return false;
        return true;
    }

    /** Lava direkt am geplanten Stollenabschnitt? */
    private static boolean lavaUm(Minecraft mc, int x, int y, int z) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                for (int dy = 0; dy <= 2; dy++) {
                    if (istLava(mc, new BlockPos(x + dx, y + dy, z + dz))) return true;
                }
            }
        }
        return false;
    }

    /** Ist auf dem Weg zum Ziel Lava? */
    private static boolean lavaImWeg(Minecraft mc, LocalPlayer player,
                                     NetheriteFarmerModule mod) {
        if (!mod.avoidLava.get()) return false;
        return lavaVoraus(mc, player);
    }

    /**
     * Sucht freigelegtes Ancient Debris in kurzer Reichweite.
     *
     * Bewusst klein: das ist kein Ersatz fuer das Graben, sondern greift
     * mit, was beim Graben ohnehin sichtbar wird.
     */
    private static BlockPos debrisInDerNaehe(Minecraft mc, LocalPlayer player,
                                             NetheriteFarmerModule mod) {
        int r = mod.debrisRange.getInt();
        BlockPos mitte = player.blockPosition();
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        BlockPos beste = null;
        double besteD = Double.MAX_VALUE;
        // Hoehe begrenzt: mehr als 6 darueber wird ohnehin verworfen (siehe
        // unten), mehr als 8 darunter liegt in der Lavaseen-Zone. Spart bei
        // Reichweite 20 fast zwei Drittel der Blockabfragen.
        int dyMin = -Math.min(r, 8), dyMax = Math.min(r, 6);
        for (int dx = -r; dx <= r; dx++) {
            for (int dy = dyMin; dy <= dyMax; dy++) {
                for (int dz = -r; dz <= r; dz++) {
                    p.set(mitte.getX() + dx, mitte.getY() + dy, mitte.getZ() + dz);
                    if (mc.level.getBlockState(p).getBlock() != Blocks.ANCIENT_DEBRIS) continue;
                    if (istGesperrt(p)) continue;        // schon aufgegeben
                    // Anti-Xray erkannt: nur Debris, das an Luft grenzt, ist echt.
                    if (nurFreigelegt && !freigelegt(mc, p)) continue;
                    // Abstand selbst rechnen statt distSqr, und die Position
                    // neu bauen statt immutable(): beides benutzt nur
                    // Methoden, die anderswo im Projekt vorkommen.
                    double ddx = p.getX() - mitte.getX();
                    double ddy = p.getY() - mitte.getY();
                    double ddz = p.getZ() - mitte.getZ();

                    // HOEHE ZAEHLT MEHR ALS LUFTLINIE.
                    //
                    // Rein nach Luftlinie galt ein Debris vier Bloecke UEBER
                    // dem Bot als naeher als eines sechs Bloecke seitlich.
                    // Das obere braucht aber Hochbauen -- das langsamste und
                    // fehleranfaelligste Verhalten des ganzen Bots. Das
                    // seitliche nur einen kurzen Stollen.
                    //
                    // Nach oben wiegt ein Block deshalb dreimal so viel wie
                    // seitlich, nach unten anderthalbmal (Graben nach unten
                    // ist einfach, nur etwas langsamer als geradeaus).
                    // Mehr als 6 Bloecke darueber: gar nicht erst anpeilen.
                    //
                    // Sonst baute der Bot bei Reichweite 20 unter Umstaenden
                    // zwanzig Bloecke hoch -- langsam, und je hoeher, desto
                    // eher trifft er auf Lava in der Decke oder stuerzt ab.
                    // Solches Debris liegt meist ohnehin in einer anderen
                    // Hoehlenschicht und wird spaeter von dort aus erreicht.
                    if (ddy > 6) continue;
                    double gewicht = (ddy > 0) ? 9.0 : 2.25;   // quadriert: 3x bzw. 1.5x
                    double d = ddx * ddx + gewicht * ddy * ddy + ddz * ddz;
                    if (d < besteD) {
                        besteD = d;
                        beste = new BlockPos(p.getX(), p.getY(), p.getZ());
                    }
                }
            }
        }
        return beste;
    }

    // --- Anti-Xray ---------------------------------------------------------
    //
    // Paper-Server (Anti-Xray Modus 2) fuellen verdecktes Gestein mit
    // FALSCHEM Debris. Der Bot lief dann jedem Phantom hinterher: kaum ist
    // der Nachbarblock weg, schickt der Server den echten Block (Netherrack)
    // und das Ziel "verschwindet".
    //
    // Erkennung: Ziel wird zu etwas anderem als Luft, ohne dass wir es
    // abgebaut haben -> Phantom. Ab drei Phantomen (und mehr Phantome als
    // echte Funde) zaehlt nur noch Debris, das schon an Luft grenzt -- das
    // kann der Server nicht faelschen.

    private static int echteFunde = 0;
    private static int fakeFunde = 0;
    private static boolean nurFreigelegt = false;

    private static boolean freigelegt(Minecraft mc, BlockPos p) {
        for (net.minecraft.core.Direction d : net.minecraft.core.Direction.values()) {
            if (mc.level.getBlockState(p.relative(d)).isAir()) return true;
        }
        return false;
    }

    private static void zielVerschwunden(Minecraft mc, BlockPos pos) {
        if (mc.level.getBlockState(pos).isAir()) return;     // abgebaut -- normal
        fakeFunde++;
        if (!nurFreigelegt && fakeFunde >= 3 && fakeFunde > echteFunde) {
            nurFreigelegt = true;
            melde(mc, "Anti-Xray erkannt (" + fakeFunde + " falsche Debris) -- hole nur noch freigelegtes.");
        }
    }

    // --- Richtungen -------------------------------------------------------

    /** Gemerkte Grabrichtung als {dx, dz}. */
    private static int[] richtung = null;

    private static int[] himmelsrichtung(float yaw) {
        float y = ((yaw % 360) + 360) % 360;
        if (y < 45 || y >= 315) return new int[]{0, 1};    // Sued
        if (y < 135) return new int[]{-1, 0};              // West
        if (y < 225) return new int[]{0, -1};              // Nord
        return new int[]{1, 0};                            // Ost
    }

    private static float richtungZuYaw(int[] r) {
        if (r[0] == 0 && r[1] == 1) return 0f;
        if (r[0] == -1) return 90f;
        if (r[1] == -1) return 180f;
        return -90f;
    }

    /** Vierteldrehung -- wenn Lava den Weg versperrt. */
    private static int[] drehe(int[] r) {
        return new int[]{-r[1], r[0]};
    }

    /** Ist direkt vor dem Spieler Lava? */
    private static boolean lavaVoraus(Minecraft mc, LocalPlayer player) {
        double rad = Math.toRadians(player.getYRot());
        int vx = (int) Math.round(player.getX() - Math.sin(rad) * 1.5);
        int vz = (int) Math.round(player.getZ() + Math.cos(rad) * 1.5);
        for (int dy = 0; dy <= 1; dy++) {
            BlockPos p = new BlockPos(vx, (int) player.getY() + dy, vz);
            if (mc.level.getBlockState(p).getBlock() == Blocks.LAVA) return true;
        }
        return false;
    }

    // ------------------------------------------------------------------
    // Hilfsmittel
    // ------------------------------------------------------------------

    private static void blickeAuf(LocalPlayer player, BlockPos pos) {
        double dx = pos.getX() + 0.5 - player.getX();
        // getEyePosition statt getEyeHeight: in Freecam.java belegt, also
        // sicher vorhanden.
        double dy = pos.getY() + 0.5 - player.getEyePosition().y;
        double dz = pos.getZ() + 0.5 - player.getZ();
        double flach = Math.sqrt(dx * dx + dz * dz);
        // Steht das Ziel fast senkrecht ueber oder unter dem Bot, ist die
        // Richtung unbestimmt: atan2(0,0) springt bei jedem Tick auf einen
        // anderen Wert, und der Bot dreht sich im Kreis, statt hochzusehen.
        // Dann die Drehung beibehalten und nur die Neigung aendern.
        float yaw = (flach < 0.3)
                ? player.getYRot()
                : (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
        willBlicken(yaw, (float) -Math.toDegrees(Math.atan2(dy, flach)));
    }

    private static void waehleSpitzhacke(LocalPlayer player) {
        int slot = besteSpitzhacke(player, true);
        if (slot >= 0 && player.getInventory().getSelectedSlot() != slot) {
            player.getInventory().setSelectedSlot(slot);
        }
    }

    private static boolean istSpitzhacke(ItemStack st) {
        return st != null && !st.isEmpty() && (st.getItem() == Items.NETHERITE_PICKAXE || st.getItem() == Items.DIAMOND_PICKAXE);
    }

    private static int stufe(ItemStack st, net.minecraft.resources.ResourceKey<net.minecraft.world.item.enchantment.Enchantment> key) {
        try {
            for (var e : st.getEnchantments().entrySet()) if (e.getKey().is(key)) return e.getIntValue();
        } catch (Throwable ignored) { }
        return 0;
    }

    private static boolean hatMending(ItemStack st) {
        return stufe(st, net.minecraft.world.item.enchantment.Enchantments.MENDING) > 0;
    }

    /** Anteil Rest-Haltbarkeit 0..1. */
    private static double rest(ItemStack st) {
        if (st == null || st.isEmpty() || !st.isDamageableItem() || st.getMaxDamage() <= 0) return 1.0;
        return (st.getMaxDamage() - st.getDamageValue()) / (double) st.getMaxDamage();
    }

    /**
     * Beste Spitzhacke: Netherit vor Diamant, dann hoehere Effizienz, dann
     * mehr Haltbarkeit. Fast kaputte (unter 3 %) nur, wenn es keine andere
     * gibt -- und nie, wenn sie ohne Mending gleich zerbrechen wuerde.
     */
    private static int besteSpitzhacke(LocalPlayer player, boolean nurHotbar) {
        int bis = nurHotbar ? 9 : 36;
        int beste = -1;
        double bestePunkte = -1;
        for (int i = 0; i < bis; i++) {
            ItemStack st = player.getInventory().getItem(i);
            if (!istSpitzhacke(st)) continue;
            double r = rest(st);
            double punkte = (st.getItem() == Items.NETHERITE_PICKAXE ? 1000 : 0)
                    + stufe(st, net.minecraft.world.item.enchantment.Enchantments.EFFICIENCY) * 100
                    + r * 50;
            if (r < 0.03) punkte -= 5000;          // fast kaputt: nur als letzte Wahl
            if (punkte > bestePunkte) { bestePunkte = punkte; beste = i; }
        }
        return beste;
    }

    /** Gibt es eine Spitzhacke, mit der man noch sicher graben kann? */
    private static boolean brauchbareSpitzhacke(LocalPlayer player) {
        for (int i = 0; i < 36; i++) {
            ItemStack st = player.getInventory().getItem(i);
            if (!istSpitzhacke(st)) continue;
            if (rest(st) >= 0.03) return true;
            if (hatMending(st) && findeSlot(player, Items.EXPERIENCE_BOTTLE) >= 0) return true;
        }
        return false;
    }

    /** Gegenstand aus der Hotbar auswaehlen und benutzen. */
    // --- Aktionssperre ---------------------------------------------------
    //
    // Essen und Flaschenwerfen brauchen mehrere Ticks. Ohne Sperre passierte
    // zweierlei:
    //
    //  - Die Benutzen-Taste wurde gedrueckt und NIE losgelassen. Der Bot warf
    //    XP-Flaschen, bis keine mehr da war.
    //  - Im naechsten Tick schaltete eine andere Stelle zurueck auf die
    //    Spitzhacke, bevor die Aktion ueberhaupt begonnen hatte. Der Bot
    //    wechselte hin und her, ohne je etwas zu benutzen.
    //
    // Solange eine Aktion laeuft, gehoert ihr der Hotbar-Platz allein.

    private static int aktionSlot = -1;
    private static String aktionGrund = null;      // warum gerade gegessen wird
    private static int aktionBis = 0;
    private static boolean aktionHalten = false;   // Taste gedrueckt halten?

    /**
     * Startet eine Aktion.
     *
     * @param dauer   Ticks, die sie belegt
     * @param halten  true = Taste gedrueckt halten (Essen),
     *                false = ein Tastendruck (Flasche werfen)
     */
    private static boolean starteAktion(Minecraft mc, LocalPlayer player,
                                        int slot, int dauer, boolean halten) {
        if (slot > 8) return false;   // nur aus der Hotbar
        if (aktionSlot >= 0) return true;   // laeuft schon
        player.getInventory().setSelectedSlot(slot);
        aktionSlot = slot;
        aktionBis = tick + dauer;
        aktionHalten = halten;
        mc.options.keyAttack.setDown(false);
        mc.options.keyUp.setDown(false);
        mc.options.keyUse.setDown(true);
        return true;
    }

    /**
     * Haelt eine laufende Aktion aufrecht.
     *
     * @return true, solange sie laeuft -- dann macht der Bot sonst nichts.
     */
    private static boolean aktionLaeuft(Minecraft mc, LocalPlayer player) {
        if (aktionSlot < 0) return false;

        if (tick >= aktionBis) {
            mc.options.keyUse.setDown(false);
            aktionSlot = -1;
            return false;
        }
        // Platz festhalten, damit niemand dazwischenschaltet.
        if (player.getInventory().getSelectedSlot() != aktionSlot) {
            player.getInventory().setSelectedSlot(aktionSlot);
        }
        // Werfen ist EIN Druck: nach dem ersten Tick loslassen, sonst
        // fliegen alle Flaschen hintereinander weg.
        if (!aktionHalten) mc.options.keyUse.setDown(false);
        return true;
    }

    private static void tastenLos(Minecraft mc) {
        mc.options.keyAttack.setDown(false);
        mc.options.keyUse.setDown(false);
        mc.options.keyUp.setDown(false);
        mc.options.keyJump.setDown(false);
        mc.options.keyDown.setDown(false);
    }

    /** Sucht einen Gegenstand im ganzen Inventar. -1 wenn nicht vorhanden. */
    private static int findeSlot(LocalPlayer player, net.minecraft.world.item.Item item) {
        int size = Math.min(player.getInventory().getContainerSize(), 36);
        for (int i = 0; i < size; i++) {
            ItemStack st = player.getInventory().getItem(i);
            if (st != null && !st.isEmpty() && st.getItem() == item) return i;
        }
        return -1;
    }

    /** Wie findeSlot, aber nur die Hotbar (0..8). */
    private static int findeHotbar(LocalPlayer player, net.minecraft.world.item.Item item) {
        for (int i = 0; i < 9; i++) {
            ItemStack st = player.getInventory().getItem(i);
            if (st != null && !st.isEmpty() && st.getItem() == item) return i;
        }
        return -1;
    }

    /**
     * Sucht Essbares.
     *
     * Bewusst eine feste Liste statt einer Eigenschaftsabfrage: die
     * Nahrungs-Komponente ist zwischen den Versionen mehrfach umgezogen,
     * eine Liste haelt.
     */
    private static final List<net.minecraft.world.item.Item> ESSEN = new ArrayList<>();
    static {
        ESSEN.add(Items.COOKED_BEEF);
        ESSEN.add(Items.COOKED_PORKCHOP);
        ESSEN.add(Items.GOLDEN_CARROT);
        ESSEN.add(Items.COOKED_MUTTON);
        ESSEN.add(Items.COOKED_CHICKEN);
        ESSEN.add(Items.BREAD);
        ESSEN.add(Items.GOLDEN_APPLE);
    }

    private static void sendeBefehl(Minecraft mc, String befehl) {
        try {
            if (mc.getConnection() != null) mc.getConnection().sendCommand(befehl);
        } catch (Throwable pvpErr) {
            com.vortex.client.core.Errors.report("NetheriteFarmer.befehl", pvpErr);
        }
    }

    private static void melde(Minecraft mc, String text) {
        try {
            if (mc.player != null) {
                mc.player.sendSystemMessage(
                        net.minecraft.network.chat.Component.literal("[Bot] " + text));
            }
        } catch (Throwable ignored) { }
    }

    /**
     * Der naechste eingesammelte Brocken in Laufweite.
     *
     * Nur Netherit-Bruchstuecke und Ancient Debris -- der Bot soll nicht
     * jedem Kies hinterherlaufen, der beim Graben herunterfaellt.
     */
    private static net.minecraft.world.entity.item.ItemEntity nahesterBrocken(
            Minecraft mc, LocalPlayer player) {
        try {
            net.minecraft.world.entity.item.ItemEntity beste = null;
            double besteD = 36.0;   // 6 Bloecke -- weiter zu laufen lohnt nicht
            for (net.minecraft.world.entity.item.ItemEntity e
                    : com.vortex.client.core.EntityCache.items()) {
                ItemStack st = e.getItem();
                if (st == null || st.isEmpty()) continue;
                if (st.getItem() != Items.ANCIENT_DEBRIS
                        && st.getItem() != Items.NETHERITE_SCRAP) continue;
                if (BROCKEN_GESPERRT.contains(e.getUUID())) continue;
                double d = e.distanceToSqr(player);
                if (d < besteD) { besteD = d; beste = e; }
            }
            return beste;
        } catch (Throwable pvpErr) {
            return null;
        }
    }

    /**
     * Der erste feste Block auf der Geraden zum Ziel.
     *
     * Damit raeumt der Bot den Weg frei, statt gegen Stein zu laufen. Nur
     * die Bloecke auf Fuss- und Kopfhoehe zaehlen -- alles andere steht
     * nicht im Weg.
     */
    private static BlockPos naechsterBlockRichtung(Minecraft mc, LocalPlayer player,
                                                   BlockPos ziel) {
        double px = player.getX(), py = player.getY(), pz = player.getZ();
        double dx = ziel.getX() + 0.5 - px;
        double dy = ziel.getY() + 0.5 - py;
        double dz = ziel.getZ() + 0.5 - pz;
        double laenge = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (laenge < 0.001) return null;
        dx /= laenge; dy /= laenge; dz /= laenge;

        // In halben Bloecken vortasten, hoechstens vier Bloecke weit --
        // weiter reicht der Arm ohnehin nicht.
        for (double t = 0.5; t <= 4.0; t += 0.5) {
            int bx = (int) Math.floor(px + dx * t);
            int by = (int) Math.floor(py + dy * t);
            int bz = (int) Math.floor(pz + dz * t);
            // Bis ZWEI Bloecke ueber der Linie pruefen, nicht nur einen.
            //
            // Liegt das Debris ueber dem Bot -- etwa an der Decke -- steckt
            // der Block im Weg auch ueber Kopfhoehe. Frueher wurde der nie
            // gefunden, der Bot lief dagegen und sprang endlos.
            for (int h = 0; h <= 2; h++) {
                BlockPos p = new BlockPos(bx, by + h, bz);
                if (p.getX() == ziel.getX() && p.getY() == ziel.getY()
                        && p.getZ() == ziel.getZ()) continue;
                if (fest(mc, p)) return p;
            }
        }
        return null;
    }

    /** Wie blickeAuf, aber auf einen freien Punkt statt auf einen Block. */
    private static void blickeAufPunkt(LocalPlayer player, double x, double y, double z) {
        double dx = x - player.getX();
        double dy = y - player.getEyePosition().y;
        double dz = z - player.getZ();
        double flach = Math.sqrt(dx * dx + dz * dz);
        willBlicken((float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0),
                    (float) -Math.toDegrees(Math.atan2(dy, flach)));
    }


    // --- Blickfuehrung ----------------------------------------------------
    //
    // ALLE Blickaenderungen laufen ueber willBlicken. Frueher setzte jede
    // Stelle den Blick selbst -- in grabeRichtung gleich zweimal im selben
    // Tick, erst die Grabrichtung, dann der Zielblock. Das Ergebnis war ein
    // Kopf, der sich im Kreis drehte.
    //
    // Jetzt wird nur der WUNSCH gemerkt; einmal am Ende des Ticks bewegt
    // sich der Blick um hoechstens turnSpeed Grad darauf zu.

    private static float wunschYaw = 0f;
    private static float wunschPitch = 0f;
    private static boolean wunschGesetzt = false;

    private static void willBlicken(float yaw, float pitch) {
        wunschYaw = yaw;
        wunschPitch = pitch;
        wunschGesetzt = true;
    }

    /** Bewegt den Blick auf den Wunschwert zu. Einmal je Tick. */
    private static void wendeBlick(LocalPlayer player, NetheriteFarmerModule mod) {
        if (!wunschGesetzt) return;
        wunschGesetzt = false;

        float max = (float) mod.turnSpeed.get();
        drehTempo = max <= 0f ? 14f : max;
        if (schnellDrehen && max > 0f) max = Math.max(max, 30f);
        schnellDrehen = false;
        if (max <= 0f) {                     // 0 = sofort, wie frueher
            player.setYRot(wunschYaw);
            player.setXRot(wunschPitch);
            return;
        }

        // Kuerzesten Weg nehmen: ohne diese Normierung dreht der Bot bei
        // einem Sprung von 170 auf -170 Grad einmal komplett herum.
        float dYaw = ((wunschYaw - player.getYRot()) % 360f + 540f) % 360f - 180f;
        float dPitch = wunschPitch - player.getXRot();

        player.setYRot(player.getYRot() + Math.max(-max, Math.min(max, dYaw)));
        player.setXRot(player.getXRot() + Math.max(-max, Math.min(max, dPitch)));
    }


    /**
     * Sind wir im Nether?
     *
     * Ueber die Kennung der Dimension statt ueber einen festen Schluessel:
     * WaypointRenderer macht es genauso, also ist der Weg im Projekt belegt.
     */
    private static boolean imNether(Minecraft mc) {
        try {
            return mc.level.dimension().identifier().toString().contains("the_nether");
        } catch (Throwable pvpErr) {
            return false;
        }
    }


    // --- Feststecken ------------------------------------------------------
    //
    // Faellt Kies von oben auf den Bot, steht er im Block fest. Er graebt
    // dann ins Leere, kommt nicht voran -- und erstickt irgendwann. Ohne
    // diese Pruefung merkt das niemand.

    private static double letzteX, letzteY, letzteZ;
    private static int stehtSeit = 0;
    private static int letzteDrehung = -100;
    private static int drehVersuche = 0;
    private static int drinSeit = 0;    // seit wann IN der Lava
    private static int nahSeit = 0;     // seit wann Lava in der Naehe
    private static int letzteLavaMeldung = -1000;
    private static int lavaRuheBis = 0;

    private static void pruefeFeststecken(Minecraft mc, LocalPlayer player) {
        double dx = player.getX() - letzteX;
        double dy = player.getY() - letzteY;
        double dz = player.getZ() - letzteZ;
        double bewegt = dx * dx + dy * dy + dz * dz;
        letzteX = player.getX(); letzteY = player.getY(); letzteZ = player.getZ();

        // Nur beim Graben und Gehen zaehlen -- beim Essen steht er zu Recht.
        // Beim Hochbauen auch nicht: da springt er auf der Stelle und dreht
        // den Kopf, das ist kein Feststecken. Die Feststeck-Erkennung sprang
        // dort dazwischen (Springen, Freigraben) und brachte alles
        // durcheinander. Das Hochbauen hat seine eigene Zeitgrenze.
        if (bautGerade || (zustand != Zustand.GRAEBT && zustand != Zustand.GEHT)) {
            stehtSeit = 0;
            return;
        }
        if (bewegt > 0.0004) {          // rund 2 cm je Tick
            stehtSeit = 0;
            // Eine halbe Minute ohne Haenger: die Erholungen zaehlen neu.
            if (erholungen > 0 && tick - letzteErholung > 600) erholungen = 0;
            return;
        }
        if (tick - absichtlichWarten <= 2) { stehtSeit = 0; return; }

        // ABBAUEN IST FORTSCHRITT, auch wenn er sich dabei nicht bewegt.
        //
        // Das war die Endlosschleife: beim Graben steht er still, die
        // Feststeck-Erkennung sprang an und drueckte die Sprungtaste -- ein
        // Sprung bricht den Schlag ab, der Block faengt von vorne an, er
        // bewegt sich wieder nicht, und so weiter. Besonders bei Kies, der
        // ohnehin staendig nachrutscht.
        //
        // ABBAUEN IST IMMER ARBEIT -- egal welches Verhalten es ausloest.
        //
        // HIER LAG DER FEHLER: die Bedingung verlangte zusaetzlich
        // schlaegtAuf != null. Das setzt aber nur der Stollenbau. Beim
        // Debris-Abbau, beim Freigraben und beim Kies blieb es leer.
        //
        // Folge: der Bot stand beim Abbauen still, galt als festsitzend,
        // sprang los -- und der Sprung brach den Schlag ab. Er lief an
        // Ancient Debris vorbei, kam nicht durch Kies und sprang dauernd.
        // Genau die drei Dinge, die zuletzt wieder auftraten.
        //
        // Die Zeitgrenze bleibt getrennt davon: schlagen() erkennt weiter,
        // wenn ein Block gar nicht bricht.
        if (mc.options.keyAttack.isDown()) {
            stehtSeit = 0;
            return;
        }
        // Dreht er noch den Kopf zum naechsten Block, steht er zu Recht --
        // sonst sprang er mitten in der Drehung los.
        if (blickRest(player) > 14f) {
            stehtSeit = 0;
            return;
        }

        stehtSeit++;


        // Nach einer halben Sekunde: springen. Loest Stufen und einen Block
        // vor den Fuessen.
        //
        // Nur wenn ueber ihm Platz ist -- sonst springt er gegen die Decke
        // und kommt nie weiter, waehrend der Sprung jeden Abbau abbricht.
        if (stehtSeit == 4) {
            int px = (int) Math.floor(player.getX());
            int py = (int) Math.floor(player.getY());
            int pz = (int) Math.floor(player.getZ());
            if (!fest(mc, new BlockPos(px, py + 2, pz))) {
                mc.options.keyJump.setDown(true);
            }
            return;
        }
        if (stehtSeit == 8) {
            mc.options.keyJump.setDown(false);
            return;
        }


        // Nach vier Sekunden: Richtung wechseln. Manchmal ist der Weg
        // schlicht versperrt und ein anderer Stollen ist die Loesung.
        if (stehtSeit == 80 && richtung != null) {
            richtung = neueRichtung(mc, richtung);
            melde(mc, "Komme nicht weiter -- neue Richtung.");
        }

        // Nach zehn Sekunden ohne Bewegung: ERHOLUNG statt Abschalten.
        //
        // Fuer stundenlangen Betrieb ist ein Bot, der sich beendet, wertlos.
        // Deshalb wird der ganze Zustand zurueckgesetzt: neues Ziel, neue
        // Richtung, eine Ebene tiefer. Das loest praktisch jede Sackgasse.
        //
        // Erst wenn das mehrfach hintereinander nichts bringt, ist etwas
        // grundlegend falsch -- dann meldet er sich ab, ohne sich
        // abzuschalten.
        if (stehtSeit > 200) {
            stehtSeit = 0;
            erholungen++;
            letzteErholung = tick;
            ziel = null;
            schlaegtAuf = null;
            fluchtWeg = null;
            if (richtung != null) richtung = new int[]{richtung[1], -richtung[0]};
            ebenenWechsel = tick;
            melde(mc, "Haenge fest -- setze mich neu auf (" + erholungen + ").");
            if (erholungen >= 5) {
                erholungen = 0;
                melde(mc, "Komme hier nicht weiter -- /afk.");
                if (tick - letztesAfk > 1200) { letztesAfk = tick; sendeBefehl(mc, "afk"); }
            }
        }
    }


    /**
     * Sucht fallendes Material (Kies, Sand) ueber der Grabstelle.
     *
     * Bis zu vier Bloecke hoch: hoehere Saeulen kommen vor, und wer nur den
     * untersten wegnimmt, bekommt sofort den naechsten auf den Kopf.
     * Zurueckgegeben wird der UNTERSTE -- der liegt in Reichweite.
     */
    private static BlockPos fallendesUeber(Minecraft mc, int x, int y, int z) {
        for (int h = 1; h <= 4; h++) {
            BlockPos p = new BlockPos(x, y + h, z);
            var b = mc.level.getBlockState(p).getBlock();
            if (b == Blocks.GRAVEL || b == Blocks.SAND || b == Blocks.RED_SAND) {
                // Den untersten der Saeule nehmen.
                for (int k = h; k >= 1; k--) {
                    BlockPos q = new BlockPos(x, y + k, z);
                    var bb = mc.level.getBlockState(q).getBlock();
                    if (bb == Blocks.GRAVEL || bb == Blocks.SAND || bb == Blocks.RED_SAND) {
                        return q;
                    }
                }
            }
        }
        return null;
    }

    /**
     * Steckt der Bot selbst in fallendem Material?
     *
     * Anders als beim allgemeinen Feststecken laesst sich das gezielt
     * beheben: den Block auf Kopfhoehe abbauen, dann ist der Weg nach oben
     * frei und man kann heraus.
     */
    private static boolean imKiesStecken(Minecraft mc, LocalPlayer player) {
        int x = (int) Math.floor(player.getX());
        int y = (int) Math.floor(player.getY());
        int z = (int) Math.floor(player.getZ());
        for (int h = 0; h <= 1; h++) {
            var b = mc.level.getBlockState(new BlockPos(x, y + h, z)).getBlock();
            if (b == Blocks.GRAVEL || b == Blocks.SAND || b == Blocks.RED_SAND) return true;
        }
        return false;
    }


    // --- Abbau-Zeitgrenze -------------------------------------------------
    //
    // Manchmal bricht ein Block nicht: falsche Blickrichtung, ausser
    // Reichweite, oder der Server laesst es nicht zu. Der Bot schlug dann
    // ewig weiter und stand still -- ohne dass die Feststeck-Erkennung
    // ansprang, denn er "arbeitete" ja.
    //
    // Deshalb: wird derselbe Block zu lange geschlagen, gilt er als
    // unbrechbar. Richtung wechseln und weiter.

    private static BlockPos schlaegtAuf = null;
    private static int schlaegtSeit = 0;
    private static int zielSeit = 0;
    /** Frueheste naechste Debris-Suche, wenn die letzte nichts fand. */
    private static int naechsteSuche = 0;
    private static double zielBesteDistanz = Double.MAX_VALUE;

    /**
     * Meldet, dass gerade auf diesen Block geschlagen wird.
     *
     * @return false, wenn zu lange erfolglos -- dann aufgeben.
     */
    private static boolean schlagen(Minecraft mc, BlockPos p) {
        if (schlaegtAuf == null || !schlaegtAuf.equals(p)) {
            schlaegtAuf = p;
            schlaegtSeit = tick;
            return true;
        }
        // 8 Sekunden. Netherrack braucht Bruchteile davon, Ancient Debris
        // mit Netherit-Spitzhacke rund eine Sekunde. Wer so lange braucht,
        // kommt nicht durch.
        if (tick - schlaegtSeit > 160) {
            schlaegtAuf = null;
            return false;
        }
        return true;
    }

    private static void schlagenZuruecksetzen() {
        schlaegtAuf = null;
    }


    // ======================================================================
    // Gefahrenerkennung
    // ======================================================================
    //
    // FRUEHER wurde Lava nur VOR dem naechsten Stollenabschnitt geprueft.
    // Fliessende Lava kommt aber von der Seite oder von hinten -- der Bot
    // grub seelenruhig weiter, waehrend sie auf ihn zulief, und merkte es
    // erst, als sie ihn beruehrte.
    //
    // Jetzt wird JEDEN Tick die ganze Umgebung geprueft, und zwar ueber den
    // Fluessigkeitszustand: Blocks.LAVA erfasst nur den Block-Typ, aber
    // fliessende Lava hat denselben Typ mit anderem Stand. getFluidState
    // erfasst beides zuverlaessig -- so macht es auch der TunnelDetector.

    /** Ist dort Lava -- Quelle oder fliessend? */
    private static boolean istLava(Minecraft mc, BlockPos p) {
        try {
            var fs = mc.level.getFluidState(p);
            if (fs.isEmpty()) return false;
            return fs.is(net.minecraft.tags.FluidTags.LAVA);
        } catch (Throwable pvpErr) {
            return mc.level.getBlockState(p).getBlock() == Blocks.LAVA;
        }
    }

    /**
     * Naechster Lavablock um den Spieler, oder null.
     *
     * Radius bewusst grosszuegig: fliessende Lava legt einen Block je halbe
     * Sekunde zurueck. Wer erst bei einem Block Abstand reagiert, hat keine
     * Zeit mehr zu graben oder auszuweichen.
     */
    private static BlockPos lavaInDerNaehe(Minecraft mc, LocalPlayer player, int r) {
        int px = (int) Math.floor(player.getX());
        int py = (int) Math.floor(player.getY());
        int pz = (int) Math.floor(player.getZ());
        BlockPos naechste = null;
        double besteD = Double.MAX_VALUE;
        for (int dx = -r; dx <= r; dx++) {
            for (int dy = -1; dy <= 2; dy++) {
                for (int dz = -r; dz <= r; dz++) {
                    BlockPos q = new BlockPos(px + dx, py + dy, pz + dz);
                    if (!istLava(mc, q)) continue;
                    double d = dx * dx + dy * dy + dz * dz;
                    if (d < besteD) { besteD = d; naechste = q; }
                }
            }
        }
        return naechste;
    }

    /**
     * Sucht die beste Fluchtrichtung WEG von einem Punkt.
     *
     * Prueft alle vier Himmelsrichtungen und bewertet sie: frei ist besser
     * als zugebaut, weiter weg von der Lava ist besser als naeher dran.
     *
     * DAS war der zweite Fehler: "rueckwaerts" half nicht, wenn dort eine
     * Wand stand. Jetzt wird tatsaechlich geschaut, wo Platz ist.
     */
    private static int[] fluchtRichtung(Minecraft mc, LocalPlayer player, BlockPos weg) {
        int px = (int) Math.floor(player.getX());
        int py = (int) Math.floor(player.getY());
        int pz = (int) Math.floor(player.getZ());
        int[][] kandidaten = {{0, 1}, {0, -1}, {1, 0}, {-1, 0}};
        int[] beste = null;
        double bestePunkte = -1e9;
        for (int[] k : kandidaten) {
            double punkte = 0;
            // Wie viel weiter weg von der Lava?
            double vorher = abstand(px, pz, weg);
            double nachher = abstand(px + k[0] * 2, pz + k[1] * 2, weg);
            punkte += (nachher - vorher) * 10;
            // Ist der Weg frei? Zwei Bloecke hoch, zwei Schritte weit.
            for (int schritt = 1; schritt <= 2; schritt++) {
                for (int h = 0; h <= 1; h++) {
                    BlockPos q = new BlockPos(px + k[0] * schritt, py + h, pz + k[1] * schritt);
                    if (istLava(mc, q)) punkte -= 100;          // niemals dorthin
                    else if (fest(mc, q)) punkte -= 3;          // muss gegraben werden
                    else punkte += 2;                            // frei
                }
            }
            if (punkte > bestePunkte) { bestePunkte = punkte; beste = k; }
        }
        return beste;
    }

    private static double abstand(int x, int z, BlockPos p) {
        double dx = x - p.getX(), dz = z - p.getZ();
        return Math.sqrt(dx * dx + dz * dz);
    }

    /**
     * Bringt den Bot aus dem Gefahrenbereich.
     *
     * Ist der Fluchtweg zugebaut, wird er FREIGEGRABEN statt dagegenzulaufen
     * -- der dritte Fehler: der Bot lief rueckwaerts gegen eine Wand und kam
     * nie an.
     *
     * @return true, solange er fluechtet
     */
    private static int[] fluchtWeg = null;
    private static int fluchtBis = 0;

    private static boolean fliehen(Minecraft mc, LocalPlayer player, BlockPos lava) {
        return fliehen(mc, player, lava, true);
    }

    private static boolean fliehen(Minecraft mc, LocalPlayer player, BlockPos lava,
                                   boolean darfGraben) {
        // Einmal gewaehlte Fluchtrichtung eine Weile BEIBEHALTEN.
        //
        // Vorher wurde sie jeden Tick neu berechnet. Stehen zwei Richtungen
        // etwa gleich gut da, wechselte sie staendig -- und der Bot drehte
        // sich auf der Stelle im Kreis, statt wegzukommen.
        if (fluchtWeg == null || tick > fluchtBis) {
            fluchtWeg = fluchtRichtung(mc, player, lava);
            fluchtBis = tick + 30;          // anderthalb Sekunden durchhalten
        }
        int[] weg = fluchtWeg;
        if (weg == null) return false;

        int px = (int) Math.floor(player.getX());
        int py = (int) Math.floor(player.getY());
        int pz = (int) Math.floor(player.getZ());
        BlockPos fuss = new BlockPos(px + weg[0], py, pz + weg[1]);
        BlockPos kopf = new BlockPos(px + weg[0], py + 1, pz + weg[1]);

        // Blick in die Fluchtrichtung, damit "vorwaerts" auch dorthin zeigt.
        willBlicken(richtungZuYaw(weg), 0f);

        if ((fest(mc, kopf) || fest(mc, fuss)) && darfGraben) {
            // Weg zu: freigraben. NICHT laufen, sonst drueckt er nur gegen
            // den Block und der Abbau bricht ab.
            BlockPos z = fest(mc, kopf) ? kopf : fuss;
            if (!player.isInLava() && !darfAbbauen(mc, z)) {
                // Dahinter Lava: diese Richtung taugt nicht, neu waehlen.
                fluchtBis = tick;
                mc.options.keyAttack.setDown(false);
                mc.options.keyUp.setDown(false);
                return true;
            }
            blickeAuf(player, z);
            waehleSpitzhacke(player);
            mc.options.keyAttack.setDown(player.isInLava() || blickFertig(player));
            mc.options.keyUp.setDown(false);
        } else {
            mc.options.keyAttack.setDown(false);
            mc.options.keyUp.setDown(true);
        }
        return true;
    }


    // ======================================================================
    // Nachfuellen: Inventar -> Hotbar / Off-Hand
    // ======================================================================
    //
    // Der Bot kann nur benutzen, was in der Hotbar liegt. Ging dort etwas
    // aus, meldete er "kein Essen mehr" -- obwohl das Inventar voll war.
    // Ebenso das Totem: poppte es, blieb die Off-Hand leer.
    //
    // Umgelagert wird ueber denselben Weg wie in AutoTotem: Gegenstand auf
    // den Cursor nehmen, auf den Zielplatz legen, und falls dort schon etwas
    // lag, zurueck auf den Ursprungsplatz.

    private static final int OFFHAND_SLOT = 45;
    private static int letzteUmlagerung = -100;

    /** Inventar-Index in die Platznummer des Inventarfensters umrechnen. */
    private static int indexZuFensterPlatz(int index) {
        if (index >= 0 && index <= 8) return 36 + index;    // Hotbar
        if (index >= 9 && index <= 35) return index;        // Hauptinventar
        return -1;
    }

    /** Legt den Gegenstand von quelle auf ziel. */
    private static void lagereUm(Minecraft mc, LocalPlayer player, int quellIndex, int zielPlatz) {
        try {
            int syncId = player.inventoryMenu.containerId;
            int quellPlatz = indexZuFensterPlatz(quellIndex);
            if (quellPlatz < 0) return;
            // ContainerInput, nicht ClickType -- so heisst es in dieser
            // Fassung, belegt in AutoTotem.
            var art = net.minecraft.world.inventory.ContainerInput.PICKUP;
            mc.gameMode.handleContainerInput(syncId, quellPlatz, 0, art, player);
            mc.gameMode.handleContainerInput(syncId, zielPlatz, 0, art, player);
            if (!player.inventoryMenu.getCarried().isEmpty()) {
                mc.gameMode.handleContainerInput(syncId, quellPlatz, 0, art, player);
            }
        } catch (Throwable pvpErr) {
            com.vortex.client.core.Errors.report("NetheriteFarmer.lagereUm", pvpErr);
        }
    }

    /** Ein freier oder entbehrlicher Hotbar-Platz. */
    private static int freierHotbarPlatz(LocalPlayer player) {
        for (int i = 0; i < 9; i++) {
            ItemStack st = player.getInventory().getItem(i);
            if (st == null || st.isEmpty()) return i;
        }
        // Keiner frei: zuerst einen Platz mit etwas Unwichtigem (Netherrack,
        // Bloecke ...). Vorher konnte hier das Essen die XP-Flaschen
        // verdraengen und im naechsten Moment umgekehrt -- ein Pingpong.
        for (int i = 8; i >= 0; i--) {
            ItemStack st = player.getInventory().getItem(i);
            if (st == null) continue;
            var it = st.getItem();
            if (it == Items.NETHERITE_PICKAXE || it == Items.DIAMOND_PICKAXE) continue;
            if (it == Items.TOTEM_OF_UNDYING || it == Items.EXPERIENCE_BOTTLE) continue;
            if (it == Items.ENCHANTED_GOLDEN_APPLE || ESSEN.contains(it)) continue;
            if (it == Items.POTION) continue;
            return i;
        }
        // Notfalls den letzten nehmen, aber niemals den mit der Spitzhacke
        // -- ohne sie steht der Bot still.
        for (int i = 8; i >= 0; i--) {
            ItemStack st = player.getInventory().getItem(i);
            if (st == null) continue;
            var it = st.getItem();
            if (it == Items.NETHERITE_PICKAXE || it == Items.DIAMOND_PICKAXE) continue;
            if (it == Items.TOTEM_OF_UNDYING) continue;
            return i;
        }
        return -1;
    }

    /**
     * Fuellt Hotbar und Off-Hand aus dem Inventar auf.
     *
     * @return true, wenn umgelagert wurde -- dann diesen Tick nichts anderes
     *         tun, denn der Inventarvorgang braucht einen Moment.
     */
    private static boolean nachfuellen(Minecraft mc, LocalPlayer player,
                                       NetheriteFarmerModule mod) {
        // Hoechstens alle 10 Ticks: Inventarklicks zu schnell hintereinander
        // verschluckt der Server.
        if (tick - letzteUmlagerung < 10) return false;

        // Das Totem wird bereits ganz oben im Tick nachgelegt.

        // 1) ESSEN ZUERST: wird gerade gegessen werden muessen, kommt genau
        //    das in die Hotbar -- vor allem anderen.
        if (essenHolen != null) {
            int quelle = -1;
            switch (essenHolen) {
                case "feuer": quelle = findeSlot(player, Items.ENCHANTED_GOLDEN_APPLE); break;
                case "trank": quelle = feuerTrank(player, false); break;
                case "apfel":
                    quelle = findeSlot(player, Items.GOLDEN_APPLE);
                    if (quelle <= 8) quelle = findeSlot(player, Items.ENCHANTED_GOLDEN_APPLE);
                    break;
                case "regen": quelle = normalesEssen(player, false); break;
                default: quelle = hungerEssen(player, false); break;
            }
            int platz = freierHotbarPlatz(player);
            if (quelle > 8 && platz >= 0) {
                lagereUm(mc, player, quelle, indexZuFensterPlatz(platz));
                letzteUmlagerung = tick;
                return true;
            }
        }

        // 1b) Beste Spitzhacke in die Hotbar. Vorher suchte der Bot nur in der
        //     Hotbar -- zerbrach die Hacke dort, grub er mit der blossen Hand
        //     weiter, obwohl im Inventar eine zweite lag.
        int hackeInv = besteSpitzhacke(player, false);
        int hackeBar = besteSpitzhacke(player, true);
        if (hackeInv > 8 && (hackeBar < 0 || besserAls(player, hackeInv, hackeBar))) {
            int platz = hackeBar >= 0 ? hackeBar : freierHotbarPlatz(player);
            if (platz >= 0) {
                lagereUm(mc, player, hackeInv, indexZuFensterPlatz(platz));
                letzteUmlagerung = tick;
                return true;
            }
        }

        // 2) Verbrauchsgueter in die Hotbar holen, wenn dort keine mehr sind.
        // Reihenfolge: erst was zum Ueberleben gebraucht wird (Aepfel), dann
        // die Flaschen. Normales Essen folgt unten.
        net.minecraft.world.item.Item[] wichtig = {
            Items.GOLDEN_APPLE, Items.ENCHANTED_GOLDEN_APPLE, Items.EXPERIENCE_BOTTLE
        };
        for (net.minecraft.world.item.Item item : wichtig) {
            if (findeHotbar(player, item) >= 0) continue;     // schon da
            int quelle = findeSlot(player, item);
            if (quelle < 0 || quelle <= 8) continue;          // nicht im Inventar
            int platz = freierHotbarPlatz(player);
            if (platz < 0) continue;
            lagereUm(mc, player, quelle, indexZuFensterPlatz(platz));
            letzteUmlagerung = tick;
            return true;
        }

        // 3) Normales Essen -- das beste aus der Liste.
        if (normalesEssen(player, true) < 0) {
            for (net.minecraft.world.item.Item item : ESSEN) {
                if (item == Items.GOLDEN_APPLE) continue;
                int quelle = findeSlot(player, item);
                if (quelle < 0 || quelle <= 8) continue;
                int platz = freierHotbarPlatz(player);
                if (platz < 0) break;
                lagereUm(mc, player, quelle, indexZuFensterPlatz(platz));
                letzteUmlagerung = tick;
                return true;
            }
        }
        return false;
    }



    /**
     * Wie lavaInDerNaehe, aber nur Lava, die den Bot auch ERREICHEN kann.
     *
     * Das war der zweite grosse Fehler: Lava hinter einer Steinwand loeste
     * dauernd "Lava kommt naeher" aus. Der Bot blieb in Dauerflucht, graebt
     * nichts mehr und lief nur noch vorwaerts -- obwohl ihm nichts passieren
     * konnte.
     *
     * Geprueft wird die Sichtlinie: liegt zwischen Bot und Lava ein fester
     * Block, kommt sie nicht durch und wird ignoriert. Das ist grob, aber es
     * trifft genau den Fall, der gestoert hat.
     */
    private static BlockPos erreichbareLava(Minecraft mc, LocalPlayer player, int r) {
        int px = (int) Math.floor(player.getX());
        int py = (int) Math.floor(player.getY());
        int pz = (int) Math.floor(player.getZ());
        BlockPos beste = null;
        double besteD = Double.MAX_VALUE;
        for (int dx = -r; dx <= r; dx++) {
            for (int dy = -1; dy <= 2; dy++) {
                for (int dz = -r; dz <= r; dz++) {
                    BlockPos q = new BlockPos(px + dx, py + dy, pz + dz);
                    if (!istLava(mc, q)) continue;

                    // Lava UNTER dem Boden oder UEBER der Decke ist harmlos,
                    // solange etwas Festes dazwischen liegt.
                    //
                    // Genau das liess den Bot im Kreis drehen: ein Lavasee
                    // unter dem Stollen loeste dauernd Flucht aus, obwohl
                    // ein Block Boden dazwischen lag. Er meldete endlos
                    // "Lava kommt naeher" und drehte sich.
                    if (dy < 0 && fest(mc, new BlockPos(px + dx, py, pz + dz))) continue;
                    if (dy > 1 && fest(mc, new BlockPos(px + dx, py + 2, pz + dz))) continue;

                    double d = dx * dx + dy * dy + dz * dz;
                    if (d >= besteD) continue;
                    if (!freieSicht(mc, px, py, pz, q)) continue;   // Wand dazwischen
                    besteD = d;
                    beste = q;
                }
            }
        }
        return beste;
    }

    /** Liegt zwischen zwei Punkten ein fester Block? */
    private static boolean freieSicht(Minecraft mc, int px, int py, int pz, BlockPos ziel) {
        double dx = ziel.getX() - px, dy = ziel.getY() - py, dz = ziel.getZ() - pz;
        double laenge = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (laenge < 1.5) return true;               // direkt daneben
        dx /= laenge; dy /= laenge; dz /= laenge;
        for (double t = 1.0; t < laenge - 0.5; t += 0.5) {
            BlockPos q = new BlockPos((int) Math.floor(px + dx * t),
                                      (int) Math.floor(py + dy * t),
                                      (int) Math.floor(pz + dz * t));
            if (istLava(mc, q)) continue;            // Lava selbst blockiert nicht
            if (fest(mc, q)) return false;           // feste Wand dazwischen
        }
        return true;
    }


    /** Wie viele Grad fehlen noch bis zur gewuenschten Blickrichtung? */
    private static float restWinkel(LocalPlayer player) {
        if (!wunschGesetzt) return 0f;
        return ((wunschYaw - player.getYRot()) % 360f + 540f) % 360f - 180f;
    }

    /** Groesste verbleibende Abweichung (Drehung oder Neigung) in Grad. */
    private static float blickRest(LocalPlayer player) {
        if (!wunschGesetzt) return 0f;
        return Math.max(Math.abs(restWinkel(player)), Math.abs(wunschPitch - player.getXRot()));
    }

    /**
     * Sieht er schon (fast) dorthin, wo er hin will?
     *
     * Erst DANN wird zugeschlagen. Vorher schlug er schon waehrend der
     * Drehung -- und baute dabei die Bloecke ab, ueber die der Blick gerade
     * hinwegstrich: Waende, Decke, den Boden unter sich.
     *
     * Der Blick bewegt sich erst am Ende des Ticks; deshalb zaehlt, ob er ihn
     * mit der naechsten Drehung erreicht (plus etwas Spielraum).
     */
    private static boolean blickFertig(LocalPlayer player) {
        // Bei langsamem Drehen nicht schon 14 Grad daneben zuschlagen --
        // sonst trifft er Waende/Decke, ueber die der Blick gerade streicht.
        return blickRest(player) <= Math.min(14f, drehTempo + 2f);
    }
    private static float drehTempo = 12f;


    // ======================================================================
    // Schutz gegen Hin-und-Her
    // ======================================================================
    //
    // Stiess der Bot am Ende des Stollens auf Lava, drehte er um -- und lief
    // denselben Gang zurueck. Am anderen Ende wieder Lava, wieder umdrehen.
    // Eine Schleife, die nie endet und keinen Meter neuen Stollen bringt.
    //
    // Die Loesung: er merkt sich, in welche Richtungen er zuletzt gedreht
    // hat. Wechselt er zu haeufig, geht er QUER -- also senkrecht zur
    // bisherigen Achse -- oder eine Ebene tiefer.

    private static int drehungenZuletzt = 0;
    private static int letzteErholung = 0;
    /** Wartet der Bot gerade mit Absicht (Lava-Pause, Abgrund, fallender Kies)? */
    private static int absichtlichWarten = -100;
    private static int drehFensterAb = 0;
    private static int ebenenWechsel = 0;
    private static int erholungen = 0;

    /**
     * Meldet eine Richtungsaenderung und liefert die neue Richtung.
     *
     * Bei den ersten Wechseln wird einfach gedreht. Haeufen sie sich, wird
     * quer ausgewichen: das bricht das Pendeln zwischen zwei Enden auf.
     */
    private static int[] neueRichtung(Minecraft mc, int[] alt) {
        if (tick - drehFensterAb > 600) {      // halbe Minute ohne Wechsel
            drehungenZuletzt = 0;
            drehFensterAb = tick;
        }
        drehungenZuletzt++;

        if (drehungenZuletzt == 1) {
            return drehe(alt);                  // rechts
        }
        if (drehungenZuletzt == 2) {
            // Links (von der urspruenglichen Richtung aus) -- vorher drehte er
            // ein zweites Mal rechts und stand damit mit dem Ruecken zur
            // Ausgangsrichtung, also im eigenen alten Stollen.
            return new int[]{-alt[0], -alt[1]};
        }

        // Zu oft gewechselt: quer zur bisherigen Achse ausbrechen und
        // ausserdem die Ebene wechseln, damit er nicht wieder im selben
        // Gang landet.
        drehungenZuletzt = 0;
        drehFensterAb = tick;
        ebenenWechsel = tick;
        melde(mc, "Pendle zwischen Hindernissen -- breche quer aus.");
        // Aus Nord/Sued wird Ost/West und umgekehrt; zusaetzlich gespiegelt,
        // damit er nicht in den gerade verlassenen Ast zurueckgeht.
        return new int[]{alt[1], -alt[0]};
    }

    /**
     * Soll gerade die Ebene gewechselt werden?
     *
     * Nach einem Ausbruch graebt der Bot kurz abwaerts. Damit verlaesst er
     * den alten Stollen wirklich, statt nur die Richtung zu tauschen.
     */
    private static boolean wechseltEbene() {
        return ebenenWechsel != 0 && tick - ebenenWechsel < 100;
    }


    // ======================================================================
    // Wachhund
    // ======================================================================
    //
    // Fuer stundenlangen Betrieb reicht es nicht, einzelne Sackgassen zu
    // erkennen. Es gibt Faelle, in denen alles "funktioniert" und trotzdem
    // nichts passiert: ein Stollen, der seit zehn Minuten nur Netherrack
    // liefert, weil der Bot im Kreis gegraben hat.
    //
    // Der Wachhund misst das Einzige, was zaehlt: Kommt Ausbeute herein?
    // Wenn zwei Minuten lang nichts, wird gross umgestellt.

    private static int letzterFund = 0;
    private static int gefunden = 0;

    /** Meldet, dass Debris aufgesammelt wurde. */
    private static void fundGemeldet(Minecraft mc) {
        gefunden++;
        letzterFund = tick;
    }

    private static int felderBeiLetzterPruefung = 0;

    private static void wachhund(Minecraft mc, LocalPlayer player) {
        if (letzterFund == 0) letzterFund = tick;
        // Zwei Minuten ohne Fund.
        if (tick - letzterFund < 2400) return;
        letzterFund = tick;
        // Aber: Graebt er dabei NEUES Gestein frei, ist alles in Ordnung --
        // Debris ist selten, zwei Minuten ohne Fund sind normal. Vorher
        // zerstoerte der Wachhund dann jedes Mal das Streifenmuster.
        int neu = neueFelder - felderBeiLetzterPruefung;
        felderBeiLetzterPruefung = neueFelder;
        if (neu >= 30) return;

        // Richtung um 90 Grad kippen UND die Ebene wechseln. Beides
        // zusammen, weil eine Aenderung allein oft im selben Gebiet bleibt.
        if (richtung == null) richtung = himmelsrichtung(player.getYRot());
        richtung = new int[]{richtung[1], -richtung[0]};
        ebenenWechsel = tick;
        ziel = null;
        schlaegtAuf = null;
        melde(mc, "Lange nichts gefunden -- suche woanders weiter.");
    }


    // ======================================================================
    // Sperrliste fuer unerreichbare Ziele
    // ======================================================================
    //
    // DER KERNFEHLER aller drei Endlosschleifen: der Bot gab ein Ziel nie
    // auf. Er wich der Lava aus, drehte die Grabrichtung -- und im naechsten
    // Tick fand er dasselbe Debris wieder und lief erneut hin.
    //
    // Dasselbe bei Debris zwei Bloecke ueber ihm: er meldete "komme nicht
    // heran", verwarf das Ziel, fand es sofort wieder und stand fuer immer.
    //
    // Jetzt wird ein aufgegebenes Ziel GEMERKT und eine Weile nicht mehr
    // angefasst. Der Bot graebt stattdessen weiter -- und kommt spaeter von
    // einer anderen Seite womoeglich doch heran.

    private static final java.util.Map<Long, Integer> GESPERRT = new java.util.HashMap<>();

    private static long schluessel(BlockPos p) {
        return ((long) p.getX() & 0x3FFFFFF) << 38
             | ((long) p.getY() & 0xFFF) << 26
             | ((long) p.getZ() & 0x3FFFFFF);
    }

    /** Ziel aufgeben und fuer eine Weile sperren. */
    private static void sperre(Minecraft mc, BlockPos p, String grund) {
        if (p == null) return;
        GESPERRT.put(schluessel(p), tick);
        ziel = null;
        zielBesteDistanz = Double.MAX_VALUE;
        schlaegtAuf = null;
        if (tick - letzteSperrMeldung > 200) {
            letzteSperrMeldung = tick;
            melde(mc, grund);
        }
        // Liste klein halten: alte Eintraege nach fuenf Minuten vergessen.
        if (GESPERRT.size() > 128) {
            GESPERRT.entrySet().removeIf(e -> tick - e.getValue() > 6000);
        }
    }

    private static boolean istGesperrt(BlockPos p) {
        Integer seit = GESPERRT.get(schluessel(p));
        if (seit == null) return false;
        // Nach zwei Minuten darf er es erneut versuchen -- vielleicht steht
        // er dann guenstiger.
        if (tick - seit > 2400) {
            GESPERRT.remove(schluessel(p));
            return false;
        }
        return true;
    }

    private static int letzteSperrMeldung = -1000;


    /**
     * Der Block unmittelbar vor dem Bot in Zielrichtung.
     *
     * Die Strahlpruefung tastet die Luftlinie ab und geht an Kanten vorbei --
     * der Bot lief dagegen und stand. Hier wird stattdessen genau das
     * geprueft, wogegen er laeuft: der Block vor den Fuessen und der vor dem
     * Kopf, in der Himmelsrichtung des Ziels.
     */
    private static BlockPos blockDirektDavor(Minecraft mc, LocalPlayer player, BlockPos ziel) {
        int px = (int) Math.floor(player.getX());
        int py = (int) Math.floor(player.getY());
        int pz = (int) Math.floor(player.getZ());
        int dxz = ziel.getX() - px, dzz = ziel.getZ() - pz;
        if (dxz == 0 && dzz == 0) return null;        // genau darueber
        int rx, rz;
        if (Math.abs(dxz) >= Math.abs(dzz)) { rx = Integer.signum(dxz); rz = 0; }
        else { rx = 0; rz = Integer.signum(dzz); }

        BlockPos fuss = new BlockPos(px + rx, py, pz + rz);
        BlockPos kopf = new BlockPos(px + rx, py + 1, pz + rz);
        if (fest(mc, kopf)) return kopf;
        if (fest(mc, fuss)) return fuss;
        return null;
    }


    /**
     * Liegt Lava auf der Strecke zwischen Bot und Ziel?
     *
     * Nur auf Fuss- und Kopfhoehe der Strecke -- Lava zwei Bloecke tiefer
     * unter festem Boden ist kein Hindernis und hat den Bot frueher grundlos
     * umkehren lassen.
     */
    private static boolean lavaAufDemWeg(Minecraft mc, LocalPlayer player, BlockPos ziel) {
        double px = player.getX(), py = player.getY(), pz = player.getZ();
        double dx = ziel.getX() + 0.5 - px;
        double dy = ziel.getY() + 0.5 - py;
        double dz = ziel.getZ() + 0.5 - pz;
        double laenge = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (laenge < 0.001) return false;
        dx /= laenge; dy /= laenge; dz /= laenge;
        for (double t = 0.5; t <= laenge; t += 0.5) {
            int bx = (int) Math.floor(px + dx * t);
            int by = (int) Math.floor(py + dy * t);
            int bz = (int) Math.floor(pz + dz * t);
            for (int h = 0; h <= 1; h++) {
                if (istLava(mc, new BlockPos(bx, by + h, bz))) return true;
            }
        }
        return false;
    }


    // ======================================================================
    // Hochsaeulen
    // ======================================================================
    //
    // Liegt das Debris zwei oder mehr Bloecke ueber dem Bot, kommt er nicht
    // heran: springen reicht nur fuer einen Block, und graben geht nach oben
    // nicht weiter, weil man in den entstandenen Schacht nicht hinaufkommt.
    //
    // Frueher stand er dann einfach da. Jetzt baut er sich hoch -- Block
    // unter sich setzen, waehrend er springt. Das ist der uebliche Weg und
    // braucht nur Fuellmaterial, von dem im Nether reichlich anfaellt.

    /** Bloecke, die zum Hochbauen taugen. Netherrack faellt beim Graben an. */
    private static final net.minecraft.world.item.Item[] FUELLER = {
        Items.NETHERRACK, Items.COBBLESTONE, Items.BLACKSTONE,
        Items.BASALT, Items.DIRT, Items.STONE
    };

    private static int letzterBau = -100;

    /**
     * Laeuft gerade ein Bauvorgang?
     *
     * WICHTIG fuer die Blickfuehrung. Ohne diesen Zustand wechselte der Bot
     * jeden Tick: hochbauen heisst nach unten sehen, abbauen heisst nach
     * oben sehen. Beides abwechselnd ergab einen Bot, der nur noch den Kopf
     * auf und ab warf und nichts zustande brachte.
     *
     * Einmal begonnen, wird durchgebaut, bis das Ziel in Reichweite ist.
     */
    private static boolean bautGerade = false;

    /** Fuellmaterial in der Hotbar, oder -1. */
    private static int findeFueller(LocalPlayer player) {
        for (net.minecraft.world.item.Item item : FUELLER) {
            int slot = findeHotbar(player, item);
            if (slot >= 0) return slot;
        }
        return -1;
    }

    /**
     * Baut eine Saeule unter dem Bot, bis das Debris in Reichweite ist.
     *
     * NEU GEBAUT (2.15.0). Die alte Fassung hatte drei Fehler, die zusammen
     * das "Hoch-, Runterschauen, Abbauen, Gott weiss was" ergaben:
     *
     *  1. Die Hoehe wurde mitten im Sprung neu berechnet. Im Sprung steht der
     *     Bot einen Block hoeher -- "ueber dem Kopf" war ploetzlich der Block
     *     darueber. War der fest, sprang die Phase auf "Decke abbauen": Blick
     *     nach oben, landen, Blick nach unten, springen, wieder nach oben ...
     *     Jetzt zaehlt nur die Hoehe, auf der er STEHT (bodenY, wird nur am
     *     Boden nachgefuehrt). Im Sprung aendert sich nichts.
     *
     *  2. Er hat schon beim Drehen zugeschlagen. Auf dem Weg von "unten" nach
     *     "oben" zeigte der Blick auf die Waende -- er baute sie ab. Jetzt wird
     *     erst geschlagen, wenn er wirklich nach oben sieht, und erst
     *     gesprungen, wenn er wirklich nach unten sieht.
     *
     *  3. Nach jedem Block drehte er einmal ganz nach oben und wieder zurueck.
     *     Jetzt raeumt er beim Hochsehen bis zu DREI Bloecke frei und kann
     *     danach drei Bloecke am Stueck hochbauen.
     *
     * Ausserdem: beim Bauen dreht er schneller als eingestellt (mind. 30 Grad
     * je Tick), sonst dauert jede Kopfbewegung eine Sekunde.
     *
     * @return false, wenn es nicht geht (kein Material, Lava, kein
     *         Fortschritt) -- dann wird das Ziel aufgegeben.
     */
    private static int bauPhase = 0;      // 0 = Decke raeumen, 1 = Block setzen
    private static int bauFortschrittY = Integer.MIN_VALUE;
    private static int bauFortschrittSeit = 0;

    private static boolean baueHoch(Minecraft mc, LocalPlayer player) {
        int px = (int) Math.floor(player.getX());
        int pz = (int) Math.floor(player.getZ());
        int basis = bodenY;

        // --- Fortschritt: 10 Sekunden kein Block hoeher -> aufgeben ------
        if (basis > bauFortschrittY) {
            bauFortschrittY = basis;
            bauFortschrittSeit = tick;
        } else if (tick - bauFortschrittSeit > 200) {
            melde(mc, "Hochbauen kommt nicht voran -- lasse das Debris liegen.");
            return false;
        }

        // --- Welche Deckenbloecke muessen weg? ---------------------------
        //
        // Pflicht ist nur basis+2 (Platz zum Springen). basis+3 und +4 nimmt
        // er gleich mit, solange sie unter dem Ziel liegen -- das spart das
        // Hochsehen bei den naechsten Bloecken.
        int bis = basis + 2;
        if (ziel != null) bis = Math.max(bis, Math.min(basis + 4, ziel.getY() - 1));

        BlockPos decke = null;
        // Hysterese: in der Setz-Phase nur zurueck, wenn der PFLICHT-Block
        // zu ist. Sonst wuerde jeder neue Block darueber die Phase kippen.
        int pruefeBis = (bauPhase == 0) ? bis : basis + 2;
        for (int y = basis + 2; y <= pruefeBis; y++) {
            BlockPos q = new BlockPos(px, y, pz);
            if (!fest(mc, q)) continue;
            if (lavaDran(mc, q)) {
                // Lava hinter dem Block: den Pflichtblock nicht anfassen
                // (Lava liefe herein), einen hoeheren einfach stehen lassen.
                if (y == basis + 2) {
                    melde(mc, "Lava ueber mir -- baue nicht weiter hoch.");
                    return false;
                }
                break;
            }
            decke = q;
            break;
        }
        bauPhase = (decke != null) ? 0 : 1;

        // --- Phase 0: Decke raeumen, Blick gerade nach oben ---------------
        if (bauPhase == 0) {
            if (!schlagen(mc, decke)) return false;          // Grundgestein o. ae.
            waehleSpitzhacke(player);
            willBlicken(player.getYRot(), -90f);
            schnellDrehen = true;
            mc.options.keyUp.setDown(false);
            mc.options.keyJump.setDown(false);
            // ERST SCHLAGEN, WENN ER WIRKLICH NACH OBEN SIEHT.
            mc.options.keyAttack.setDown(player.getXRot() < -75f);
            return true;
        }
        schlagenZuruecksetzen();

        // --- Phase 1: Block unter sich setzen ------------------------------
        int slot = findeFueller(player);
        if (slot < 0) {
            // Netherrack liegt oft nur im Inventar: in die Hotbar holen.
            int quelle = -1;
            for (net.minecraft.world.item.Item item : FUELLER) {
                quelle = findeSlot(player, item);
                if (quelle > 8) break;
                quelle = -1;
            }
            if (quelle < 0) {
                melde(mc, "Kein Baumaterial (Netherrack) -- lasse das Debris liegen.");
                return false;
            }
            if (tick - letzteUmlagerung >= 10) {
                int platz = freierHotbarPlatz(player);
                if (platz < 0) return false;
                lagereUm(mc, player, quelle, indexZuFensterPlatz(platz));
                letzteUmlagerung = tick;
            }
            tastenLos(mc);
            return true;
        }
        if (player.getInventory().getSelectedSlot() != slot) {
            player.getInventory().setSelectedSlot(slot);
        }
        willBlicken(player.getYRot(), 90f);
        schnellDrehen = true;
        mc.options.keyUp.setDown(false);
        mc.options.keyAttack.setDown(false);
        // ERST SPRINGEN, WENN ER WIRKLICH NACH UNTEN SIEHT.
        boolean schautRunter = player.getXRot() > 75f;
        mc.options.keyJump.setDown(schautRunter);
        if (!schautRunter) return true;

        // Setzen genau dann, wenn die Fuesse ueber dem Platz schweben, an den
        // der neue Block gehoert (basis). Vorher verweigert Minecraft das
        // Setzen, weil der Spieler selbst dort steht.
        BlockPos platz = new BlockPos(px, basis, pz);
        if (player.getY() < basis + 1.0) return true;
        if (fest(mc, platz)) return true;                 // schon gesetzt
        if (tick - letzterBau < 3) return true;

        try {
            BlockPos unter = platz.below();
            net.minecraft.world.phys.BlockHitResult treffer;
            if (fest(mc, unter)) {
                // Selbst zusammengesetzt statt mc.hitResult: der Blick muss
                // dann nicht aufs Pixel stimmen.
                treffer = new net.minecraft.world.phys.BlockHitResult(
                        new net.minecraft.world.phys.Vec3(px + 0.5, basis, pz + 0.5),
                        net.minecraft.core.Direction.UP, unter, false);
            } else if (mc.hitResult instanceof net.minecraft.world.phys.BlockHitResult bhr) {
                treffer = bhr;
            } else {
                return true;
            }
            mc.gameMode.useItemOn(player, net.minecraft.world.InteractionHand.MAIN_HAND, treffer);
            player.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
            letzterBau = tick;
        } catch (Throwable pvpErr) {
            com.vortex.client.core.Errors.report("NetheriteFarmer.baueHoch", pvpErr);
            return false;
        }
        return true;
    }

    /** Liegt neben oder ueber diesem Block Lava? Dann nicht abbauen. */
    private static boolean lavaDran(Minecraft mc, BlockPos q) {
        if (istLava(mc, q.above())) return true;
        if (istLava(mc, q.north()) || istLava(mc, q.south())) return true;
        return istLava(mc, q.east()) || istLava(mc, q.west());
    }

    /**
     * Hoehe, auf der der Bot STEHT. Wird nur am Boden nachgefuehrt -- im
     * Sprung bleibt sie gleich. Daran haengt das ganze Hochbauen.
     */
    private static int bodenY = Integer.MIN_VALUE;

    private static void bodenNachfuehren(LocalPlayer player) {
        int y = (int) Math.floor(player.getY());
        // Am Boden, beim ersten Mal, oder wenn er tief gefallen ist (dann
        // stimmt der alte Wert sicher nicht mehr).
        if (player.onGround() || bodenY == Integer.MIN_VALUE || y < bodenY - 1 || y > bodenY + 2) {
            bodenY = y;
        }
    }

    /** Beim Bauen schneller drehen als eingestellt -- gilt nur fuer diesen Tick. */
    private static boolean schnellDrehen = false;


    // ======================================================================
    // Muell wegwerfen (2.27.0)
    // ======================================================================
    //
    // OHNE DAS HIELT DER BOT NACH 10-15 MINUTEN AN: ein 1x2-Stollen bringt
    // zwei Bloecke pro Schritt, 28 freie Plaetze sind schnell voll -- dann
    // "Inventar voll" und Schluss.
    //
    // Die fruehere Fassung warf alles Moegliche weg (auch Wichtiges). Jetzt
    // gilt eine feste Liste von reinem Gesteinsmuell, und ein Stapel
    // Netherrack bleibt immer fuer das Hochbauen. Geworfen wird nach HINTEN
    // (Blick umgedreht), damit er die Sachen nicht gleich wieder aufsammelt.

    private static final java.util.Set<net.minecraft.world.item.Item> MUELL = java.util.Set.of(
            Items.NETHERRACK, Items.BASALT, Items.SMOOTH_BASALT, Items.BLACKSTONE, Items.GRAVEL,
            Items.SOUL_SAND, Items.SOUL_SOIL, Items.MAGMA_BLOCK, Items.FLINT,
            Items.COBBLESTONE, Items.STONE, Items.DIRT, Items.NETHER_BRICK,
            Items.CRIMSON_NYLIUM, Items.WARPED_NYLIUM, Items.NETHER_WART_BLOCK, Items.WARPED_WART_BLOCK,
            Items.BONE_BLOCK);

    private static int letzterWurf = -100;

    /** Plaetze, die Muell belegen (ohne den einen Netherrack-Stapel zum Bauen). */
    private static int muellSlot(LocalPlayer player) {
        int behalten = -1;               // groesster Netherrack-Stapel bleibt
        int groesste = -1;
        for (int i = 0; i < 36; i++) {
            ItemStack st = player.getInventory().getItem(i);
            if (st != null && !st.isEmpty() && st.getItem() == Items.NETHERRACK && st.getCount() > groesste) {
                groesste = st.getCount(); behalten = i;
            }
        }
        // Hauptinventar zuerst, dann Hotbar (dort liegt das Werkzeug).
        for (int i = 35; i >= 0; i--) {
            if (i == behalten) continue;
            ItemStack st = player.getInventory().getItem(i);
            if (st == null || st.isEmpty() || !MUELL.contains(st.getItem())) continue;
            if (i == player.getInventory().getSelectedSlot()) continue;
            return i;
        }
        return -1;
    }

    private static boolean muellNoetig(LocalPlayer player) {
        NetheriteFarmerModule m = modul();
        return m != null && m.dropJunk.get() && freiePlaetze(player) <= 4 && muellSlot(player) >= 0;
    }

    /** Einen Stapel Muell nach hinten werfen. */
    private static void muellWerfen(Minecraft mc, LocalPlayer player) {
        int slot = muellSlot(player);
        if (slot < 0) return;
        float hinten = (richtung != null ? richtungZuYaw(richtung) : player.getYRot()) + 180f;
        willBlicken(hinten, -20f);
        schnellDrehen = true;
        if (blickRest(player) > 10f) return;
        if (tick - letzterWurf < 4) return;
        if (!player.inventoryMenu.getCarried().isEmpty()) return;
        try {
            int platz = indexZuFensterPlatz(slot);
            // THROW mit Taste 1 = ganzen Stapel werfen (wie Strg+Q im Inventar)
            mc.gameMode.handleContainerInput(player.inventoryMenu.containerId, platz, 1,
                    net.minecraft.world.inventory.ContainerInput.THROW, player);
            letzterWurf = tick;
            letzteUmlagerung = tick;
        } catch (Throwable pvpErr) {
            com.vortex.client.core.Errors.report("NetheriteFarmer.muell", pvpErr);
        }
    }



    // ======================================================================
    // Verhaltensbausteine
    // ======================================================================
    //
    // Jeder Baustein macht GENAU EINE Sache und kehrt zurueck. Er entscheidet
    // nicht mehr selbst, ob er drankommt -- das macht der Schiedsrichter.
    // Genau daran krankte die alte Fassung: jeder Baustein hatte seine eigene
    // Meinung dazu, und die Meinungen widersprachen sich.

    private static boolean fehltTotem(LocalPlayer player) {
        ItemStack off = player.getOffhandItem();
        boolean leer = off == null || off.isEmpty()
                || off.getItem() != Items.TOTEM_OF_UNDYING;
        return leer && findeSlot(player, Items.TOTEM_OF_UNDYING) >= 0;
    }

    private static void legeTotem(Minecraft mc, LocalPlayer player) {
        if (tick - letzteUmlagerung < 10) return;
        int quelle = findeSlot(player, Items.TOTEM_OF_UNDYING);
        if (quelle < 0) return;
        lagereUm(mc, player, quelle, OFFHAND_SLOT);
        letzteUmlagerung = tick;
        melde(mc, "Totem nachgelegt.");
    }

    // ======================================================================
    // Essen (hat immer Vorrang)
    // ======================================================================

    /**
     * Muss jetzt gegessen werden, und was? null = nein.
     *   "feuer"  brennt/in Lava ohne Feuerschutz -> verzauberter Goldapfel
     *   "apfel"  Leben unter der Grenze -> Goldapfel (verzaubert oder normal)
     *   "hunger" Hunger unter der Grenze -> normales Essen, sonst Goldapfel
     *   "regen"  Leben nicht voll und Hunger unter 18 -> normales Essen, damit
     *            die Heilung weiterlaeuft (Minecraft heilt erst ab 18 Hunger)
     */
    private static String essenNoetig(LocalPlayer player, NetheriteFarmerModule mod, boolean inLava) {
        float leben = player.getHealth();
        int hunger = player.getFoodData().getFoodLevel();
        boolean brennt = inLava || player.isOnFire();
        boolean feuerschutz = player.hasEffect(net.minecraft.world.effect.MobEffects.FIRE_RESISTANCE);
        // Brennt er ohne Feuerschutz: der Trank wirkt dauerhaft (8 min), der
        // verzauberte Apfel sofort -- bei wenig Leben der Apfel zuerst.
        if (brennt && !feuerschutz && leben < 16 && findeSlot(player, Items.ENCHANTED_GOLDEN_APPLE) >= 0) return "feuer";
        if (brennt && !feuerschutz && feuerTrank(player, false) >= 0) return "trank";
        // Vorbeugend: Feuerschutz nie ablaufen lassen (unter 30 s nachtrinken).
        if (mod.keepFireRes.get() && feuerschutzRest(player) < 600 && feuerTrank(player, false) >= 0 && !brennt) return "trank";
        if (leben <= mod.gappleBelow.get()
                && (findeSlot(player, Items.GOLDEN_APPLE) >= 0
                    || findeSlot(player, Items.ENCHANTED_GOLDEN_APPLE) >= 0)) return "apfel";
        if (hunger <= mod.eatBelow.get() && hungerEssen(player, false) >= 0) return "hunger";
        if (hunger < 18 && leben < player.getMaxHealth() - 2 && normalesEssen(player, false) >= 0) return "regen";
        return null;
    }

    /** Hotbar-Platz fuer diesen Grund, oder -1. */
    private static int essenSlot(LocalPlayer player, String grund) {
        switch (grund) {
            case "feuer": return findeHotbar(player, Items.ENCHANTED_GOLDEN_APPLE);
            case "trank": return feuerTrank(player, true);
            case "apfel": {
                int s = findeHotbar(player, Items.GOLDEN_APPLE);
                return s >= 0 ? s : findeHotbar(player, Items.ENCHANTED_GOLDEN_APPLE);
            }
            case "regen": return normalesEssen(player, true);
            default: return hungerEssen(player, true);
        }
    }

    private static boolean essenImGriff(LocalPlayer player, String grund) {
        return essenSlot(player, grund) >= 0;
    }

    private static boolean essenImRucksack(LocalPlayer player, String grund) {
        switch (grund) {
            case "feuer": return findeSlot(player, Items.ENCHANTED_GOLDEN_APPLE) > 8;
            case "trank": return feuerTrank(player, false) > 8;
            case "apfel": return findeSlot(player, Items.GOLDEN_APPLE) > 8
                    || findeSlot(player, Items.ENCHANTED_GOLDEN_APPLE) > 8;
            case "regen": return normalesEssen(player, false) > 8;
            default: return hungerEssen(player, false) > 8;
        }
    }

    /**
     * Essen gegen Hunger: das beste normale Essen, und wenn keins mehr da
     * ist, ein Goldapfel. Goldaepfel sind Essen wie jedes andere -- der Bot
     * hoert NICHT auf, nur weil kein Steak mehr da ist.
     */
    private static int hungerEssen(LocalPlayer player, boolean nurHotbar) {
        int slot = normalesEssen(player, nurHotbar);
        if (slot >= 0) return slot;
        slot = nurHotbar ? findeHotbar(player, Items.GOLDEN_APPLE) : findeSlot(player, Items.GOLDEN_APPLE);
        if (slot >= 0) return slot;
        return nurHotbar ? findeHotbar(player, Items.ENCHANTED_GOLDEN_APPLE) : findeSlot(player, Items.ENCHANTED_GOLDEN_APPLE);
    }

    /**
     * Normales Essen, das beste zuerst (Liste ESSEN ist danach sortiert).
     * Ohne Goldaepfel -- die kommen erst dran, wenn nichts anderes mehr da
     * ist (hungerEssen).
     */
    private static int normalesEssen(LocalPlayer player, boolean nurHotbar) {
        for (net.minecraft.world.item.Item item : ESSEN) {
            if (item == Items.GOLDEN_APPLE) continue;
            int slot = nurHotbar ? findeHotbar(player, item) : findeSlot(player, item);
            if (slot >= 0) return slot;
        }
        return -1;
    }

    // ======================================================================
    // Auto Totem
    // ======================================================================

    private static int letzterTotemKlick = -100;
    /** Was nachfuellen() gerade zum Essen in die Hotbar holen soll, sonst null. */
    private static String essenHolen = null;

    /**
     * Haelt jederzeit ein Totem in der Off-Hand.
     *
     * Laeuft jeden Tick vor dem Schiedsrichter. Vorher war es eine Aufgabe
     * unter vielen, mit 10 Ticks Pause zwischen Inventaraktionen -- nach
     * einem Pop konnte das Nachlegen eine halbe Sekunde dauern, und in
     * dieser halben Sekunde stirbt man in der Lava. Jetzt: 3 Ticks.
     */
    private static void autoTotem(Minecraft mc, LocalPlayer player) {
        try {
            ItemStack off = player.getOffhandItem();
            if (off != null && !off.isEmpty() && off.getItem() == Items.TOTEM_OF_UNDYING) return;
            if (tick - letzterTotemKlick < 3) return;
            // Haengt noch etwas am Cursor (Klick nicht angekommen)? Dann erst
            // warten -- sonst landet es irgendwo.
            if (!player.inventoryMenu.getCarried().isEmpty()) return;
            int quelle = findeSlot(player, Items.TOTEM_OF_UNDYING);
            if (quelle < 0) return;
            boolean warLeer = off == null || off.isEmpty();
            lagereUm(mc, player, quelle, OFFHAND_SLOT);
            letzterTotemKlick = tick;
            letzteUmlagerung = tick;
            if (warLeer) melde(mc, "Totem in die Off-Hand gelegt.");
        } catch (Throwable pvpErr) {
            com.vortex.client.core.Errors.report("NetheriteFarmer.autoTotem", pvpErr);
        }
    }

    // ======================================================================
    // Inventar voll
    // ======================================================================

    private static boolean fastVollGemeldet = false;

    private static int freiePlaetze(LocalPlayer player) {
        int n = 0;
        for (int i = 0; i < 36; i++) {
            ItemStack st = player.getInventory().getItem(i);
            if (st == null || st.isEmpty()) n++;
        }
        return n;
    }

    /** Kein freier Platz mehr -- und auch kein Debris-Stapel, der noch Platz hat. */
    private static boolean inventarVoll(LocalPlayer player) {
        for (int i = 0; i < 36; i++) {
            ItemStack st = player.getInventory().getItem(i);
            if (st == null || st.isEmpty()) return false;
            if (st.getItem() == Items.ANCIENT_DEBRIS && st.getCount() < st.getMaxStackSize()) return false;
        }
        // Voll, aber Muell dabei: der wird gleich weggeworfen -- kein Grund anzuhalten.
        NetheriteFarmerModule m = modul();
        return m == null || !m.dropJunk.get() || muellSlot(player) < 0;
    }

    // ======================================================================
    // Statistik
    // ======================================================================

    private static long laufSeit = 0;
    private static int debrisGefunden = 0;
    private static int debrisZuletzt = -1;
    private static long statistikZuletzt = 0;

    private static int zaehleDebris(LocalPlayer player) {
        int n = 0;
        for (int i = 0; i < 36; i++) {
            ItemStack st = player.getInventory().getItem(i);
            if (st != null && !st.isEmpty() && st.getItem() == Items.ANCIENT_DEBRIS) n += st.getCount();
        }
        return n;
    }

    /** Zaehlt neues Debris mit und meldet alle 5 Minuten den Stand. */
    private static void statistik(Minecraft mc, LocalPlayer player, NetheriteFarmerModule mod) {
        long jetzt = System.currentTimeMillis();
        if (laufSeit == 0) {
            laufSeit = jetzt;
            statistikZuletzt = jetzt;
        }
        int n = zaehleDebris(player);
        // Nur Zuwachs zaehlen -- legt man selbst etwas weg, sinkt die Zahl
        // nicht.
        if (debrisZuletzt >= 0 && n > debrisZuletzt) {
            debrisGefunden += n - debrisZuletzt;
            fundGemeldet(mc);
            echteFunde++;
        }
        debrisZuletzt = n;
        if (mod.stats.get() && jetzt - statistikZuletzt >= 5 * 60_000L) {
            statistikZuletzt = jetzt;
            statistikMelden(false);
        }
    }

    private static String fertigGrund = null;

    /**
     * Eine Zeile fuer die Bot-Seite: was er gerade tut und wie viel er hat.
     * Wird vom Modul ueber getStatus() geliefert.
     */
    public static String statusText() {
        if (zustand == Zustand.AUS) return "Idle";
        if (zustand == Zustand.FERTIG) return "Stopped: " + (fertigGrund == null ? "-" : fertigGrund);
        String was;
        if (aktuell == null) was = "Starting";
        else switch (aktuell) {
            case FLIEHEN:     was = "Escaping lava"; break;
            case ESSEN:       was = "Eating"; break;
            case TOTEM:       was = "Equipping totem"; break;
            case NACHFUELLEN: was = "Refilling hotbar"; break;
            case MUELL:       was = "Dropping junk"; break;
            case REPARIEREN:  was = "Repairing pickaxe"; break;
            case FREIGRABEN:  was = "Digging free"; break;
            case HOCHBAUEN:   was = "Pillaring up"; break;
            case BROCKEN:     was = "Picking up debris"; break;
            case DEBRIS:      was = "Mining debris"; break;
            default:          was = "Tunneling"; break;
        }
        long min = laufSeit == 0 ? 0 : (System.currentTimeMillis() - laufSeit) / 60000L;
        return was + "  |  " + debrisGefunden + " debris  |  " + min + " min";
    }

    private static void statistikMelden(boolean ende) {
        if (laufSeit == 0) return;
        double minuten = (System.currentTimeMillis() - laufSeit) / 60000.0;
        if (minuten < 0.5 && debrisGefunden == 0) return;
        double proStunde = minuten > 0 ? debrisGefunden / minuten * 60.0 : 0;
        melde(Minecraft.getInstance(), String.format(java.util.Locale.ROOT,
                "%s%d Ancient Debris (= %d Netherite-Barren) in %d min, %.1f pro Stunde, %d Bahnen.",
                ende ? "Beendet: " : "Stand: ", debrisGefunden, debrisGefunden / 4,
                Math.round(minuten), proStunde, bahnen));
    }

    /** Ist die Hacke in Slot a deutlich besser als in Slot b? (Hotbar-Hacke fast kaputt, Material, Effizienz) */
    private static boolean besserAls(LocalPlayer player, int a, int b) {
        ItemStack sa = player.getInventory().getItem(a), sb = player.getInventory().getItem(b);
        if (rest(sb) < 0.03 && rest(sa) >= 0.03) return true;
        if (sa.getItem() == Items.NETHERITE_PICKAXE && sb.getItem() != Items.NETHERITE_PICKAXE) return true;
        return stufe(sa, net.minecraft.world.item.enchantment.Enchantments.EFFICIENCY)
                > stufe(sb, net.minecraft.world.item.enchantment.Enchantments.EFFICIENCY);
    }

    /** Fehlt etwas Wichtiges in der Hotbar, das im Inventar liegt? */
    private static boolean nachfuellenNoetig(LocalPlayer player) {
        int inv = besteSpitzhacke(player, false), bar = besteSpitzhacke(player, true);
        if (inv > 8 && (bar < 0 || besserAls(player, inv, bar))) return true;
        if (findeHotbar(player, Items.EXPERIENCE_BOTTLE) < 0
                && findeSlot(player, Items.EXPERIENCE_BOTTLE) >= 0) return true;
        if (findeHotbar(player, Items.GOLDEN_APPLE) < 0
                && findeSlot(player, Items.GOLDEN_APPLE) >= 0) return true;
        if (findeHotbar(player, Items.ENCHANTED_GOLDEN_APPLE) < 0
                && findeSlot(player, Items.ENCHANTED_GOLDEN_APPLE) >= 0) return true;
        return normalesEssen(player, true) < 0 && normalesEssen(player, false) >= 0;
    }

    private static void sperreZiel(Minecraft mc) {
        sperre(mc, ziel, "Komme nicht heran -- grabe weiter.");
    }

    /**
     * Graebt sich frei.
     *
     * Reihenfolge: zum Ziel hin, sonst in Grabrichtung, sonst nach oben.
     * Springen ist hier verboten -- es bricht jeden Schlag ab.
     */
    private static void grabeFrei(Minecraft mc, LocalPlayer player) {
        int px = (int) Math.floor(player.getX());
        int py = (int) Math.floor(player.getY());
        int pz = (int) Math.floor(player.getZ());

        // KIES ZUERST: steht der Bot darin, liegt das Hindernis um ihn herum,
        // nicht vor ihm -- die normale Reihenfolge trifft daneben.
        if (imKiesStecken(mc, player)) {
            BlockPos kopf = new BlockPos(px, py + 1, pz);
            BlockPos fuss = new BlockPos(px, py, pz);
            BlockPos z = fest(mc, kopf) ? kopf : (fest(mc, fuss) ? fuss : null);
            if (z != null) {
                blickeAuf(player, z);
                waehleSpitzhacke(player);
                mc.options.keyUp.setDown(false);
                mc.options.keyJump.setDown(false);
                mc.options.keyAttack.setDown(blickFertig(player));
                return;
            }
        }

        int rx, rz;
        if (ziel != null) {
            int dx = ziel.getX() - px, dz = ziel.getZ() - pz;
            if (Math.abs(dx) >= Math.abs(dz)) { rx = Integer.signum(dx); rz = 0; }
            else { rx = 0; rz = Integer.signum(dz); }
        } else {
            if (richtung == null) richtung = himmelsrichtung(player.getYRot());
            rx = richtung[0]; rz = richtung[1];
        }

        BlockPos[] versuche = {
            new BlockPos(px + rx, py, pz + rz),
            new BlockPos(px + rx, py + 1, pz + rz),
            new BlockPos(px, py + 2, pz),
            new BlockPos(px + 1, py, pz), new BlockPos(px - 1, py, pz),
            new BlockPos(px, py, pz + 1), new BlockPos(px, py, pz - 1)
        };
        for (BlockPos z : versuche) {
            if (!fest(mc, z)) continue;
            if (!darfAbbauen(mc, z)) continue;       // Lava dahinter: nicht aufmachen
            blickeAuf(player, z);
            waehleSpitzhacke(player);
            mc.options.keyUp.setDown(false);
            mc.options.keyJump.setDown(false);
            mc.options.keyAttack.setDown(blickFertig(player));
            return;
        }
        // Nichts Festes in Reichweite: dann hilft ein Sprung.
        mc.options.keyJump.setDown(true);
    }

    private static java.util.UUID brockenId = null;
    private static int brockenSeit = 0;
    private static double brockenBeste = Double.MAX_VALUE;
    private static final java.util.Set<java.util.UUID> BROCKEN_GESPERRT = new java.util.HashSet<>();

    /** Holt einen herabgefallenen Brocken. */
    private static void holeBrocken(Minecraft mc, LocalPlayer player,
                                    NetheriteFarmerModule mod,
                                    net.minecraft.world.entity.item.ItemEntity brocken) {
        if (brocken == null) return;
        BlockPos bp = new BlockPos((int) Math.floor(brocken.getX()),
                                   (int) Math.floor(brocken.getY()),
                                   (int) Math.floor(brocken.getZ()));
        // Zeitgrenze je Brocken: kommt er 10 s lang nicht naeher (liegt auf
        // einem Sims, hinter etwas ...), wird genau DIESER Brocken
        // aufgegeben -- vorher lief der Bot bis zu 5 Minuten hinterher.
        java.util.UUID id = brocken.getUUID();
        double dd = brocken.distanceToSqr(player);
        if (!id.equals(brockenId)) { brockenId = id; brockenSeit = tick; brockenBeste = dd; }
        else if (dd < brockenBeste - 0.25) { brockenBeste = dd; brockenSeit = tick; }
        else if (tick - brockenSeit > 200) {
            BROCKEN_GESPERRT.add(id);
            brockenId = null;
            melde(mc, "Komme nicht an den Brocken -- lasse ihn liegen.");
            return;
        }
        // Lava: mit Feuerschutz darf er hinein (Netherit-Brocken schwimmen auf
        // Lava), ohne nicht. Gesperrt wird der Brocken selbst, nicht die Stelle
        // -- auf Lava treibt er, die Stelle aendert sich dauernd.
        if (mod.avoidLava.get() && feuerschutzRest(player) < 200 && lavaAufDemWeg(mc, player, bp)) {
            BROCKEN_GESPERRT.add(id);
            melde(mc, "Brocken liegt an Lava -- ohne Feuerschutz lasse ich ihn liegen.");
            return;
        }
        BlockPos imWeg = blockDirektDavor(mc, player, bp);
        if (imWeg != null) {
            if (!darfAbbauen(mc, imWeg) || !schlagen(mc, imWeg)) { BROCKEN_GESPERRT.add(id); return; }
            blickeAuf(player, imWeg);
            waehleSpitzhacke(player);
            mc.options.keyUp.setDown(false);
            mc.options.keyAttack.setDown(blickFertig(player));
            return;
        }
        if (feuerschutzRest(player) < 200 && !sichererSchritt(mc, player, bp.getX(), bp.getZ())) {
            BROCKEN_GESPERRT.add(id);
            return;
        }
        blickeAufPunkt(player, brocken.getX(), brocken.getY(), brocken.getZ());
        mc.options.keyUp.setDown(Math.abs(restWinkel(player)) < 40f);
        if (player.isInLava()) mc.options.keyJump.setDown(true);
    }

    /** Geht zum Debris und baut es ab. */
    private static void holeDebris(Minecraft mc, LocalPlayer player,
                                   NetheriteFarmerModule mod) {
        if (ziel == null) return;
        double d = Math.sqrt(player.distanceToSqr(
                ziel.getX() + 0.5, ziel.getY() + 0.5, ziel.getZ() + 0.5));

        if (d <= 4.5) {
            // Lava am Debris: Abbauen liesse sie in das Loch laufen -- der
            // Brocken schwimmt dann weg. Nur mit Feuerschutz (dann holt er
            // ihn auch aus der Lava), sonst liegen lassen.
            if (mod.avoidLava.get() && !darfAbbauen(mc, ziel) && feuerschutzRest(player) < 400) {
                sperre(mc, ziel, "Debris grenzt an Lava -- ohne Feuerschutz lasse ich es liegen.");
                return;
            }
            // Auch hier die Zeitgrenze: bricht der Block nicht, ist er nicht
            // zu schaffen -- Ziel sperren statt ewig draufzuschlagen. Nicht am
            // Boden (Sprung, Lava) dauert es 5x so lang: dann nicht werten.
            if (!player.onGround()) schlaegtSeit = Math.max(schlaegtSeit, tick - 40);
            if (!schlagen(mc, ziel)) {
                sperre(mc, ziel, "Debris bricht nicht -- lasse es liegen.");
                return;
            }
            blickeAuf(player, ziel);
            waehleSpitzhacke(player);
            mc.options.keyUp.setDown(false);
            mc.options.keyJump.setDown(false);
            mc.options.keyAttack.setDown(blickFertig(player));
            return;
        }
        if (mod.avoidLava.get() && lavaAufDemWeg(mc, player, ziel)) {
            sperre(mc, ziel, "Lava vor einem Debris -- lasse es liegen.");
            return;
        }
        BlockPos imWeg = blockDirektDavor(mc, player, ziel);
        if (imWeg == null) imWeg = naechsterBlockRichtung(mc, player, ziel);
        if (imWeg != null) {
            // Gleiche Regeln wie ueberall: nichts aufmachen, hinter dem Lava
            // steht, und nicht ewig auf denselben Block schlagen.
            if (!darfAbbauen(mc, imWeg) || !schlagen(mc, imWeg)) {
                sperre(mc, ziel, "Weg zum Debris ist gefaehrlich -- grabe weiter.");
                return;
            }
            blickeAuf(player, imWeg);
            waehleSpitzhacke(player);
            mc.options.keyUp.setDown(false);
            mc.options.keyAttack.setDown(blickFertig(player));
            return;
        }
        if (!sichererSchritt(mc, player, ziel.getX(), ziel.getZ())) {
            sperre(mc, ziel, "Abgrund auf dem Weg zum Debris -- grabe weiter.");
            return;
        }
        blickeAuf(player, ziel);
        mc.options.keyUp.setDown(Math.abs(restWinkel(player)) < 30f);

        // Kein Fortschritt -> aufgeben
        double dd = player.distanceToSqr(ziel.getX() + 0.5, ziel.getY() + 0.5, ziel.getZ() + 0.5);
        if (dd < zielBesteDistanz - 0.25) { zielBesteDistanz = dd; zielSeit = tick; }
        else if (tick - zielSeit > 400) sperreZiel(mc);
    }

    /** Graebt den Stollen auf der eingestellten Hoehe weiter. */
    private static void stollen(Minecraft mc, LocalPlayer player,
                                NetheriteFarmerModule mod) {
        // Nach der Lava erst ein Stueck weglaufen, nicht sofort graben.
        if (tick < lavaRuheBis) {
            if (richtung == null) richtung = himmelsrichtung(player.getYRot());
            int px = (int) Math.floor(player.getX()), py = (int) Math.floor(player.getY()), pz = (int) Math.floor(player.getZ());
            int vx = px + richtung[0], vz = pz + richtung[1];
            // Auch beim Weglaufen: keine Lava voraus, kein Loch, keine Wand.
            boolean frei = !fest(mc, new BlockPos(vx, py, vz)) && !fest(mc, new BlockPos(vx, py + 1, vz));
            if (frei && !lavaUm(mc, vx, py, vz) && sichererBoden(mc, vx, py, vz)) {
                willBlicken(richtungZuYaw(richtung), 0f);
                mc.options.keyUp.setDown(Math.abs(restWinkel(player)) < 30f);
                return;
            }
            lavaRuheBis = tick;     // weiter mit normalem Graben (prueft selbst)
        }
        int zielY = mod.mineY.getInt();
        int istY = (int) Math.floor(player.getY());
        if (istY > zielY + 1) { grabeRichtung(mc, player, mod, -1); return; }
        if (istY < zielY - 1) { grabeRichtung(mc, player, mod, +1); return; }
        merkeBesucht(player);
        if (mod.stripMine.get() && !wechseltEbene()) muster(mc, player, mod);
        grabeRichtung(mc, player, mod, wechseltEbene() ? -1 : 0);
    }


    // ======================================================================
    // Streifen-Muster (Strip Mine)
    // ======================================================================
    //
    // VORHER: geradeaus, bis etwas im Weg war, dann irgendwohin drehen. Nach
    // ein paar Lava-Ausweichern lief der Bot durch seine eigenen alten Gaenge
    // -- Minuten, in denen kein einziger neuer Block freigelegt wurde.
    //
    // JETZT: Schlangenlinie aus parallelen Bahnen im Abstand von 3 Bloecken.
    //
    //     ======>======>======>   Bahn 1   (Laenge "Lane Length")
    //                         |   3 Bloecke seitlich
    //     <======<======<======   Bahn 2
    //     |
    //     ======>======> ...
    //
    // Warum genau 3: Ancient Debris entsteht NIE an Luft (Vanilla-Worldgen,
    // discard_chance_on_air_exposure = 1). Anti-Xray zeigt ausserdem nur
    // Bloecke, die an Luft grenzen. Ein Gang legt also genau die Wand links
    // und rechts frei. Bei 3 Bloecken Abstand liegen zwischen zwei Gaengen
    // genau 2 Wandbloecke -- jeder wird von einem Gang gesehen, keiner doppelt.
    //
    // Nach jeder Stoerung (Lava, Abgrund, Wachhund) beginnt das Muster ab der
    // neuen Richtung von vorn. Die Seite wird so gewaehlt, dass sie in
    // unberuehrtes Gestein fuehrt.

    /** Abstand der Bahnen (Einstellung "Lane Spacing"). */
    private static int bahnAbstand() {
        NetheriteFarmerModule m = modul();
        return m != null ? m.laneSpacing.getInt() : 5;
    }
    private static int[] achse = null;          // Richtung der aktuellen Bahn
    private static int[] seite = null;          // wohin die naechste Bahn liegt
    private static int[] musterGesetzt = null;  // was das Muster zuletzt gesetzt hat
    private static boolean querWechsel = false; // gerade auf dem Weg zur naechsten Bahn
    private static int bahnX, bahnZ;            // Startpunkt des aktuellen Abschnitts
    private static int bahnen = 0;

    private static boolean gleich(int[] a, int[] b) {
        return a != null && b != null && a[0] == b[0] && a[1] == b[1];
    }

    private static void muster(Minecraft mc, LocalPlayer player, NetheriteFarmerModule mod) {
        int px = (int) Math.floor(player.getX());
        int pz = (int) Math.floor(player.getZ());
        if (richtung == null) richtung = himmelsrichtung(player.getYRot());

        // Hat jemand anderes (Lava, Abgrund, Wachhund) die Richtung geaendert?
        // Dann Muster ab hier neu anfangen.
        if (!gleich(richtung, musterGesetzt)) {
            achse = richtung;
            seite = besteSeite(px, pz, achse, seite);
            querWechsel = false;
            bahnX = px; bahnZ = pz;
            musterGesetzt = richtung;
            return;
        }

        int gelaufen = Math.max(0, (px - bahnX) * richtung[0] + (pz - bahnZ) * richtung[1]);
        if (!querWechsel) {
            // Bahn fertig -- oder sie laeuft gleich in einen alten Gang:
            // dann lieber jetzt schon zur naechsten Bahn wechseln.
            boolean alterGang = gelaufen >= 2
                    && besucht(px + richtung[0] * 2, pz + richtung[1] * 2)
                    && besucht(px + richtung[0] * 3, pz + richtung[1] * 3);
            if (gelaufen >= mod.laneLength.getInt() || alterGang) {
                // Liegt auf der geplanten Seite schon ein Gang? Andere Seite.
                if (besuchtAufSeite(px, pz, seite) > besuchtAufSeite(px, pz, new int[]{-seite[0], -seite[1]})) {
                    seite = new int[]{-seite[0], -seite[1]};
                }
                querWechsel = true;
                richtung = seite;
                bahnX = px; bahnZ = pz;
            }
        } else if (gelaufen >= bahnAbstand()) {
            querWechsel = false;
            achse = new int[]{-achse[0], -achse[1]};
            richtung = achse;
            bahnX = px; bahnZ = pz;
            bahnen++;
        }
        musterGesetzt = richtung;
    }

    /** Die Seite (links/rechts der Achse), auf der weniger alte Gaenge liegen. */
    private static int[] besteSeite(int px, int pz, int[] ax, int[] bisher) {
        int[] a = new int[]{-ax[1], ax[0]};
        int[] b = new int[]{ax[1], -ax[0]};
        int na = besuchtAufSeite(px, pz, a), nb = besuchtAufSeite(px, pz, b);
        if (na == nb && bisher != null && (gleich(bisher, a) || gleich(bisher, b))) return bisher;
        return na <= nb ? a : b;
    }

    /** Wie viele besuchte Felder liegen 1..8 Bloecke in diese Richtung? */
    private static int besuchtAufSeite(int px, int pz, int[] r) {
        int n = 0;
        for (int i = 1; i <= 8; i++) if (besucht(px + r[0] * i, pz + r[1] * i)) n++;
        return n;
    }

    // --- Gedaechtnis: wo war der Bot schon? --------------------------------

    private static final java.util.Set<Long> BESUCHT = new java.util.LinkedHashSet<>();

    /** Hoehe, auf der gerade gegraben wird -- ein Gang eine Ebene tiefer ist ein anderer. */
    private static int besuchtY = 0;

    private static long feld(int x, int y, int z) {
        return schluessel(new BlockPos(x, y, z));
    }

    private static boolean besucht(int x, int z) {
        return BESUCHT.contains(feld(x, besuchtY, z));
    }

    /** Neue (vorher unbesuchte) Felder -- das eigentliche Mass fuer Fortschritt. */
    private static int neueFelder = 0;

    private static void merkeBesucht(LocalPlayer player) {
        // Speicher begrenzen: die aeltesten Felder vergessen statt alles auf einmal.
        if (BESUCHT.size() > 200_000) {
            var it = BESUCHT.iterator();
            for (int i = 0; i < 50_000 && it.hasNext(); i++) { it.next(); it.remove(); }
        }
        // Auf die eingestellte Abbauhoehe runden: der Stollen schwankt um
        // +-1 -- ein Gang einen Block hoeher ist trotzdem derselbe Gang.
        NetheriteFarmerModule m = modul();
        besuchtY = m != null ? m.mineY.getInt() : (int) Math.floor(player.getY());
        if (BESUCHT.add(feld((int) Math.floor(player.getX()), besuchtY, (int) Math.floor(player.getZ())))) neueFelder++;
    }


    // ======================================================================
    // Schiedsrichter
    // ======================================================================
    //
    // WARUM DAS SO GEBAUT IST
    //
    // Vorher war die Entscheidung eine Kette aus Wenn-Dann mit ueber siebzig
    // Ausstiegspunkten. Wer zuerst zurueckkehrte, gewann -- und das wechselte
    // von Tick zu Tick. Daher kam das Kippen zwischen Hochbauen und Abbauen,
    // das Pendeln bei Lava, das Hin und Her beim Brocken. Jeder einzelne Fall
    // liess sich flicken, aber es kamen immer neue.
    //
    // Jetzt entscheidet EINE Stelle. Jedes Verhalten bewertet sich mit einer
    // Punktzahl, das hoechste gewinnt. Entscheidend ist der zweite Teil: das
    // gewaehlte Verhalten bekommt eine MINDESTZEIT und einen Bonus, solange
    // es laeuft.
    //
    // Damit ist das Kippen strukturell ausgeschlossen -- nicht nur an den
    // Stellen, die ich einzeln geflickt habe.
    //
    // Das ist keine lernende KI. Es ist die Bauweise, mit der Spiele ihre
    // Gegner steuern, und sie loest genau die Fehlerklasse, die hier immer
    // wieder aufgetreten ist.

    private enum Verhalten {
        FLIEHEN(0),        // Lava -- schlaegt alles, darf sofort uebernehmen
        ESSEN(32),         // dauert 32 Ticks und darf nicht abbrechen
        TOTEM(10),
        NACHFUELLEN(10),
        MUELL(8),
        REPARIEREN(12),
        FREIGRABEN(20),    // steckt fest
        HOCHBAUEN(40),     // laengste Mindestzeit: hier kippte es am meisten
        BROCKEN(20),
        DEBRIS(20),
        STOLLEN(10);

        final int mindestZeit;
        Verhalten(int mindestZeit) { this.mindestZeit = mindestZeit; }
    }

    private static Verhalten aktuell = null;
    private static int aktuellSeit = 0;

    /** Punkte fuer das laufende Verhalten, damit es nicht sofort weicht. */
    private static final int TREUE_BONUS = 25;

    /**
     * Muss er sich hochbauen, um ans Ziel zu kommen?
     *
     * Gerechnet wird ab der STEHhoehe (bodenY), nicht ab der aktuellen --
     * im Sprung ist er einen Block hoeher, und vorher kippte die
     * Entscheidung dadurch mitten im Sprung auf "abbauen" und zurueck.
     *
     *  - Liegt das Debris seitlich weit weg, geht er erst hin (DEBRIS graebt
     *    den Weg frei). Hochbauen lohnt nur, wenn es ungefaehr ueber ihm liegt.
     *  - Hochbauen, bis es vom Auge aus in Reichweite ist (4,2 Bloecke).
     *  - Einmal begonnen, weiter bis 3,6 Bloecke -- sonst kippt es an der
     *    Grenze hin und her.
     */
    private static boolean mussHochbauen(LocalPlayer player) {
        if (ziel == null) return false;
        int hoehe = ziel.getY() - bodenY;
        double dx = ziel.getX() + 0.5 - player.getX();
        double dz = ziel.getZ() + 0.5 - player.getZ();
        double seitlich = Math.sqrt(dx * dx + dz * dz);
        double dy = ziel.getY() + 0.5 - (bodenY + 1.62);
        double reichweite = Math.sqrt(seitlich * seitlich + dy * dy);
        if (bautGerade) return hoehe >= 2 && reichweite > 3.6 && seitlich <= 3.5;
        return hoehe >= 3 && reichweite > 4.2 && seitlich <= 3.0;
    }

    private static void entscheiden(Minecraft mc, LocalPlayer player,
                                    NetheriteFarmerModule mod) {
        try {
            // --- Laufende Aktion aufrechterhalten -------------------------
            //
            // Ohne diesen Aufruf haelt keine Aktion ihren Hotbar-Platz und
            // die Benutzen-Taste wird nie losgelassen -- der Bot wuerde
            // XP-Flaschen werfen, bis keine mehr da ist.
            boolean aktionLief = aktionLaeuft(mc, player);
            bodenNachfuehren(player);

            // --- Lage EINMAL erfassen -------------------------------------
            //
            // Vorher fragte jeder Zweig selbst nach Lava, und zwei Zweige
            // kamen im selben Tick zu unterschiedlichen Ergebnissen.
            boolean inLava = player.isInLava();
            BlockPos lava = mod.avoidLava.get()
                    ? erreichbareLava(mc, player, inLava ? 4 : 3) : null;
            float leben = player.getHealth();
            int hunger = player.getFoodData().getFoodLevel();
            boolean stecktFest = stehtSeit > 6 && tick - absichtlichWarten > 2;
            boolean aktionLaeuftNoch = aktionLief;

            // Ziel pruefen und gegebenenfalls neu suchen
            if (ziel != null
                    && mc.level.getBlockState(ziel).getBlock() != Blocks.ANCIENT_DEBRIS) {
                zielVerschwunden(mc, ziel);
                ziel = null;
                bautGerade = false;
            }
            // SUCHE HOECHSTENS ZWEIMAL PRO SEKUNDE.
            //
            // Vorher lief sie in JEDEM Tick, solange kein Ziel da war -- und
            // beim Stollengraben ist das der Normalfall. Bei Reichweite 20
            // sind das 68.921 Blockabfragen je Tick, ueber 1,3 Millionen pro
            // Sekunde, alle auf dem Spielfaden.
            //
            // Debris taucht nicht ploetzlich auf. Neu in Reichweite kommt es
            // nur, wenn der Bot sich bewegt -- rund ein Block pro Sekunde.
            // Alle 10 Ticks zu suchen verpasst also nichts und spart 90 %.
            if (ziel == null && tick >= naechsteSuche) {
                ziel = debrisInDerNaehe(mc, player, mod);
                zielSeit = tick;
                zielBesteDistanz = Double.MAX_VALUE;
                if (ziel == null) naechsteSuche = tick + 10;
            }
            net.minecraft.world.entity.item.ItemEntity brocken = nahesterBrocken(mc, player);

            // --- Bewerten --------------------------------------------------
            java.util.EnumMap<Verhalten, Integer> punkte =
                    new java.util.EnumMap<>(Verhalten.class);

            // --- Punktetabelle ---------------------------------------------
            //
            // Die Reihenfolge ist der Kern des Ganzen. Sie war vorher nicht
            // durchdacht, und zwei Faelle gingen daneben:
            //
            //  - TOTEM (800) schlug LAVA-NAEHE (700). Der Bot haette Totems
            //    umgelagert, waehrend Lava auf ihn zulief. Ein Totem kann
            //    eine Sekunde warten, Lava nicht.
            //
            //  - ESSEN bei Hunger (400) verlor gegen DEBRIS (450). Der Bot
            //    haette weitergegraben, waehrend der Hungerbalken auf null
            //    faellt -- und waere verhungert, obwohl Essen dalag.
            //
            // Jetzt in klaren Stufen:
            //   1000-900  Ueberleben JETZT (Lava, kritisches Leben)
            //    700-600  bald gefaehrlich (Totem fehlt, steckt fest)
            //    550-500  Versorgung und Ausbeute
            //    450-300  Arbeit
            //       100   Grundbeschaeftigung

            if (inLava || lava != null) {
                punkte.put(Verhalten.FLIEHEN, inLava ? 1000 : 900);
            } else if (drinSeit != 0 || nahSeit != 0) {
                // Gerade heraus: in die Richtung weiter, in die er GEFLOHEN
                // ist -- nicht einfach umkehren. Kam die Lava von hinten oder
                // von der Seite, zeigte "umgekehrt" genau zurueck zur Lava.
                drinSeit = 0;
                nahSeit = 0;
                if (fluchtWeg != null) richtung = fluchtWeg;
                else if (richtung != null) richtung = new int[]{-richtung[0], -richtung[1]};
                fluchtWeg = null;
                lavaRuheBis = tick + 40;
                // Das Ziel lag bei der Lava: sperren, sonst sucht er es in
                // 10 Ticks wieder und laeuft zurueck.
                if (ziel != null) sperre(mc, ziel, "Debris liegt an Lava -- spaeter nochmal.");
                melde(mc, "Aus der Lava heraus -- grabe woanders weiter.");
            }

            // ESSEN HAT IMMER VORRANG -- vor allem, auch vor der Lava.
            //
            // Vorher lag es bei 550 bzw. 950 Punkten: unter der Flucht aus
            // der Lava (1000) und hinter der Mindestzeit laufender Aufgaben.
            // In der Lava verbrannte der Bot mit Goldaepfeln im Rucksack.
            // Jetzt 5000: nichts kommt darueber. Waehrend er isst, laeuft er
            // trotzdem aus der Lava heraus (siehe fuehreAus, ESSEN).
            String essenGrund = essenNoetig(player, mod, inLava);
            essenHolen = null;
            // IN DER LAVA NUR ESSEN, WAS RETTET.
            //
            // Vorher schlug jedes Essen (5000) die Flucht (1000): ein
            // hungriger Bot fing in der Lava ein Steak an und lief 1,8
            // Sekunden gegen die Wand seines Fluchtwegs. Jetzt: Feuerschutz
            // (Goldapfel/Trank) und der Notfall-Apfel ja, normales Essen erst
            // danach.
            boolean gefahr = inLava || lava != null;
            if (gefahr && essenGrund != null && !essenGrund.equals("feuer") && !essenGrund.equals("trank")
                    && !(essenGrund.equals("apfel") && leben <= 8f)) {
                essenGrund = null;
            }
            // Laeuft gerade normales Essen und es wird gefaehrlich: abbrechen.
            if (gefahr && aktionSlot >= 0 && aktionHalten && aktionGrund != null
                    && !aktionGrund.equals("feuer") && !aktionGrund.equals("trank") && !aktionGrund.equals("apfel")) {
                mc.options.keyUse.setDown(false);
                aktionSlot = -1;
                aktionLaeuftNoch = false;
            }
            if (essenGrund != null) {
                if (essenImGriff(player, essenGrund)) {
                    punkte.put(Verhalten.ESSEN, 5000);
                } else if (essenImRucksack(player, essenGrund)) {
                    // Liegt nur im Inventar: erst in die Hotbar holen -- das
                    // gehoert zum Essen und bekommt denselben Vorrang.
                    punkte.put(Verhalten.NACHFUELLEN, 4900);
                    essenHolen = essenGrund;
                }
            }

            // Totem: macht Auto Totem jeden Tick nebenher. Nur wenn es
            // ausgeschaltet ist, bleibt es eine Aufgabe des Schiedsrichters.
            if (!mod.autoTotem.get() && fehltTotem(player)) punkte.put(Verhalten.TOTEM, 700);
            // FREIGRABEN nur, wenn er NICHT gerade abbaut.
            //
            // Sonst verdraengt es mit 600 Punkten den Debris-Abbau (450) --
            // der Bot haette mitten im Schlag abgebrochen, um sich
            // "freizugraben", obwohl er genau das schon tat.
            if (stecktFest && !mc.options.keyAttack.isDown()) {
                punkte.put(Verhalten.FREIGRABEN, 600);
            }

            // Nachfuellen ist DRINGEND, wenn das Fehlende gleich gebraucht
            // wird -- sonst kann er nicht essen, obwohl Essen im Rucksack
            // liegt.
            if (nachfuellenNoetig(player) && !punkte.containsKey(Verhalten.NACHFUELLEN)) {
                punkte.put(Verhalten.NACHFUELLEN, 350);
            }

            // Fast voll: vor dem Einsammeln Platz schaffen, sonst bleibt der
            // naechste Brocken liegen.
            if (muellNoetig(player)) punkte.put(Verhalten.MUELL, 520);
            if (brocken != null) punkte.put(Verhalten.BROCKEN, 500);
            if (ziel != null) {
                punkte.put(mussHochbauen(player) ? Verhalten.HOCHBAUEN : Verhalten.DEBRIS, 450);
            }
            if (brauchtReparatur(player, mod) && tick - letzteFlasche >= FLASCHEN_ABSTAND) {
                punkte.put(Verhalten.REPARIEREN, 300);
            }
            punkte.put(Verhalten.STOLLEN, 100);        // immer moeglich

            // --- Waehlen, mit Treue zum Laufenden -------------------------
            Verhalten beste = Verhalten.STOLLEN;
            int besteP = Integer.MIN_VALUE;
            for (java.util.Map.Entry<Verhalten, Integer> e : punkte.entrySet()) {
                int pkt = e.getValue();
                if (e.getKey() == aktuell) pkt += TREUE_BONUS;
                if (pkt > besteP) { besteP = pkt; beste = e.getKey(); }
            }

            // Mindestzeit: ein laufendes Verhalten wird nicht sofort
            // verdraengt -- ausser von Flucht, Essen und Freigraben.
            //
            // FREIGRABEN gehoert dazu, weil Feststecken bedeutet, dass das
            // laufende Verhalten ohnehin nichts bewirkt. Es zwei Sekunden
            // lang festzuhalten, waehrend der Bot in einer Ecke klemmt, ist
            // genau die Blockade, die wir loswerden wollten.
            if (aktuell != null && beste != aktuell
                    && tick - aktuellSeit < aktuell.mindestZeit
                    && punkte.containsKey(aktuell)
                    && beste != Verhalten.FLIEHEN && beste != Verhalten.ESSEN
                    && beste != Verhalten.FREIGRABEN
                    && punkte.getOrDefault(beste, 0) < 4900) {   // Essen holen wartet auf nichts
                beste = aktuell;
            }
            // Eine laufende Essensaktion laeuft IMMER zu Ende -- auch in der
            // Lava. Abgebrochenes Essen ist verlorene Zeit; der Bot laeuft
            // beim Essen trotzdem aus der Lava heraus.
            if (aktionLaeuftNoch && aktionHalten) {
                beste = Verhalten.ESSEN;
            }

            if (beste != aktuell) {
                aktuell = beste;
                aktuellSeit = tick;
            }
            // JEDEN Tick nachfuehren, nicht nur beim Wechsel.
            //
            // Vorher wurde bautGerade nur beim Verhaltenswechsel gesetzt.
            // Blieb der Bot beim Hochbauen, blieb auch der Wert stehen --
            // und wenn er das Hochbauen verliess, ohne dass ein Wechsel
            // erkannt wurde, blieb er faelschlich auf true. Die Hysterese
            // hielt dann ein Hochbauen am Leben, das gar nicht mehr lief.
            bautGerade = (beste == Verhalten.HOCHBAUEN);
            if (!bautGerade) {                 // fuer das naechste Mal zuruecksetzen
                bauPhase = 0;
                bauFortschrittY = Integer.MIN_VALUE;
            }

            fuehreAus(mc, player, mod, beste, lava, brocken, inLava);

        } catch (Throwable pvpErr) {
            com.vortex.client.core.Errors.report("NetheriteFarmer.entscheiden", pvpErr);
        }
    }

    /** Fuehrt das gewaehlte Verhalten aus. Genau EIN Zweig je Tick. */
    private static void fuehreAus(Minecraft mc, LocalPlayer player,
                                  NetheriteFarmerModule mod, Verhalten was,
                                  BlockPos lava,
                                  net.minecraft.world.entity.item.ItemEntity brocken,
                                  boolean inLava) {
        // Alle Bewegungstasten zuruecksetzen. Jeder Tick faengt bei null an,
        // damit keine Taste aus dem vorigen Verhalten haengenbleibt -- daran
        // lag das Dauerspringen.
        mc.options.keyUp.setDown(false);
        mc.options.keyDown.setDown(false);
        mc.options.keyJump.setDown(false);
        mc.options.keyAttack.setDown(false);

        switch (was) {
            case FLIEHEN: {
                zustand = Zustand.GEHT;
                boolean isst = aktionSlot >= 0 && aktionHalten;

                // Zeitgeber und Eskalation: ohne sie fehlen alle Stufen --
                // keine neue Fluchtrichtung nach fuenf Sekunden, kein
                // Richtungswechsel ohne Ausweg.
                if (inLava) {
                    mc.options.keyJump.setDown(true);
                    if (drinSeit == 0) {
                        drinSeit = tick;
                        if (tick - letzteLavaMeldung > 200) {
                            letzteLavaMeldung = tick;
                            melde(mc, "In Lava -- grabe mich heraus.");
                        }
                    } else if (tick - drinSeit > 100) {
                        drinSeit = tick;
                        fluchtWeg = null;      // neue Richtung, NICHT abschalten
                    }
                } else if (lava != null && nahSeit == 0) {
                    nahSeit = tick;
                    if (tick - letzteLavaMeldung > 200) {
                        letzteLavaMeldung = tick;
                        melde(mc, "Lava in der Naehe -- weiche aus.");
                    }
                }

                if (lava != null) {
                    if (fliehen(mc, player, lava, !isst)) break;
                    if (nahSeit != 0 && tick - nahSeit > 200 && richtung != null) {
                        nahSeit = 0;
                        richtung = neueRichtung(mc, richtung);
                    }
                    break;
                }
                if (inLava && !isst) {
                    willBlicken(player.getYRot(), -90f);
                    waehleSpitzhacke(player);
                    mc.options.keyAttack.setDown(true);
                }
                break;
            }
            case ESSEN: {
                zustand = Zustand.ISST;
                if (aktionSlot < 0) {
                    String grund = essenNoetig(player, mod, inLava);
                    int slot = grund == null ? -1 : essenSlot(player, grund);
                    // 36 statt 32 Ticks: ein kleiner Puffer fuer Server-Lag,
                    // sonst wird das Essen knapp vor dem Ende abgebrochen.
                    if (slot >= 0 && starteAktion(mc, player, slot, 36, true)) aktionGrund = grund;
                }
                // WAEHREND DES ESSENS AUS DER GEFAHR: Essen und Laufen geht
                // gleichzeitig. In der Lava nach oben schwimmen und von ihr
                // weg -- aber nicht graben, ein Schlag wuerde das Essen
                // abbrechen.
                if (inLava) mc.options.keyJump.setDown(true);
                if (lava != null) fliehen(mc, player, lava, false);
                break;
            }
            case TOTEM:       legeTotem(mc, player); break;
            case NACHFUELLEN: nachfuellen(mc, player, mod); break;
            case MUELL:       muellWerfen(mc, player); break;
            case REPARIEREN:  zustand = Zustand.REPARIERT; reparieren(mc, player, mod); break;
            case FREIGRABEN:  zustand = Zustand.GRAEBT; grabeFrei(mc, player); break;
            case HOCHBAUEN:
                zustand = Zustand.GEHT;
                if (!baueHoch(mc, player)) {
                    // Geht nicht (Material, Lava, kein Fortschritt): Ziel
                    // aufgeben statt endlos zu warten.
                    bautGerade = false;
                    bauPhase = 0;
                    bauFortschrittY = Integer.MIN_VALUE;
                    tastenLos(mc);
                    sperreZiel(mc);
                }
                break;
            case BROCKEN:     zustand = Zustand.GEHT; holeBrocken(mc, player, mod, brocken); break;
            case DEBRIS:      zustand = Zustand.GRAEBT; holeDebris(mc, player, mod); break;
            case STOLLEN:     zustand = Zustand.GRAEBT; stollen(mc, player, mod); break;
        }
    }


    // ======================================================================
    // Abbruchbedingungen und Reparatur
    // ======================================================================

    /** Ist ein anderer Spieler in Reichweite? */
    private static boolean spielerWarNah = false;
    private static int letztesAfk = -100000;

    private static boolean fremderSpielerNah(Minecraft mc, NetheriteFarmerModule mod) {
        double r = mod.playerRange.get() + (spielerWarNah ? 8 : 0);
        double r2 = r * r;
        boolean nah = false;
        for (Player p : mc.level.players()) {
            if (p == mc.player) continue;
            // Freunde (Freundesliste des Clients und Vortex-Freunde) stoeren nicht.
            try { if (com.vortex.client.core.Friends.istFreundName(p.getName().getString())) continue; } catch (Throwable ignored) { }
            if (p.distanceToSqr(mc.player) <= r2) { nah = true; break; }
        }
        spielerWarNah = nah;
        return nah;
    }

    /** Lava oder Feuer: dann muss der Bot handeln, auch in der Pause. */
    private static boolean inGefahr(Minecraft mc, LocalPlayer player) {
        return player.isInLava() || player.isOnFire() || erreichbareLava(mc, player, 2) != null;
    }

    /**
     * Was ist ausgegangen? null heisst: alles da.
     *
     * Geprueft wird das GANZE Inventar, nicht nur die Hotbar -- das
     * Nachfuellen holt es von dort herauf.
     */
    private static String wasFehlt(LocalPlayer player, NetheriteFarmerModule mod) {
        if (findeSlot(player, Items.NETHERITE_PICKAXE) < 0
                && findeSlot(player, Items.DIAMOND_PICKAXE) < 0) {
            return "keine Spitzhacke mehr";
        }
        // Nicht bis zum Zerbrechen graben: eine Netherit-Spitzhacke mit
        // Effizienz ist mehr wert als eine Stunde Debris.
        if (!brauchbareSpitzhacke(player)) return "Spitzhacke fast kaputt";
        // Goldaepfel zaehlen als Essen (wie frueher).
        if (hungerEssen(player, false) < 0) return "kein Essen mehr";
        if (findeSlot(player, Items.TOTEM_OF_UNDYING) < 0
                && (player.getOffhandItem() == null
                    || player.getOffhandItem().isEmpty()
                    || player.getOffhandItem().getItem() != Items.TOTEM_OF_UNDYING)) {
            return "kein Totem mehr";
        }
        // XP-Flaschen braucht nur, wer mit Mending reparieren kann.
        int hacke = besteSpitzhacke(player, false);
        if (hacke >= 0 && hatMending(player.getInventory().getItem(hacke))
                && findeSlot(player, Items.EXPERIENCE_BOTTLE) < 0
                && rest(player.getInventory().getItem(hacke)) < 0.25) {
            return "keine XP-Flaschen mehr";
        }
        // Voll: gefundenes Debris bliebe liegen. Lieber anhalten als
        // stundenlang umsonst zu graben.
        if (inventarVoll(player)) return "Inventar voll";
        int frei = freiePlaetze(player);
        if (frei <= 3 && !fastVollGemeldet) {
            fastVollGemeldet = true;
            melde(Minecraft.getInstance(), "Inventar fast voll (" + frei + " Plaetze frei).");
        } else if (frei > 5) {
            fastVollGemeldet = false;
        }
        return null;
    }

    /** Ist die Spitzhacke unter der Reparaturgrenze? */
    private static boolean brauchtReparatur(LocalPlayer player,
                                            NetheriteFarmerModule mod) {
        int slot = besteSpitzhacke(player, true);
        if (slot < 0) return false;
        ItemStack hacke = player.getInventory().getItem(slot);
        // Ohne Mending bringen XP-Flaschen nichts -- vorher warf er trotzdem
        // alle weg und hoerte dann mit "keine XP-Flaschen" auf.
        if (!hatMending(hacke)) return false;
        return beschaedigt(hacke, REPARIEREN_UNTER) && findeHotbar(player, Items.EXPERIENCE_BOTTLE) >= 0;
    }

    private static boolean beschaedigt(ItemStack st, double grenze) {
        if (st == null || st.isEmpty() || !st.isDamageableItem()) return false;
        int max = st.getMaxDamage();
        if (max <= 0) return false;
        double rest = (max - st.getDamageValue()) / (double) max;
        return rest < grenze;
    }

    /**
     * Wirft eine XP-Flasche.
     *
     * Der Platz wird laenger gehalten als der eine Wurf-Tick: vorher
     * schaltete der Bot sofort zurueck zur Spitzhacke, die Flasche flog nie,
     * und man sah nur das Hin- und Herschalten.
     */
    private static void reparieren(Minecraft mc, LocalPlayer player,
                                   NetheriteFarmerModule mod) {
        int slot = findeHotbar(player, Items.EXPERIENCE_BOTTLE);
        if (slot < 0) return;
        // Auf den Boden werfen: in die Luft geworfen landet die Flasche
        // irgendwo (ueber einem Abgrund, in Lava) und die XP ist weg.
        willBlicken(player.getYRot(), 88f);
        schnellDrehen = true;
        if (player.getXRot() < 80f) return;
        if (!starteAktion(mc, player, slot, FLASCHEN_HALTEN, false)) return;
        letzteFlasche = tick;
    }

}
