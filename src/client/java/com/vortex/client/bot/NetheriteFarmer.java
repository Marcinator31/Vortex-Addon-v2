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

    /** Was der Bot gerade tut -- fuer Anzeige und Protokoll. */
    public enum Zustand { AUS, SUCHT, GEHT, GRAEBT, ISST, REPARIERT, FERTIG }

    // --- Feste Werte ------------------------------------------------------

    /** Ab so wenig Hunger wird gegessen (halbe Balken, 20 = voll). */
    private static final int ESSEN_UNTER = 16;
    /** Ab so wenig Leben wird ein goldener Apfel gegessen. */
    private static final float APFEL_UNTER = 12f;
    /** Unter dieser Haltbarkeit wird repariert. */
    private static final double REPARIEREN_UNTER = 0.90;
    /** Abstand zwischen XP-Flaschen in Ticks (eine halbe Sekunde). */
    private static final int FLASCHEN_ABSTAND = 10;
    /** Wie lange der Flaschen-Platz gehalten wird. */
    private static final int FLASCHEN_HALTEN = 10;

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
        erholungen = 0;
        letzterFund = 0;
        bautGerade = false;
        aktuell = null;
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

            // Spieler in der Naehe -> /afk
            if (mod.afkOnPlayer.get() && fremderSpielerNah(mc, mod)) {
                if (!gemeldet) {
                    gemeldet = true;
                    melde(mc, "Spieler in der Naehe -- gehe auf /afk.");
                    tastenLos(mc);
                    sendeBefehl(mc, "afk");
                }
                return;
            }
            // Vorrat leer -> /afk
            String fehlt = wasFehlt(player, mod);
            if (fehlt != null) {
                if (!gemeldet) {
                    gemeldet = true;
                    zustand = Zustand.FERTIG;
                    melde(mc, "Fertig: " + fehlt);
                    tastenLos(mc);
                    if (mod.afkWhenOut.get()) sendeBefehl(mc, "afk");
                }
                return;
            }
            gemeldet = false;

            // Ab hier entscheidet der Schiedsrichter. try/finally sorgt
            // dafuer, dass Blick, Feststeck-Pruefung und Sprungsperre IMMER
            // laufen, egal wie ein Zweig endet.
            try {
                entscheiden(mc, player, mod);
            } finally {
                pruefeFeststecken(mc, player);
                wachhund(mc, player);
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
        int r = mod.debrisRange.getInt();
        BlockPos mitte = player.blockPosition();
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        BlockPos beste = null;
        double besteD = Double.MAX_VALUE;
        for (int dx = -r; dx <= r; dx++) {
            for (int dy = -r; dy <= r; dy++) {
                for (int dz = -r; dz <= r; dz++) {
                    p.set(mitte.getX() + dx, mitte.getY() + dy, mitte.getZ() + dz);
                    if (mc.level.getBlockState(p).getBlock() != Blocks.ANCIENT_DEBRIS) continue;
                    if (istGesperrt(p)) continue;        // schon aufgegeben
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
                if (istGesperrt(new BlockPos((int) Math.floor(e.getX()),
                        (int) Math.floor(e.getY()), (int) Math.floor(e.getZ())))) continue;
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


        // Nach einer halben Sekunde: springen. Loest Stufen und einen Block
        // vor den Fuessen.
        if (stehtSeit == 4) {
            mc.options.keyJump.setDown(true);
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
            ziel = null;
            schlaegtAuf = null;
            fluchtWeg = null;
            if (richtung != null) richtung = new int[]{richtung[1], -richtung[0]};
            ebenenWechsel = tick;
            melde(mc, "Haenge fest -- setze mich neu auf (" + erholungen + ").");
            if (erholungen >= 5) {
                        melde(mc, "Komme hier nicht weiter -- /afk.");
                sendeBefehl(mc, "afk");
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

    private static void wachhund(Minecraft mc, LocalPlayer player) {
        if (letzterFund == 0) letzterFund = tick;
        // Zwei Minuten ohne Fund.
        if (tick - letzterFund < 2400) return;
        letzterFund = tick;

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
     * Baut einen Block unter dem Bot, waehrend er springt.
     *
     * @return true, wenn gerade gebaut wird -- dann sonst nichts tun.
     */
    private static boolean baueHoch(Minecraft mc, LocalPlayer player) {
        int px = (int) Math.floor(player.getX());
        int py = (int) Math.floor(player.getY());
        int pz = (int) Math.floor(player.getZ());

        // 1) DECKE ZUERST WEGNEHMEN.
        //
        // Sitzt ein Block ueber dem Kopf, stoesst der Bot beim Springen
        // dagegen und kommt nie hoeher -- er baute endlos weiter, ohne dass
        // sich etwas bewegte. Also erst Platz schaffen.
        BlockPos ueberKopf = new BlockPos(px, py + 2, pz);
        if (fest(mc, ueberKopf)) {
            bautGerade = true;
            waehleSpitzhacke(player);
            blickeAuf(player, ueberKopf);
            mc.options.keyJump.setDown(false);
            mc.options.keyUp.setDown(false);
            mc.options.keyAttack.setDown(true);
            return true;
        }

        // 2) Steckt er seitlich fest, erst dort freiraeumen.
        //
        // Beim Hochbauen in einer engen Spalte klemmt er sonst zwischen zwei
        // Bloecken und springt nur noch auf der Stelle.
        if (stehtSeit > 6) {
            int[][] rund = {{1,0},{-1,0},{0,1},{0,-1}};
            for (int[] r : rund) {
                BlockPos q = new BlockPos(px + r[0], py + 1, pz + r[1]);
                if (!fest(mc, q)) continue;
                bautGerade = true;
                waehleSpitzhacke(player);
                blickeAuf(player, q);
                mc.options.keyJump.setDown(false);
                mc.options.keyAttack.setDown(true);
                return true;
            }
        }

        // 3) Block setzen.
        int slot = findeFueller(player);
        if (slot < 0) {
            bautGerade = false;
            return false;
        }
        bautGerade = true;

        if (tick - letzterBau < 8) {
            mc.options.keyJump.setDown(true);
            return true;
        }

        player.getInventory().setSelectedSlot(slot);
        willBlicken(player.getYRot(), 90f);      // gerade nach unten
        mc.options.keyUp.setDown(false);
        mc.options.keyAttack.setDown(false);
        mc.options.keyJump.setDown(true);

        // Erst setzen, wenn er auch wirklich nach unten sieht.
        if (player.getXRot() < 80f) return true;

        try {
            var hit = mc.hitResult;
            if (hit instanceof net.minecraft.world.phys.BlockHitResult bhr) {
                mc.gameMode.useItemOn(player, net.minecraft.world.InteractionHand.MAIN_HAND, bhr);
                letzterBau = tick;
            }
        } catch (Throwable pvpErr) {
            com.vortex.client.core.Errors.report("NetheriteFarmer.baueHoch", pvpErr);
            bautGerade = false;
            return false;
        }
        return true;
    }


    // Das Wegwerfen aus dem Inventar wurde entfernt: es hat mehr Schaden
    // angerichtet als genutzt.



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

    /** Fehlt etwas Wichtiges in der Hotbar, das im Inventar liegt? */
    private static boolean nachfuellenNoetig(LocalPlayer player) {
        if (findeHotbar(player, Items.EXPERIENCE_BOTTLE) < 0
                && findeSlot(player, Items.EXPERIENCE_BOTTLE) >= 0) return true;
        if (findeHotbar(player, Items.GOLDEN_APPLE) < 0
                && findeSlot(player, Items.GOLDEN_APPLE) >= 0) return true;
        return findeEssenHotbar(player) < 0 && findeEssen(player) >= 0;
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
                mc.options.keyAttack.setDown(true);
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
            blickeAuf(player, z);
            waehleSpitzhacke(player);
            mc.options.keyUp.setDown(false);
            mc.options.keyJump.setDown(false);
            mc.options.keyAttack.setDown(true);
            return;
        }
        // Nichts Festes in Reichweite: dann hilft ein Sprung.
        mc.options.keyJump.setDown(true);
    }

    /** Holt einen herabgefallenen Brocken. */
    private static void holeBrocken(Minecraft mc, LocalPlayer player,
                                    NetheriteFarmerModule mod,
                                    net.minecraft.world.entity.item.ItemEntity brocken) {
        if (brocken == null) return;
        BlockPos bp = new BlockPos((int) Math.floor(brocken.getX()),
                                   (int) Math.floor(brocken.getY()),
                                   (int) Math.floor(brocken.getZ()));
        if (mod.avoidLava.get() && lavaAufDemWeg(mc, player, bp)) {
            sperre(mc, bp, "Brocken liegt an Lava -- lasse ihn liegen.");
            return;
        }
        BlockPos imWeg = blockDirektDavor(mc, player, bp);
        if (imWeg != null) {
            blickeAuf(player, imWeg);
            waehleSpitzhacke(player);
            mc.options.keyUp.setDown(false);
            mc.options.keyAttack.setDown(true);
            return;
        }
        fundGemeldet(mc);
        blickeAufPunkt(player, brocken.getX(), brocken.getY(), brocken.getZ());
        mc.options.keyUp.setDown(Math.abs(restWinkel(player)) < 40f);
    }

    /** Geht zum Debris und baut es ab. */
    private static void holeDebris(Minecraft mc, LocalPlayer player,
                                   NetheriteFarmerModule mod) {
        if (ziel == null) return;
        double d = Math.sqrt(player.distanceToSqr(
                ziel.getX() + 0.5, ziel.getY() + 0.5, ziel.getZ() + 0.5));

        if (d <= 4.5) {
            blickeAuf(player, ziel);
            waehleSpitzhacke(player);
            mc.options.keyUp.setDown(false);
            mc.options.keyJump.setDown(false);
            mc.options.keyAttack.setDown(true);
            return;
        }
        if (mod.avoidLava.get() && lavaAufDemWeg(mc, player, ziel)) {
            sperre(mc, ziel, "Lava vor einem Debris -- lasse es liegen.");
            return;
        }
        BlockPos imWeg = blockDirektDavor(mc, player, ziel);
        if (imWeg == null) imWeg = naechsterBlockRichtung(mc, player, ziel);
        if (imWeg != null) {
            blickeAuf(player, imWeg);
            waehleSpitzhacke(player);
            mc.options.keyUp.setDown(false);
            mc.options.keyAttack.setDown(true);
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
            willBlicken(richtungZuYaw(richtung), 0f);
            mc.options.keyUp.setDown(Math.abs(restWinkel(player)) < 30f);
            return;
        }
        int zielY = mod.mineY.getInt();
        int istY = (int) Math.floor(player.getY());
        if (istY > zielY + 1) { grabeRichtung(mc, player, mod, -1); return; }
        if (istY < zielY - 1) { grabeRichtung(mc, player, mod, +1); return; }
        grabeRichtung(mc, player, mod, wechseltEbene() ? -1 : 0);
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

    private static void entscheiden(Minecraft mc, LocalPlayer player,
                                    NetheriteFarmerModule mod) {
        try {
            // --- Laufende Aktion aufrechterhalten -------------------------
            //
            // Ohne diesen Aufruf haelt keine Aktion ihren Hotbar-Platz und
            // die Benutzen-Taste wird nie losgelassen -- der Bot wuerde
            // XP-Flaschen werfen, bis keine mehr da ist.
            boolean aktionLief = aktionLaeuft(mc, player);

            // --- Lage EINMAL erfassen -------------------------------------
            //
            // Vorher fragte jeder Zweig selbst nach Lava, und zwei Zweige
            // kamen im selben Tick zu unterschiedlichen Ergebnissen.
            boolean inLava = player.isInLava();
            BlockPos lava = mod.avoidLava.get()
                    ? erreichbareLava(mc, player, inLava ? 4 : 3) : null;
            float leben = player.getHealth();
            int hunger = player.getFoodData().getFoodLevel();
            boolean stecktFest = stehtSeit > 6;
            boolean aktionLaeuftNoch = aktionLief;

            // Ziel pruefen und gegebenenfalls neu suchen
            if (ziel != null
                    && mc.level.getBlockState(ziel).getBlock() != Blocks.ANCIENT_DEBRIS) {
                ziel = null;
                bautGerade = false;
            }
            if (ziel == null) {
                ziel = debrisInDerNaehe(mc, player, mod);
                zielSeit = tick;
                zielBesteDistanz = Double.MAX_VALUE;
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
                // Gerade heraus: Richtung umkehren und kurz weg, sonst graebt
                // er im naechsten Tick wieder hinein.
                drinSeit = 0;
                nahSeit = 0;
                fluchtWeg = null;
                if (richtung != null) richtung = new int[]{-richtung[0], -richtung[1]};
                lavaRuheBis = tick + 60;
                ziel = null;
                melde(mc, "Aus der Lava heraus -- grabe woanders weiter.");
            }

            // Goldener Apfel bei kritischem Leben -- schlaegt sogar die
            // Flucht bei blosser Lavanaehe, denn Verbrennen geht schneller
            // als Ausweichen.
            if (leben <= APFEL_UNTER && findeHotbar(player, Items.GOLDEN_APPLE) >= 0) {
                punkte.put(Verhalten.ESSEN, 950);
            } else if (hunger <= ESSEN_UNTER && findeEssenHotbar(player) >= 0) {
                // Essen MUSS ueber dem Graben liegen, sonst verhungert er
                // mit vollem Rucksack.
                punkte.put(Verhalten.ESSEN, 550);
            }

            if (fehltTotem(player)) punkte.put(Verhalten.TOTEM, 700);
            if (stecktFest) punkte.put(Verhalten.FREIGRABEN, 600);

            // Nachfuellen ist DRINGEND, wenn das Fehlende gleich gebraucht
            // wird -- sonst kann er nicht essen, obwohl Essen im Rucksack
            // liegt.
            if (nachfuellenNoetig(player)) {
                boolean dringend = (hunger <= ESSEN_UNTER && findeEssenHotbar(player) < 0)
                        || (leben <= APFEL_UNTER && findeHotbar(player, Items.GOLDEN_APPLE) < 0);
                punkte.put(Verhalten.NACHFUELLEN, dringend ? 560 : 350);
            }

            if (brocken != null) punkte.put(Verhalten.BROCKEN, 500);
            if (ziel != null) {
                int hoehe = ziel.getY() - (int) Math.floor(player.getY());
                double d = Math.sqrt(player.distanceToSqr(
                        ziel.getX() + 0.5, ziel.getY() + 0.5, ziel.getZ() + 0.5));
                boolean hoch = bautGerade ? (hoehe >= 2 && d > 4.0) : (hoehe >= 3);
                punkte.put(hoch ? Verhalten.HOCHBAUEN : Verhalten.DEBRIS, 450);
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
                    && beste != Verhalten.FREIGRABEN) {
                beste = aktuell;
            }
            // Eine laufende Essensaktion laeuft immer zu Ende -- ausser bei
            // Lava, dort zaehlt nur Herauskommen.
            if (aktionLaeuftNoch && aktionHalten && beste != Verhalten.FLIEHEN) {
                beste = Verhalten.ESSEN;
            }

            if (beste != aktuell) {
                aktuell = beste;
                aktuellSeit = tick;
                bautGerade = (beste == Verhalten.HOCHBAUEN);
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
                    int slot = (player.getHealth() <= APFEL_UNTER)
                            ? findeHotbar(player, Items.GOLDEN_APPLE) : -1;
                    if (slot < 0) slot = findeEssenHotbar(player);
                    if (slot >= 0) starteAktion(mc, player, slot, 32, true);
                }
                break;
            }
            case TOTEM:       legeTotem(mc, player); break;
            case NACHFUELLEN: nachfuellen(mc, player, mod); break;
            case REPARIEREN:  zustand = Zustand.REPARIERT; reparieren(mc, player, mod); break;
            case FREIGRABEN:  zustand = Zustand.GRAEBT; grabeFrei(mc, player); break;
            case HOCHBAUEN:
                zustand = Zustand.GEHT;
                if (!baueHoch(mc, player)) {
                    bautGerade = false;
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
     * Geprueft wird das GANZE Inventar, nicht nur die Hotbar -- das
     * Nachfuellen holt es von dort herauf.
     */
    private static String wasFehlt(LocalPlayer player, NetheriteFarmerModule mod) {
        if (findeSlot(player, Items.NETHERITE_PICKAXE) < 0
                && findeSlot(player, Items.DIAMOND_PICKAXE) < 0) {
            return "keine Spitzhacke mehr";
        }
        if (findeEssen(player) < 0) return "kein Essen mehr";
        if (findeSlot(player, Items.TOTEM_OF_UNDYING) < 0
                && (player.getOffhandItem() == null
                    || player.getOffhandItem().isEmpty()
                    || player.getOffhandItem().getItem() != Items.TOTEM_OF_UNDYING)) {
            return "kein Totem mehr";
        }
        if (findeSlot(player, Items.EXPERIENCE_BOTTLE) < 0) {
            return "keine XP-Flaschen mehr";
        }
        return null;
    }

    /** Ist die Spitzhacke unter der Reparaturgrenze? */
    private static boolean brauchtReparatur(LocalPlayer player,
                                            NetheriteFarmerModule mod) {
        ItemStack hand = player.getMainHandItem();
        if (hand == null || hand.isEmpty() || !hand.isDamageableItem()) {
            // Nicht in der Hand? Dann die Hotbar-Spitzhacke pruefen.
            int slot = findeHotbar(player, Items.NETHERITE_PICKAXE);
            if (slot < 0) slot = findeHotbar(player, Items.DIAMOND_PICKAXE);
            if (slot < 0) return false;
            hand = player.getInventory().getItem(slot);
        }
        return beschaedigt(hand, REPARIEREN_UNTER);
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
        if (!starteAktion(mc, player, slot, FLASCHEN_HALTEN, false)) return;
        letzteFlasche = tick;
    }

}
