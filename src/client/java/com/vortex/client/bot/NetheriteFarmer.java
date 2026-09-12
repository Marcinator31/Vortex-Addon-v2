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
            }
        } catch (Throwable ignored) { }
        zustand = Zustand.AUS;
        ziel = null;
        richtung = null;
        gemeldet = false;
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

            // --- 2. Ueberleben -------------------------------------------
            if (ueberleben(mc, player, mod)) return;

            // --- 3. Reparieren -------------------------------------------
            if (reparieren(mc, player, mod)) return;

            // --- 4. Abbauen ----------------------------------------------
            abbauen(mc, player, mod);

            // Blick zuletzt bewegen: bis hier haben die Teilaufgaben nur
            // ihren Wunsch hinterlegt.
            wendeBlick(player, mod);

        } catch (Throwable pvpErr) {
            com.vortex.client.core.Errors.report("NetheriteFarmer", pvpErr);
            stop();
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
        // Goldener Apfel bei Schaden -- auch mit vollem Hunger. In Lava ist
        // das der einzige Weg, der wirklich hilft.
        if (player.getHealth() <= mod.gappleBelow.get()) {
            int slot = findeSlot(player, Items.GOLDEN_APPLE);
            if (slot < 0) slot = findeSlot(player, Items.ENCHANTED_GOLDEN_APPLE);
            if (slot >= 0 && benutze(mc, player, slot)) {
                zustand = Zustand.ISST;
                return true;
            }
        }
        // Normales Essen bei Hunger.
        if (player.getFoodData().getFoodLevel() <= mod.eatBelow.get()) {
            int slot = findeEssen(player);
            if (slot >= 0 && benutze(mc, player, slot)) {
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
        if (!benutze(mc, player, slot)) return false;

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
        }
        if (ziel != null) {
            zustand = Zustand.GRAEBT;
            waehleSpitzhacke(player);
            double d = Math.sqrt(player.distanceToSqr(
                    ziel.getX() + 0.5, ziel.getY() + 0.5, ziel.getZ() + 0.5));

            if (d <= 4.0) {
                // In Reichweite: direkt draufschlagen.
                blickeAuf(player, ziel);
                mc.options.keyUp.setDown(false);
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
                mc.options.keyUp.setDown(!lavaImWeg(mc, player, mod));
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
        grabeRichtung(mc, player, mod, 0);
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
            melde(mc, "Lava voraus -- Richtung gewechselt.");
            richtung = drehe(richtung);
            return;
        }

        // Zwei Bloecke hoch graben, damit man durchpasst: erst Kopfhoehe,
        // dann Fusshoehe. Ein ein Block hoher Gang laesst sich nicht begehen.
        BlockPos kopf = new BlockPos(vx, py + dy + 1, vz);
        BlockPos fuss = new BlockPos(vx, py + dy, vz);
        BlockPos zielBlock = fest(mc, kopf) ? kopf : (fest(mc, fuss) ? fuss : null);

        waehleSpitzhacke(player);
        if (zielBlock != null) {
            blickeAuf(player, zielBlock);
            mc.options.keyUp.setDown(false);
            mc.options.keyAttack.setDown(true);
        } else {
            // Frei: vorruecken.
            mc.options.keyAttack.setDown(false);
            willBlicken(richtungZuYaw(richtung), dy < 0 ? 30f : (dy > 0 ? -30f : 0f));
            mc.options.keyUp.setDown(true);
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
        willBlicken((float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0),
                    (float) -Math.toDegrees(Math.atan2(dy, flach)));
    }

    private static void waehleSpitzhacke(LocalPlayer player) {
        int slot = findeHotbar(player, Items.NETHERITE_PICKAXE);
        if (slot < 0) slot = findeHotbar(player, Items.DIAMOND_PICKAXE);
        if (slot >= 0 && player.getInventory().getSelectedSlot() != slot) {
            player.getInventory().setSelectedSlot(slot);
        }
    }

    /** Gegenstand aus der Hotbar auswaehlen und benutzen. */
    private static boolean benutze(Minecraft mc, LocalPlayer player, int slot) {
        if (slot > 8) return false;   // nur aus der Hotbar
        if (player.getInventory().getSelectedSlot() != slot) {
            player.getInventory().setSelectedSlot(slot);
        }
        mc.options.keyAttack.setDown(false);
        mc.options.keyUse.setDown(true);
        return true;
    }

    private static void tastenLos(Minecraft mc) {
        mc.options.keyAttack.setDown(false);
        mc.options.keyUse.setDown(false);
        mc.options.keyUp.setDown(false);
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
            for (int h = 0; h <= 1; h++) {
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

}
