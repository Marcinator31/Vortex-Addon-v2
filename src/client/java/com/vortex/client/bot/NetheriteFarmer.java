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
        for (ItemStack st : player.getArmorSlots()) {
            if (beschaedigt(st, mod.armorBelow.get() / 100.0)) return true;
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

    private static void abbauen(Minecraft mc, LocalPlayer player,
                                NetheriteFarmerModule mod) {
        // Ziel pruefen: abgebaut oder zu weit weg -> neues suchen.
        if (ziel != null) {
            if (mc.level.getBlockState(ziel).getBlock() != Blocks.ANCIENT_DEBRIS) {
                ziel = null;
            }
        }
        if (ziel == null) {
            zustand = Zustand.SUCHT;
            ziel = sucheDebris(mc, player, mod);
            if (ziel == null) {
                tastenLos(mc);
                return;
            }
        }

        // Hinsehen -- danach entscheidet die Entfernung, ob gegraben oder
        // gegangen wird.
        blickeAuf(player, ziel);

        double dist = Math.sqrt(player.distanceToSqr(
                ziel.getX() + 0.5, ziel.getY() + 0.5, ziel.getZ() + 0.5));

        if (dist <= 4.0) {
            zustand = Zustand.GRAEBT;
            waehleSpitzhacke(player);
            mc.options.keyUp.setDown(false);
            mc.options.keyAttack.setDown(true);
        } else {
            zustand = Zustand.GEHT;
            mc.options.keyAttack.setDown(false);
            // Lava im Weg: stehenbleiben statt hineinzulaufen. Ein Bot, der
            // in Lava rennt, verliert alles -- lieber ein Ziel aufgeben.
            if (mod.avoidLava.get() && lavaVoraus(mc, player)) {
                mc.options.keyUp.setDown(false);
                ziel = null;
                return;
            }
            mc.options.keyUp.setDown(true);
        }
    }

    /** Sucht den naechsten Ancient-Debris-Block in Reichweite. */
    private static BlockPos sucheDebris(Minecraft mc, LocalPlayer player,
                                        NetheriteFarmerModule mod) {
        int r = mod.searchRange.getInt();
        int maxY = mod.maxY.getInt();
        BlockPos mitte = player.blockPosition();
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        BlockPos besteStelle = null;
        double besteDistanz = Double.MAX_VALUE;

        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                for (int dy = -r; dy <= r; dy++) {
                    int y = mitte.getY() + dy;
                    if (y > maxY) continue;
                    p.set(mitte.getX() + dx, y, mitte.getZ() + dz);
                    if (mc.level.getBlockState(p).getBlock() != Blocks.ANCIENT_DEBRIS) continue;
                    double d = p.distSqr(mitte);
                    if (d < besteDistanz) {
                        besteDistanz = d;
                        besteStelle = p.immutable();
                    }
                }
            }
        }
        return besteStelle;
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
        double dy = pos.getY() + 0.5 - (player.getY() + player.getEyeHeight());
        double dz = pos.getZ() + 0.5 - player.getZ();
        double flach = Math.sqrt(dx * dx + dz * dz);
        player.setYRot((float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0));
        player.setXRot((float) -Math.toDegrees(Math.atan2(dy, flach)));
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
}
