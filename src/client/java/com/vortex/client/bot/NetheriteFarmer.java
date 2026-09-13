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
 *   1. Muss ich aufhoeren?      (Spieler in der Naehe, Vorrat leer)
 *   2. Muss ich ueberleben?     (Leben, Hunger, Totem, Ruestung)
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

    /** Was der Bot gerade tut -- nur fuer die Anzeige und das Protokoll. */
    public enum Zustand { AUS, SUCHT, GEHT, GRAEBT, ISST, REPARIERT, FERTIG }

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
                mc.options.keyShift.setDown(false);
                mc.options.keyJump.setDown(false);
                mc.options.keyDown.setDown(false);
            }
        } catch (Throwable ignored) { }
        zustand = Zustand.AUS;
        ziel = null;
        richtung = null;
        gemeldet = false;
        aktionSlot = -1;
        stehtSeit = 0;
        drehVersuche = 0;
        drinSeit = 0;
        nahSeit = 0;
        fluchtWeg = null;
        drehungenZuletzt = 0;
        ebenenWechsel = 0;
        lavaRuheBis = 0;
        schlaegtAuf = null;
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

            // Nur im Nether. Ancient Debris gibt es nirgendwo sonst -- in der
            // Oberwelt wuerde der Bot stundenlang Stein wegraeumen und dabei
            // Werkzeug und Nahrung verbrauchen, ohne je etwas zu finden.
            if (!imNether(mc)) {
                if (!gemeldet) {
                    gemeldet = true;
                    melde(mc, "Nur im Nether. Modul bleibt aus.");
                    tastenLos(mc);
                }
                return;
            }
            tick++;

            // --- 1. Aufhoeren -------------------------------------------
            if (mod.logoutOnPlayer.get() && fremderSpielerNah(mc, mod)) {
                melde(mc, "Spieler in der Naehe -- logge aus.");
                logout(mc);
                return;
            }
            String fehlt = wasFehlt(player, mod);
            if (fehlt != null) {
                if (!gemeldet) {
                    gemeldet = true;
                    zustand = Zustand.FERTIG;
                    melde(mc, "Fertig: " + fehlt);
                    tastenLos(mc);
                    if (mod.afkWhenDone.get()) sendeBefehl(mc, "afk");
                }
                return;
            }
            gemeldet = false;

            // Ab hier kann jeder Zweig vorzeitig zurueckkehren. Damit der
            // Blick TROTZDEM bewegt wird, laeuft der Rest in try/finally.
            //
            // FRUEHER stand wendeBlick nur ganz am Ende. Jede vorzeitige
            // Rueckkehr -- Flucht, Essen, Nachfuellen -- uebersprang sie. Der
            // Bot hinterlegte seinen Blickwunsch und wandte ihn nie an: er
            // stand mit dem Gesicht in die falsche Richtung und lief dorthin,
            // waehrend er eigentlich fliehen wollte.
            try {
                entscheiden(mc, player, mod);
            } finally {
                pruefeFeststecken(mc, player);
                // Nie gleichzeitig schlagen und springen -- ein Sprung
                // bricht jeden Abbau ab.
                if (mc.options.keyAttack.isDown()) {
                    mc.options.keyJump.setDown(false);
                }
                wendeBlick(player, mod);
            }
        } catch (Throwable pvpErr) {
            com.vortex.client.core.Errors.report("NetheriteFarmer", pvpErr);
            stop();
        }
    }

    /** Alle Entscheidungen eines Ticks. Darf jederzeit zurueckkehren. */
    private static void entscheiden(Minecraft mc, LocalPlayer player,
                                    NetheriteFarmerModule mod) {
        try {
            // Laeuft schon etwas? Dann nicht dazwischenfunken.
            //
            // AUSNAHME Lava: dort muss er sich weiterbewegen duerfen, auch
            // waehrend er isst. Sonst stand er essend in der Lava, bis beides
            // zu Ende war.
            boolean inGefahr = player.isInLava() || erreichbareLava(mc, player, 3) != null;
            if (aktionLaeuft(mc, player) && !inGefahr) return;

            // --- TOTEM ZUERST, vor allem anderen ---------------------------
            //
            // Ein Totem poppt genau dann, wenn es gefaehrlich ist -- und
            // frueher lief das Nachlegen erst NACH der Gefahrenbehandlung.
            // Die kehrte aber vorzeitig zurueck, also blieb die Off-Hand in
            // genau der Lage leer, in der das naechste Totem gebraucht wird.
            //
            // Steht hier ganz oben und kostet fast nichts: eine Abfrage der
            // Off-Hand je Tick.
            if (mod.keepTotem.get()) {
                ItemStack off = player.getOffhandItem();
                if ((off == null || off.isEmpty()
                        || off.getItem() != Items.TOTEM_OF_UNDYING)
                        && tick - letzteUmlagerung >= 10) {
                    int quelle = findeSlot(player, Items.TOTEM_OF_UNDYING);
                    if (quelle >= 0) {
                        lagereUm(mc, player, quelle, OFFHAND_SLOT);
                        letzteUmlagerung = tick;
                        melde(mc, "Totem nachgelegt.");
                        return;
                    }
                }
            }

            // ALLE Bewegungstasten zuruecksetzen.
            //
            // Vorher setzte jede Stelle ihre Tasten selbst, aber niemand
            // nahm sie zurueck. Folge: die Sprungtaste blieb gedrueckt,
            // waehrend der Bot abbaute -- er sprang dauernd und brach den
            // Abbau jedes Mal ab. Dasselbe mit Vorwaerts und Rueckwaerts.
            //
            // Jetzt gilt: jeder Tick faengt bei null an, und nur wer eine
            // Taste WIRKLICH braucht, drueckt sie.
            mc.options.keyUp.setDown(false);
            mc.options.keyDown.setDown(false);
            mc.options.keyJump.setDown(false);
            mc.options.keyAttack.setDown(false);

            // --- 2. Ueberleben -------------------------------------------
            if (ueberleben(mc, player, mod)) return;

            // --- 2b. Nachfuellen aus dem Inventar -------------------------
            if (nachfuellen(mc, player, mod)) return;

            // --- 3. Reparieren -------------------------------------------
            if (reparieren(mc, player, mod)) return;

            // --- 4. Abbauen ----------------------------------------------
            abbauen(mc, player, mod);

        } catch (Throwable pvpErr) {
            com.vortex.client.core.Errors.report("NetheriteFarmer.entscheiden", pvpErr);
        }
    }

    // ------------------------------------------------------------------
    // 1. Aufhoeren
    // ------------------------------------------------------------------

    /** Ist ein anderer Spieler in Reichweite? */
    private static boolean fremderSpielerNah(Minecraft mc, NetheriteFarmerModule mod) {
        double r = mod.playerRange.get();
        double r2 = r * r;
        for (Player p : mc.level.players()) {
            if (p == mc.player) continue;
            if (p.distanceToSqr(mc.player) <= r2) return true;
        }
        return false;
    }

    /**
     * Was ist ausgegangen? null heisst: alles da.
     *
     * Der Bot hoert lieber auf, als ohne Werkzeug oder Nahrung weiterzulaufen.
     */
    private static String wasFehlt(LocalPlayer player, NetheriteFarmerModule mod) {
        if (findeSlot(player, Items.NETHERITE_PICKAXE) < 0
                && findeSlot(player, Items.DIAMOND_PICKAXE) < 0) {
            return "keine Spitzhacke mehr";
        }
        if (findeEssen(player) < 0) return "kein Essen mehr";
        if (mod.keepTotem.get() && findeSlot(player, Items.TOTEM_OF_UNDYING) < 0) {
            return "kein Totem mehr";
        }
        if (findeSlot(player, Items.EXPERIENCE_BOTTLE) < 0) {
            return "keine XP-Flaschen mehr";
        }
        return null;
    }

    private static void logout(Minecraft mc) {
        try {
            tastenLos(mc);
            stop();
            // Verbindung trennen. Der Bildschirm danach uebernimmt der
            // normale Ablauf des Spiels.
            if (mc.getConnection() != null) {
                mc.getConnection().getConnection().disconnect(
                        net.minecraft.network.chat.Component.literal("Vortex: Spieler in der Naehe"));
            }
        } catch (Throwable pvpErr) {
            com.vortex.client.core.Errors.report("NetheriteFarmer.logout", pvpErr);
        }
    }

    // ------------------------------------------------------------------
    // 2. Ueberleben
    // ------------------------------------------------------------------

    /** @return true, wenn der Bot in diesem Tick mit Ueberleben beschaeftigt ist. */
    private static boolean ueberleben(Minecraft mc, LocalPlayer player,
                                      NetheriteFarmerModule mod) {
        // --- In Lava? Dann RAUS, nicht essen ------------------------------
        //
        // Das war der toedlichste Fehler: der Bot stand in Lava, verlor
        // Leben, ass einen goldenen Apfel, verlor weiter Leben, ass den
        // naechsten -- bis alle weg waren und er starb. Essen heilt schneller
        // als Lava schadet, aber nur solange Vorrat da ist. Rauskommen ist
        // die einzige Loesung.
        // --- GOLDENER APFEL ZUERST ----------------------------------------
        //
        // Vor allem anderen, auch vor der Lavaflucht. Wer stirbt, waehrend er
        // ausweicht, hat nichts gewonnen. Essen und Laufen gehen gleichzeitig.
        if (player.getHealth() <= mod.gappleBelow.get() && aktionSlot < 0) {
            int apfel = findeHotbar(player, Items.GOLDEN_APPLE);
            if (apfel < 0) apfel = findeHotbar(player, Items.ENCHANTED_GOLDEN_APPLE);
            if (apfel >= 0) {
                player.getInventory().setSelectedSlot(apfel);
                aktionSlot = apfel;
                aktionBis = tick + 32;
                aktionHalten = true;
                mc.options.keyUse.setDown(true);
                zustand = Zustand.ISST;
                // KEIN return: er soll dabei weiterlaufen oder fliehen.
            }
        }

        // --- Lava ---------------------------------------------------------
        //
        // ZWEI GETRENNTE ZAEHLER. Frueher war es einer, und das hatte eine
        // boese Folge: stand irgendwo Lava in der Naehe, lief die Frist schon
        // los. Beruehrte er sie Minuten spaeter, war sie laengst abgelaufen
        // und der Bot stoppte sofort mit "komme nicht heraus" -- obwohl er
        // gerade erst hineingeraten war.
        boolean drin = player.isInLava();
        BlockPos lava = erreichbareLava(mc, player, drin ? 4 : 3);

        if (drin) {
            if (drinSeit == 0) {
                drinSeit = tick;
                melde(mc, "In Lava -- grabe mich heraus.");
            }
            zustand = Zustand.GEHT;
            mc.options.keyJump.setDown(true);     // in Lava haelt Springen oben

            // Erst nach fuenf Sekunden IM Feuer aufgeben, nicht nach fuenf
            // Sekunden seit irgendeiner Lavasichtung.
            // NIEMALS wegen Lava abschalten.
            //
            // Der Bot soll es weiter versuchen, statt sich selbst zu beenden
            // -- ein stehender Bot in der Lava stirbt sicher, ein kaempfender
            // vielleicht nicht.
            //
            // Nach fuenf Sekunden wird nur die Fluchtrichtung neu gewaehlt,
            // falls die bisherige nichts gebracht hat.
            if (tick - drinSeit > 100) {
                drinSeit = tick;
                fluchtWeg = null;
                melde(mc, "Immer noch in Lava -- neue Richtung.");
            }
            if (lava != null && fliehen(mc, player, lava)) return true;
            // Keine Lava mehr zu sehen, aber noch drin: senkrecht hoch.
            willBlicken(player.getYRot(), -90f);
            waehleSpitzhacke(player);
            mc.options.keyAttack.setDown(true);
            return true;
        }

        if (drinSeit != 0) {
            drinSeit = 0;
            // Gerade heraus: Richtung umkehren und kurz weglaufen.
            if (richtung != null) richtung = new int[]{-richtung[0], -richtung[1]};
            lavaRuheBis = tick + 60;
            ziel = null;
            schlagenZuruecksetzen();
            melde(mc, "Aus der Lava heraus -- grabe woanders weiter.");
        }

        if (lava != null) {
            if (nahSeit == 0) {
                nahSeit = tick;
                // Hoechstens alle zehn Sekunden melden. Vorher wechselte der
                // Zustand staendig zwischen "Lava da" und "keine Lava", und
                // jede Flanke schrieb eine neue Zeile in den Chat.
                if (tick - letzteLavaMeldung > 200) {
                    letzteLavaMeldung = tick;
                    melde(mc, "Lava in der Naehe -- weiche aus.");
                }
            }
            zustand = Zustand.GEHT;
            if (fliehen(mc, player, lava)) return true;
            // Kein Fluchtweg gefunden: nach zehn Sekunden Richtung wechseln,
            // statt bewegungslos stehenzubleiben.
            if (tick - nahSeit > 200 && richtung != null) {
                nahSeit = 0;
                richtung = neueRichtung(mc, richtung);
                melde(mc, "Kein Ausweg -- andere Richtung.");
            }
            return true;
        }
        nahSeit = 0;

        // Nach der Lava eine Weile nur laufen, nicht graben.
        if (tick < lavaRuheBis) {
            zustand = Zustand.GEHT;
            if (richtung == null) richtung = himmelsrichtung(player.getYRot());
            willBlicken(richtungZuYaw(richtung), 0f);
            mc.options.keyUp.setDown(true);
            return true;
        }

        // Goldener Apfel bei Schaden -- auch mit vollem Hunger. In Lava ist
        // das der einzige Weg, der wirklich hilft.
        if (player.getHealth() <= mod.gappleBelow.get()) {
            int slot = findeSlot(player, Items.GOLDEN_APPLE);
            if (slot < 0) slot = findeSlot(player, Items.ENCHANTED_GOLDEN_APPLE);
            // 32 Ticks: so lange dauert Essen. Kuerzer und es bricht ab.
            if (slot >= 0 && starteAktion(mc, player, slot, 32, true)) {
                zustand = Zustand.ISST;
                return true;
            }
        }
        // Normales Essen bei Hunger.
        if (player.getFoodData().getFoodLevel() <= mod.eatBelow.get()) {
            int slot = findeEssen(player);
            if (slot >= 0 && starteAktion(mc, player, slot, 32, true)) {
                zustand = Zustand.ISST;
                return true;
            }
        }
        // Totem in die Off-Hand -- das bestehende AutoTotem-Modul macht das
        // bereits, wenn es an ist. Hier wird nur geprueft, ob ueberhaupt
        // eines da ist; das Nachlegen bleibt bei AutoTotem, damit sich die
        // beiden nicht gegenseitig ins Inventar greifen.
        return false;
    }

    // ------------------------------------------------------------------
    // 3. Reparieren
    // ------------------------------------------------------------------

    /**
     * Wirft eine XP-Flasche, wenn Werkzeug oder Ruestung Reparatur brauchen.
     *
     * Der Abstand zwischen den Wuerfen ist funktional: eine Flasche zu frueh
     * geworfen repariert nichts, weil noch nichts beschaedigt ist.
     */
    private static boolean reparieren(Minecraft mc, LocalPlayer player,
                                      NetheriteFarmerModule mod) {
        if (tick - letzteFlasche < mod.bottleDelay.getInt()) return false;
        if (!brauchtReparatur(player, mod)) return false;

        int slot = findeSlot(player, Items.EXPERIENCE_BOTTLE);
        if (slot < 0) return false;
        // Ein Druck, dann loslassen -- sonst wirft er alle auf einmal.
        if (!starteAktion(mc, player, slot, 4, false)) return false;

        letzteFlasche = tick;
        zustand = Zustand.REPARIERT;
        return true;
    }

    /** Ist irgendetwas Getragenes unter der eingestellten Haltbarkeit? */
    private static boolean brauchtReparatur(LocalPlayer player,
                                            NetheriteFarmerModule mod) {
        double grenze = mod.repairBelow.get() / 100.0;
        ItemStack hand = player.getMainHandItem();
        if (beschaedigt(hand, grenze)) return true;
        // Ruestung ueber die Ausruestungsplaetze lesen.
        //
        // getArmorSlots() gibt es hier nicht -- getItemBySlot(EquipmentSlot)
        // ist der im Projekt belegte Weg (ArmorHud und
        // LivingEntityRendererMixin machen es genauso).
        net.minecraft.world.entity.EquipmentSlot[] plaetze = {
            net.minecraft.world.entity.EquipmentSlot.HEAD,
            net.minecraft.world.entity.EquipmentSlot.CHEST,
            net.minecraft.world.entity.EquipmentSlot.LEGS,
            net.minecraft.world.entity.EquipmentSlot.FEET
        };
        for (net.minecraft.world.entity.EquipmentSlot platz : plaetze) {
            if (beschaedigt(player.getItemBySlot(platz),
                    mod.armorBelow.get() / 100.0)) return true;
        }
        return false;
    }

    private static boolean beschaedigt(ItemStack st, double grenze) {
        if (st == null || st.isEmpty() || !st.isDamageableItem()) return false;
        int max = st.getMaxDamage();
        if (max <= 0) return false;
        double rest = (max - st.getDamageValue()) / (double) max;
        return rest < grenze;
    }

    // ------------------------------------------------------------------
    // 4. Abbauen
    // ------------------------------------------------------------------

    /**
     * Kern: einen Stollen auf der eingestellten Hoehe graben.
     *
     * WARUM NICHT SUCHEN: Auf den allermeisten Servern liefert der Server
     * die Bloecke hinter Stein gar nicht aus -- ein Bot, der auf eine
     * Blocksuche wartet, steht ewig herum. Gegraben wird deshalb blind, so
     * wie es ein Mensch auch tut. Gefunden wird, was beim Graben auftaucht.
     *
     * Ablauf je Tick:
     *   1. Liegt freigelegtes Debris in kurzer Reichweite? -> hinsehen, abbauen
     *   2. Bin ich auf der falschen Hoehe? -> hin
     *   3. Sonst: geradeaus weitergraben
     */
    private static void abbauen(Minecraft mc, LocalPlayer player,
                                NetheriteFarmerModule mod) {
        // --- 0. Herabgefallene Brocken einsammeln -------------------------
        //
        // Abgebautes Debris liegt als Gegenstand am Boden. Minecraft hebt es
        // nur auf, wenn man darueberlaeuft -- ohne diesen Schritt graebt der
        // Bot weiter und laesst die Ausbeute liegen. Frueher wurde nur
        // zufaellig etwas aufgesammelt, naemlich wenn der Weg ohnehin
        // darueber fuehrte.
        net.minecraft.world.entity.item.ItemEntity brocken = nahesterBrocken(mc, player);
        if (brocken != null) {
            zustand = Zustand.GEHT;
            mc.options.keyAttack.setDown(false);
            blickeAufPunkt(player, brocken.getX(), brocken.getY(), brocken.getZ());
            mc.options.keyUp.setDown(true);
            return;
        }

        // --- 1. Freigelegtes Debris hat Vorrang ---------------------------
        if (ziel != null
                && mc.level.getBlockState(ziel).getBlock() != Blocks.ANCIENT_DEBRIS) {
            ziel = null;
        }
        if (ziel == null) {
            ziel = debrisInDerNaehe(mc, player, mod);
            zielSeit = tick;
        }
        // Unerreichbares Ziel nach 15 Sekunden aufgeben.
        //
        // Debris hoch an der Decke oder hinter einer Lavawand kann man nicht
        // immer erreichen. Ohne diese Grenze versucht es der Bot bis zum
        // Verhungern -- und graebt in der Zeit keinen Meter Stollen.
        if (ziel != null && tick - zielSeit > 300) {
            melde(mc, "Komme an das Debris nicht heran -- grabe weiter.");
            ziel = null;
            schlagenZuruecksetzen();
            return;
        }
        if (ziel != null) {
            zustand = Zustand.GRAEBT;
            waehleSpitzhacke(player);
            double d = Math.sqrt(player.distanceToSqr(
                    ziel.getX() + 0.5, ziel.getY() + 0.5, ziel.getZ() + 0.5));

            // IN REICHWEITE WIRD IMMER ABGEBAUT, nie gelaufen.
            //
            // 4,5 statt 4,0: die Reichweite wird vom Auge aus gemessen,
            // nicht von den Fuessen. Steht das Debris direkt ueber dem Bot,
            // lag es knapp ausserhalb -- er lief vorwaerts, kam nie hin und
            // drehte sich dabei im Kreis.
            if (d <= 4.5) {
                blickeAuf(player, ziel);
                mc.options.keyUp.setDown(false);
                mc.options.keyJump.setDown(false);
                waehleSpitzhacke(player);
                mc.options.keyAttack.setDown(true);
                return;
            }

            // Zu weit weg. FRUEHER wurde hier nur gelaufen -- stand Stein
            // dazwischen, lief der Bot dagegen und kam nie an. Jetzt wird der
            // Weg freigeraeumt: liegt ein Block zwischen Spieler und Ziel,
            // wird DER abgebaut, nicht das Ziel.
            BlockPos imWeg = naechsterBlockRichtung(mc, player, ziel);
            if (imWeg != null) {
                blickeAuf(player, imWeg);
                mc.options.keyUp.setDown(false);
                mc.options.keyAttack.setDown(true);
            } else {
                blickeAuf(player, ziel);
                mc.options.keyAttack.setDown(false);
                // ERST ausrichten, DANN laufen.
                //
                // Vorher lief er sofort los, waehrend sich der Blick noch
                // drehte -- und damit in die alte Richtung. Bei 12 Grad je
                // Tick sind das bis zu anderthalb Sekunden Irrweg.
                //
                // Erst ab etwa 30 Grad Restwinkel geht es vorwaerts.
                boolean ausgerichtet = Math.abs(restWinkel(player)) < 30f;
                mc.options.keyUp.setDown(ausgerichtet && !lavaImWeg(mc, player, mod));
            }
            return;
        }

        // --- 2. Auf die richtige Hoehe ------------------------------------
        int zielY = mod.mineY.getInt();
        int istY = (int) Math.floor(player.getY());
        if (istY > zielY + 1) {
            // Nach unten graben, aber NIE senkrecht unter sich: darunter kann
            // Lava liegen, und dann faellt man hinein. Stattdessen schraeg:
            // den Block vor den Fuessen abbauen und nachruecken.
            zustand = Zustand.GRAEBT;
            grabeRichtung(mc, player, mod, -1);
            return;
        }
        if (istY < zielY - 1) {
            zustand = Zustand.GEHT;
            grabeRichtung(mc, player, mod, +1);
            return;
        }

        // --- 3. Stollen weitergraben --------------------------------------
        zustand = Zustand.GRAEBT;
        // Nach einem Ausbruch kurz abwaerts, um den alten Gang zu verlassen.
        grabeRichtung(mc, player, mod, wechseltEbene() ? -1 : 0);
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
        BlockPos ueberKopf = new BlockPos(vx, py + dy + 2, vz);
        if (mod.avoidLava.get() && mc.level.getBlockState(ueberKopf).getBlock() == Blocks.LAVA) {
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

        // Zwei Bloecke hoch graben, damit man durchpasst: erst Kopfhoehe,
        // dann Fusshoehe. Ein ein Block hoher Gang laesst sich nicht begehen.
        BlockPos kopf = new BlockPos(vx, py + dy + 1, vz);
        BlockPos fuss = new BlockPos(vx, py + dy, vz);

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
            zielBlock = fest(mc, kopf) ? kopf : (fest(mc, fuss) ? fuss : null);
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
            mc.options.keyAttack.setDown(true);
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
            if (!fest(mc, boden) && !fest(mc, new BlockPos(vx, py + dy - 2, vz))) {
                mc.options.keyUp.setDown(false);
                if (tick - letzteDrehung > 20) {
                    letzteDrehung = tick;
                    richtung = neueRichtung(mc, richtung);
                    melde(mc, "Abgrund voraus -- neue Richtung.");
                }
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
            boolean stufe = fest(mc, new BlockPos(vx, py + dy, vz))
                    && !fest(mc, new BlockPos(vx, py + dy + 1, vz))
                    && !fest(mc, new BlockPos(vx, py + dy + 2, vz));
            mc.options.keyJump.setDown(stufe);
        }
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
                    if (mc.level.getBlockState(new BlockPos(x + dx, y + dy, z + dz))
                            .getBlock() == Blocks.LAVA) return true;
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
        int r = mod.pickupRange.getInt();
        BlockPos mitte = player.blockPosition();
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        BlockPos beste = null;
        double besteD = Double.MAX_VALUE;
        for (int dx = -r; dx <= r; dx++) {
            for (int dy = -r; dy <= r; dy++) {
                for (int dz = -r; dz <= r; dz++) {
                    p.set(mitte.getX() + dx, mitte.getY() + dy, mitte.getZ() + dz);
                    if (mc.level.getBlockState(p).getBlock() != Blocks.ANCIENT_DEBRIS) continue;
                    // Abstand selbst rechnen statt distSqr, und die Position
                    // neu bauen statt immutable(): beides benutzt nur
                    // Methoden, die anderswo im Projekt vorkommen.
                    double ddx = p.getX() - mitte.getX();
                    double ddy = p.getY() - mitte.getY();
                    double ddz = p.getZ() - mitte.getZ();
                    double d = ddx * ddx + ddy * ddy + ddz * ddz;
                    if (d < besteD) {
                        besteD = d;
                        beste = new BlockPos(p.getX(), p.getY(), p.getZ());
                    }
                }
            }
        }
        return beste;
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
        int slot = findeHotbar(player, Items.NETHERITE_PICKAXE);
        if (slot < 0) slot = findeHotbar(player, Items.DIAMOND_PICKAXE);
        if (slot >= 0 && player.getInventory().getSelectedSlot() != slot) {
            player.getInventory().setSelectedSlot(slot);
        }
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

    private static int findeEssen(LocalPlayer player) {
        for (net.minecraft.world.item.Item item : ESSEN) {
            int slot = findeHotbar(player, item);
            if (slot >= 0) return slot;
        }
        for (net.minecraft.world.item.Item item : ESSEN) {
            int slot = findeSlot(player, item);
            if (slot >= 0) return slot;
        }
        return -1;
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
        if (zustand != Zustand.GRAEBT && zustand != Zustand.GEHT) {
            stehtSeit = 0;
            return;
        }
        if (bewegt > 0.0004) {          // rund 2 cm je Tick
            stehtSeit = 0;
            return;
        }

        // ABBAUEN IST FORTSCHRITT, auch wenn er sich dabei nicht bewegt.
        //
        // Das war die Endlosschleife: beim Graben steht er still, die
        // Feststeck-Erkennung sprang an und drueckte die Sprungtaste -- ein
        // Sprung bricht den Schlag ab, der Block faengt von vorne an, er
        // bewegt sich wieder nicht, und so weiter. Besonders bei Kies, der
        // ohnehin staendig nachrutscht.
        //
        // Solange er auf einen Block schlaegt und die Zeitgrenze nicht
        // gerissen ist, gilt das als Arbeit.
        if (mc.options.keyAttack.isDown() && schlaegtAuf != null
                && tick - schlaegtSeit <= 160) {
            stehtSeit = 0;
            return;
        }

        stehtSeit++;

        // KIES ZUERST -- und sofort, nicht erst nach drei Sekunden.
        //
        // Steht der Bot im Kies, hilft kein Springen: der Block ist um ihn
        // herum. Er muss ihn abbauen, und zwar auf Kopfhoehe, damit der Weg
        // nach oben frei wird. Je frueher, desto weniger rutscht nach.
        if (imKiesStecken(mc, player) && stehtSeit >= 5) {
            if (stehtSeit == 5) melde(mc, "Im Kies -- grabe mich frei.");
            willBlicken(player.getYRot(), 0f);
            waehleSpitzhacke(player);
            mc.options.keyUp.setDown(false);
            // NICHT springen waehrend des Abbauens -- der Sprung bricht den
            // Schlag ab und der Block faengt von vorne an. Erst graben, das
            // Hochkommen ergibt sich danach von selbst.
            mc.options.keyJump.setDown(false);
            mc.options.keyAttack.setDown(true);
            return;
        }

        // Nach einer halben Sekunde: springen. Loest Stufen und einen Block
        // vor den Fuessen.
        if (stehtSeit == 10) {
            mc.options.keyJump.setDown(true);
            return;
        }
        if (stehtSeit == 14) {
            mc.options.keyJump.setDown(false);
            return;
        }

        // Nach einer Sekunde: FREIGRABEN, und zwar dorthin, wo er hin will.
        //
        // Vorher wurde nur nach oben gegraben. Steckt er aber zwischen zwei
        // Bloecken -- der haeufigste Fall -- liegt das Hindernis VOR ihm,
        // nicht ueber ihm. Dann half nur, von Hand einen Block abzubauen.
        //
        // Jetzt wird der Reihe nach probiert: vorne Fusshoehe, vorne
        // Kopfhoehe, dann oben. Der erste feste Block gewinnt.
        if (stehtSeit > 20 && stehtSeit % 5 == 0) {
            if (richtung == null) richtung = himmelsrichtung(player.getYRot());
            int px = (int) Math.floor(player.getX());
            int py = (int) Math.floor(player.getY());
            int pz = (int) Math.floor(player.getZ());
            BlockPos[] versuche = {
                new BlockPos(px + richtung[0], py, pz + richtung[1]),       // vorne unten
                new BlockPos(px + richtung[0], py + 1, pz + richtung[1]),   // vorne oben
                new BlockPos(px, py + 2, pz)                                // ueber mir
            };
            for (BlockPos z : versuche) {
                if (!fest(mc, z)) continue;
                if (stehtSeit == 25) melde(mc, "Steckengeblieben -- grabe mich frei.");
                blickeAuf(player, z);
                waehleSpitzhacke(player);
                mc.options.keyUp.setDown(false);
                mc.options.keyJump.setDown(false);
                mc.options.keyAttack.setDown(true);
                return;
            }
        }

        // Nach vier Sekunden: Richtung wechseln. Manchmal ist der Weg
        // schlicht versperrt und ein anderer Stollen ist die Loesung.
        if (stehtSeit == 80 && richtung != null) {
            richtung = neueRichtung(mc, richtung);
            melde(mc, "Komme nicht weiter -- neue Richtung.");
        }

        // Nach zehn Sekunden ohne Bewegung ist etwas grundlegend falsch.
        if (stehtSeit > 200) {
            melde(mc, "Komme nicht frei -- Bot stoppt.");
            NetheriteFarmerModule m = modul();
            if (m != null) m.setEnabled(false);
            stop();
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
            // Wasser gibt es im Nether nicht; alles Fluessige ist hier Lava.
            return mc.level.getBlockState(p).getBlock() == Blocks.LAVA
                    || fs.getType().toString().toLowerCase().contains("lava");
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

        if (fest(mc, kopf) || fest(mc, fuss)) {
            // Weg zu: freigraben. NICHT laufen, sonst drueckt er nur gegen
            // den Block und der Abbau bricht ab.
            BlockPos z = fest(mc, kopf) ? kopf : fuss;
            blickeAuf(player, z);
            waehleSpitzhacke(player);
            mc.options.keyAttack.setDown(true);
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
        // Keiner frei: den letzten nehmen, aber niemals den mit der
        // Spitzhacke -- ohne sie steht der Bot still.
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

        // 2) Verbrauchsgueter in die Hotbar holen, wenn dort keine mehr sind.
        net.minecraft.world.item.Item[] wichtig = {
            Items.EXPERIENCE_BOTTLE, Items.GOLDEN_APPLE
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

        // 3) Essen -- irgendeines aus der Liste.
        if (findeEssenHotbar(player) < 0) {
            for (net.minecraft.world.item.Item item : ESSEN) {
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

    /** Essbares NUR in der Hotbar. */
    private static int findeEssenHotbar(LocalPlayer player) {
        for (net.minecraft.world.item.Item item : ESSEN) {
            int slot = findeHotbar(player, item);
            if (slot >= 0) return slot;
        }
        return -1;
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
    private static int drehFensterAb = 0;
    private static int ebenenWechsel = 0;

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

        if (drehungenZuletzt <= 2) {
            return drehe(alt);                  // normale Vierteldrehung
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

}
