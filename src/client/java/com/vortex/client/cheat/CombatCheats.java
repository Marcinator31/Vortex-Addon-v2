package com.vortex.client.cheat;

import com.vortex.client.module.ModuleManager;
import com.vortex.client.module.modules.AutoAnchorModule;
import com.vortex.client.module.modules.AutoArmorModule;
import com.vortex.client.module.modules.AutoEatModule;
import com.vortex.client.module.modules.AutoMendModule;
import com.vortex.client.module.modules.AutoToolModule;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Kampf- und Versorgungs-Cheats: Auto Armor, Auto Eat, Auto Tool, Auto Anchor,
 * Auto Mend.
 *
 * DIE HAND GEHOERT IMMER NUR EINEM. Essen, XP-Flaschen und Anker brauchen alle
 * den ausgewaehlten Hotbar-Platz. Wuerden sie gleichzeitig laufen, schalteten
 * sie im selben Tick hin und her und keiner kaeme zum Zug -- derselbe Fehler,
 * den der Netherite-Bot frueher hatte. Deshalb gibt es hier eine Sperre:
 * wer die Hand hat, behaelt sie, bis er fertig ist.
 *
 * Laeuft ein Bot (Netherite Farmer, AFK Bot), bleiben Essen und Mend still --
 * die Bots versorgen sich selbst und wuerden sonst gestoert.
 */
public final class CombatCheats {

    private CombatCheats() {}

    private static long tick = 0;

    /** Wer gerade die Hand hat: null, "eat", "mend". */
    private static String hand = null;

    public static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            tick++;
            LocalPlayer p = mc.player;
            if (p == null || mc.level == null || mc.gameMode == null) {
                essenAbbrechen(mc);
                hand = null;
                return;
            }
            try { autoArmor(mc, p); } catch (Throwable e) { fehler("AutoArmor", e); }
            try { autoEat(mc, p); } catch (Throwable e) { fehler("AutoEat", e); }
            try { autoMend(mc, p); } catch (Throwable e) { fehler("AutoMend", e); }
            try { autoTool(mc, p); } catch (Throwable e) { fehler("AutoTool", e); }
            try { autoAnchor(mc, p); } catch (Throwable e) { fehler("AutoAnchor", e); }
        });
    }

    private static void fehler(String wo, Throwable e) {
        com.vortex.client.core.Errors.report("CombatCheats." + wo, e);
    }

    private static <T extends com.vortex.client.module.Module> T an(Class<T> typ) {
        T m = ModuleManager.INSTANCE.get(typ);
        return (m != null && m.isEnabled()) ? m : null;
    }

    private static boolean botLaeuft() {
        var nf = ModuleManager.INSTANCE.get(com.vortex.client.module.modules.NetheriteFarmerModule.class);
        var afk = ModuleManager.INSTANCE.get(com.vortex.client.module.modules.AfkBotModule.class);
        return (nf != null && nf.isEnabled()) || (afk != null && afk.isEnabled());
    }

    /**
     * Isst gerade ein Farm-Bot selbst (BotEssen)? Dann pausiert Auto Eat --
     * es wuerde dem Bot den Hotbar-Platz beim Pflanzen/Faellen wegschnappen.
     */
    private static boolean farmerIsst() {
        var crop = ModuleManager.INSTANCE.get(com.vortex.client.module.modules.CropFarmerModule.class);
        var tree = ModuleManager.INSTANCE.get(com.vortex.client.module.modules.TreeFarmerModule.class);
        return (crop != null && crop.isEnabled() && crop.eat.get()) || (tree != null && tree.isEnabled() && tree.eat.get());
    }

    /** Naechster fremder Spieler (kein Freund) innerhalb von "weite", sonst null. */
    private static Player gegner(Minecraft mc, LocalPlayer p, double weite) {
        Player best = null;
        double bestD = weite * weite;
        for (Player o : mc.level.players()) {
            if (o == p || !o.isAlive() || o.isSpectator()) continue;
            if (com.vortex.client.core.Friends.schuetzt(o)) continue;
            double d = o.distanceToSqr(p);
            if (d <= bestD) {
                bestD = d;
                best = o;
            }
        }
        return best;
    }

    // ------------------------------------------------------------------
    // Auto Armor
    // ------------------------------------------------------------------

    private static long armorZuletzt = -100;

    private static final EquipmentSlot[] RUESTUNG = {
            EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET
    };

    /** Plaetze im Inventarfenster: Kopf 5, Brust 6, Beine 7, Fuesse 8. */
    private static int fensterPlatz(EquipmentSlot s) {
        switch (s) {
            case HEAD: return 5;
            case CHEST: return 6;
            case LEGS: return 7;
            case FEET: return 8;
            default: return -1;
        }
    }

    private static void autoArmor(Minecraft mc, LocalPlayer p) {
        AutoArmorModule m = an(AutoArmorModule.class);
        if (m == null) return;
        // Nur ohne fremdes Fenster -- in einer offenen Kiste gehoeren die
        // Klicks zur Kiste, nicht zum eigenen Inventar.
        if (mc.gui.screen() != null && !(mc.gui.screen() instanceof InventoryScreen)) return;
        if (!p.inventoryMenu.getCarried().isEmpty()) return;
        if (tick - armorZuletzt < m.delay.getInt()) return;

        // Nie mitten im Flug etwas an der Ruestung aendern.
        if (p.isFallFlying()) return;

        for (EquipmentSlot slot : RUESTUNG) {
            ItemStack an = p.getItemBySlot(slot);
            // Getragene Elytra bleibt, solange "Prefer Elytra" aus ist -- sonst
            // wuerde sie gegen jeden Brustpanzer getauscht. Wer sie anzieht,
            // will fliegen.
            if (slot == EquipmentSlot.CHEST && an.is(Items.ELYTRA) && !m.preferElytra.get()) continue;
            // Fluch der Bindung: laesst sich ohnehin nicht abnehmen
            if (!an.isEmpty() && hatVerzauberung(an, Enchantments.BINDING_CURSE)) continue;
            double jetzt = an.isEmpty() ? -1 : wert(p, an, slot, m);

            int bester = -1;
            double besterWert = jetzt;
            int n = Math.min(36, p.getInventory().getContainerSize());
            for (int i = 0; i < n; i++) {
                ItemStack st = p.getInventory().getItem(i);
                if (st == null || st.isEmpty() || !passt(st, slot)) continue;
                if (m.skipBinding.get() && hatVerzauberung(st, Enchantments.BINDING_CURSE)) continue;
                double w = wert(p, st, slot, m);
                if (w > besterWert + 0.01) {
                    besterWert = w;
                    bester = i;
                }
            }
            if (bester >= 0) {
                // Aufnehmen, auf den Ruestungsplatz legen, was dort lag zurueck.
                Inv.tausche(mc, Inv.fensterPlatz(bester), fensterPlatz(slot));
                armorZuletzt = tick;
                return;   // ein Teil pro Durchgang -- der Server kommt mit
            }
        }
    }

    /** Gehoert der Gegenstand auf diesen Ruestungsplatz? */
    private static boolean passt(ItemStack st, EquipmentSlot slot) {
        if (slot == EquipmentSlot.CHEST && st.is(Items.ELYTRA)) return true;
        switch (slot) {
            case HEAD: return st.is(ItemTags.HEAD_ARMOR);
            case CHEST: return st.is(ItemTags.CHEST_ARMOR);
            case LEGS: return st.is(ItemTags.LEG_ARMOR);
            case FEET: return st.is(ItemTags.FOOT_ARMOR);
            default: return false;
        }
    }

    /** Ruestung + Haerte + Schutz-Verzauberungen. Elytra nach Einstellung. */
    private static double wert(LocalPlayer p, ItemStack st, EquipmentSlot slot, AutoArmorModule m) {
        if (st.is(Items.ELYTRA)) return m.preferElytra.get() ? 1000 : 0.5;
        double w = 0;
        ItemAttributeModifiers mods = st.get(DataComponents.ATTRIBUTE_MODIFIERS);
        if (mods != null) {
            for (ItemAttributeModifiers.Entry e : mods.modifiers()) {
                if (e.attribute() == Attributes.ARMOR || e.attribute() == Attributes.ARMOR_TOUGHNESS) {
                    w += e.modifier().amount();
                }
            }
        }
        w += 0.6 * verzauberung(st, Enchantments.PROTECTION);
        w += 0.3 * verzauberung(st, Enchantments.BLAST_PROTECTION);
        w += 0.3 * verzauberung(st, Enchantments.FIRE_PROTECTION);
        w += 0.3 * verzauberung(st, Enchantments.PROJECTILE_PROTECTION);
        w += 0.1 * verzauberung(st, Enchantments.UNBREAKING);
        w += 0.2 * verzauberung(st, Enchantments.MENDING);
        // Fast kaputte Teile abwerten -- sonst traegt man ein Teil mit 3
        // Haltbarkeit statt eines vollen gleichen.
        if (st.isDamageableItem() && st.getMaxDamage() > 0) {
            double rest = (st.getMaxDamage() - st.getDamageValue()) / (double) st.getMaxDamage();
            if (rest < 0.1) w *= 0.5;
        }
        return w;
    }

    private static int verzauberung(ItemStack st, net.minecraft.resources.ResourceKey<net.minecraft.world.item.enchantment.Enchantment> key) {
        for (var e : st.getEnchantments().entrySet()) {
            if (e.getKey().is(key)) return e.getIntValue();
        }
        return 0;
    }

    private static boolean hatVerzauberung(ItemStack st,
                                           net.minecraft.resources.ResourceKey<net.minecraft.world.item.enchantment.Enchantment> key) {
        return verzauberung(st, key) > 0;
    }

    // ------------------------------------------------------------------
    // Auto Eat
    // ------------------------------------------------------------------

    private static int essenVorher = -1;
    private static int essenPlatz = -1;
    private static long essenSeit = 0;
    private static boolean essenTaste = false;

    private static void autoEat(Minecraft mc, LocalPlayer p) {
        AutoEatModule m = an(AutoEatModule.class);
        if (m == null || botLaeuft() || farmerIsst() || p.isCreative()) {
            if ("eat".equals(hand)) essenAbbrechen(mc);
            return;
        }

        if ("eat".equals(hand)) {
            ItemStack st = p.getInventory().getItem(essenPlatz);
            boolean nochEssen = st != null && !st.isEmpty() && st.get(DataComponents.FOOD) != null;
            // Fertig, wenn satt, der Stapel weg ist, oder nach 3 Sekunden
            // (Server laesst nicht essen, z. B. im Spawn-Schutz).
            if (!brauchtEssen(p, m) || !nochEssen || tick - essenSeit > 60) {
                essenAbbrechen(mc);
                return;
            }
            // Solange tatsaechlich gegessen wird, laeuft die Frist neu -- sonst
            // wuerde das zweite Stueck nach drei Sekunden abgebrochen.
            if (p.isUsingItem()) essenSeit = tick;
            // Platz festhalten, Taste halten.
            if (p.getInventory().getSelectedSlot() != essenPlatz) p.getInventory().setSelectedSlot(essenPlatz);
            mc.options.keyUse.setDown(true);
            return;
        }

        if (hand != null || mc.gui.screen() != null) return;
        if (!brauchtEssen(p, m)) return;
        if (m.pauseFighting.get() && gegner(mc, p, 6) != null
                && p.getHealth() > Math.max(6, m.health.get())) return;

        int platz = essensPlatz(p, m.gapples.get());
        if (platz < 0) return;
        essenVorher = p.getInventory().getSelectedSlot();
        essenPlatz = platz;
        essenSeit = tick;
        hand = "eat";
        p.getInventory().setSelectedSlot(platz);
        mc.options.keyUse.setDown(true);
        essenTaste = true;
    }

    private static boolean brauchtEssen(LocalPlayer p, AutoEatModule m) {
        int hunger = p.getFoodData().getFoodLevel();
        if (hunger <= m.hunger.getInt()) return true;
        // Leben regenerieren: nur sinnvoll, solange man nicht satt ist
        int leben = m.health.getInt();
        return leben > 0 && p.getHealth() <= leben && p.getFoodData().needsFood();
    }

    /** Bestes Essen in der Hotbar (nach Naehrwert), schaedliches ausgenommen. */
    private static int essensPlatz(LocalPlayer p, boolean gapples) {
        int best = -1;
        int bestWert = -1;
        for (int i = 0; i < 9; i++) {
            ItemStack st = p.getInventory().getItem(i);
            if (st == null || st.isEmpty()) continue;
            FoodProperties f = st.get(DataComponents.FOOD);
            if (f == null || schlecht(st)) continue;
            boolean gold = st.is(Items.GOLDEN_APPLE) || st.is(Items.ENCHANTED_GOLDEN_APPLE);
            if (gold && !gapples) continue;
            // Goldaepfel nur, wenn nichts anderes da ist -- sie sind wertvoll.
            int w = f.nutrition() * 2 + (gold ? -100 : 0);
            if (w > bestWert) {
                bestWert = w;
                best = i;
            }
        }
        return best;
    }

    private static boolean schlecht(ItemStack st) {
        return st.is(Items.ROTTEN_FLESH) || st.is(Items.SPIDER_EYE) || st.is(Items.POISONOUS_POTATO)
                || st.is(Items.PUFFERFISH) || st.is(Items.CHORUS_FRUIT) || st.is(Items.SUSPICIOUS_STEW);
    }

    private static void essenAbbrechen(Minecraft mc) {
        if (essenTaste) {
            mc.options.keyUse.setDown(false);
            essenTaste = false;
        }
        if ("eat".equals(hand)) {
            LocalPlayer p = mc.player;
            if (p != null && essenVorher >= 0 && essenVorher <= 8) {
                p.getInventory().setSelectedSlot(essenVorher);
            }
            hand = null;
        }
        essenVorher = -1;
        essenPlatz = -1;
    }

    // ------------------------------------------------------------------
    // Auto Mend
    // ------------------------------------------------------------------

    private static long mendZuletzt = -100;
    private static boolean mendAktiv = false;

    private static void autoMend(Minecraft mc, LocalPlayer p) {
        AutoMendModule m = an(AutoMendModule.class);
        if (m == null || botLaeuft()) {
            if ("mend".equals(hand)) hand = null;
            mendAktiv = false;
            return;
        }
        if (hand != null && !"mend".equals(hand)) return;
        if (mc.gui.screen() != null) return;

        int pause = m.pauseRange.getInt();
        if (pause > 0 && gegner(mc, p, pause) != null) {
            if ("mend".equals(hand)) hand = null;
            mendAktiv = false;
            return;
        }

        // Anfangen unter der Schwelle, weitermachen bis alles voll ist --
        // sonst wuerde nach jeder Flasche kurz aufgehoert und neu begonnen.
        double niedrigste = niedrigsteHaltbarkeit(p, m.onlyMending.get());
        if (niedrigste < 0) {
            mendAktiv = false;
            if ("mend".equals(hand)) hand = null;
            return;
        }
        if (!mendAktiv && niedrigste * 100 >= m.threshold.get()) return;
        if (niedrigste >= 0.999) {
            mendAktiv = false;
            if ("mend".equals(hand)) hand = null;
            return;
        }
        int platz = Inv.hotbar(p, st -> st.is(Items.EXPERIENCE_BOTTLE));
        if (platz < 0) {
            mendAktiv = false;
            if ("mend".equals(hand)) hand = null;
            return;
        }
        mendAktiv = true;
        hand = "mend";
        if (tick - mendZuletzt < m.delay.getInt()) return;
        // Senkrecht nach unten werfen -- die Kugeln landen direkt bei einem.
        Inv.benutzeMitBlick(mc, platz, 90f);
        mendZuletzt = tick;
    }

    /**
     * Niedrigste Haltbarkeit (0..1) der getragenen Ruestung und der Haende,
     * nur beschaedigte Teile. -1, wenn nichts zu reparieren ist.
     */
    private static double niedrigsteHaltbarkeit(LocalPlayer p, boolean nurMending) {
        double min = 2;
        EquipmentSlot[] plaetze = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS,
                EquipmentSlot.FEET, EquipmentSlot.MAINHAND, EquipmentSlot.OFFHAND};
        for (EquipmentSlot s : plaetze) {
            ItemStack st = p.getItemBySlot(s);
            if (st == null || st.isEmpty() || !st.isDamageableItem() || st.getDamageValue() <= 0) continue;
            if (nurMending && !hatVerzauberung(st, Enchantments.MENDING)) continue;
            double rest = (st.getMaxDamage() - st.getDamageValue()) / (double) st.getMaxDamage();
            if (rest < min) min = rest;
        }
        return min > 1 ? -1 : min;
    }

    // ------------------------------------------------------------------
    // Auto Tool
    // ------------------------------------------------------------------

    private static int toolVorher = -1;

    private static void autoTool(Minecraft mc, LocalPlayer p) {
        AutoToolModule m = an(AutoToolModule.class);
        if (m == null || hand != null || mc.gui.screen() != null || botLaeuft()) {  // Bots waehlen ihr Werkzeug selbst
            toolVorher = -1;
            return;
        }
        boolean haut = mc.options.keyAttack.isDown();
        HitResult hr = mc.hitResult;

        if (haut && hr instanceof BlockHitResult bhr && hr.getType() == HitResult.Type.BLOCK) {
            BlockState st = mc.level.getBlockState(bhr.getBlockPos());
            if (st.isAir()) return;
            int best = besterAbbau(p, st, m.saveDurability.getInt());
            waehle(p, best, m);
            return;
        }
        if (haut && m.weapons.get() && hr instanceof EntityHitResult) {
            int best = besteWaffe(p, m.saveDurability.getInt());
            waehle(p, best, m);
            return;
        }
        // Losgelassen: zurueck zum vorherigen Platz
        if (!haut && toolVorher >= 0) {
            if (m.switchBack.get()) p.getInventory().setSelectedSlot(toolVorher);
            toolVorher = -1;
        }
    }

    private static void waehle(LocalPlayer p, int platz, AutoToolModule m) {
        int jetzt = p.getInventory().getSelectedSlot();
        if (platz < 0 || platz == jetzt) return;
        if (toolVorher < 0) toolVorher = jetzt;
        p.getInventory().setSelectedSlot(platz);
    }

    /** Fast kaputt? Dann nicht benutzen (Werkzeug schonen). */
    private static boolean zuSchwach(ItemStack st, int schonen) {
        if (schonen <= 0 || !st.isDamageableItem()) return false;
        return st.getMaxDamage() - st.getDamageValue() <= schonen;
    }

    private static int besterAbbau(LocalPlayer p, BlockState state, int schonen) {
        int jetzt = p.getInventory().getSelectedSlot();
        ItemStack aktuell = p.getInventory().getItem(jetzt);
        float bestTempo = zuSchwach(aktuell, schonen) ? 0f : aktuell.getDestroySpeed(state);
        int best = -1;
        for (int i = 0; i < 9; i++) {
            ItemStack st = p.getInventory().getItem(i);
            if (st == null || st.isEmpty() || zuSchwach(st, schonen)) continue;
            float t = st.getDestroySpeed(state);
            if (t > bestTempo + 0.01f) {
                bestTempo = t;
                best = i;
            }
        }
        return best;
    }

    private static int besteWaffe(LocalPlayer p, int schonen) {
        int best = -1;
        double bestSchaden = schaden(p.getInventory().getItem(p.getInventory().getSelectedSlot()));
        for (int i = 0; i < 9; i++) {
            ItemStack st = p.getInventory().getItem(i);
            if (st == null || st.isEmpty() || zuSchwach(st, schonen)) continue;
            double s = schaden(st);
            if (s > bestSchaden + 0.01) {
                bestSchaden = s;
                best = i;
            }
        }
        return best;
    }

    private static double schaden(ItemStack st) {
        if (st == null || st.isEmpty()) return 0;
        ItemAttributeModifiers mods = st.get(DataComponents.ATTRIBUTE_MODIFIERS);
        if (mods == null) return 0;
        double s = 0;
        for (ItemAttributeModifiers.Entry e : mods.modifiers()) {
            if (e.attribute() == Attributes.ATTACK_DAMAGE) s += e.modifier().amount();
        }
        return s;
    }

    // ------------------------------------------------------------------
    // Auto Anchor
    // ------------------------------------------------------------------

    private static long ankerZuletzt = -100;
    /** Zuletzt gemeldeter Grund, warum nichts passiert -- jeder nur einmal. */
    private static String ankerGrund = null;

    /**
     * Sagt im Chat, warum Auto Anchor gerade nichts tut. Jeder Grund nur
     * einmal, bis sich etwas aendert -- sonst weiss man nie, ob das Modul
     * kaputt ist oder nur etwas fehlt.
     */
    private static void grund(LocalPlayer p, String text) {
        if (text.equals(ankerGrund)) return;
        ankerGrund = text;
        p.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                "\u00a7d[Auto Anchor] \u00a77" + text));
    }

    private static void autoAnchor(Minecraft mc, LocalPlayer p) {
        AutoAnchorModule m = an(AutoAnchorModule.class);
        if (m == null) {
            ankerGrund = null;
            return;
        }
        if (hand != null || mc.gui.screen() != null) return;
        // Im Nether ist der Anker ein normaler Respawnpunkt und explodiert nicht.
        if (mc.level.dimension() == Level.NETHER) {
            grund(p, "Respawn anchors do not explode in the Nether -- only in the Overworld and the End.");
            return;
        }
        if (tick - ankerZuletzt < m.delay.getInt()) return;

        int ankerPlatz = Inv.hotbar(p, st -> st.is(Items.RESPAWN_ANCHOR));
        int glowPlatz = Inv.hotbar(p, st -> st.is(Items.GLOWSTONE));
        if (glowPlatz < 0) {
            grund(p, "Put glowstone in your hotbar.");
            return;
        }

        double reichweite = m.range.get();
        Player ziel = gegner(mc, p, reichweite + 4);
        if (ziel == null) {
            grund(p, "Waiting for an enemy player within " + (int) (reichweite + 4)
                    + " blocks (friends and mobs are ignored).");
            return;
        }

        // 1. Liegt schon ein Anker beim Ziel? Dann Schild setzen, laden, zuenden.
        BlockPos anker = findeAnker(mc, p, ziel, m);
        if (anker != null) {
            BlockState st = mc.level.getBlockState(anker);
            int ladung = st.getValue(BlockStateProperties.RESPAWN_ANCHOR_CHARGES);
            // a) Schild: Glowstone auf die Seite des Ankers, die zu mir zeigt
            //    (zum Laden muss danach noch einer uebrig sein)
            BlockPos schild = m.shield.get() ? schildPlatz(mc, p, anker, ziel) : null;
            if (schild != null && glowAnzahl(p) >= (ladung == 0 ? 2 : 1)) {
                setzeSchild(mc, p, schild, anker, glowPlatz, m.swing.get());
                ankerGrund = null;
                ankerZuletzt = tick;
                return;
            }
            // b) laden
            if (ladung == 0) {
                klickeBlock(mc, p, anker, glowPlatz, m.swing.get());
                ankerGrund = null;
                ankerZuletzt = tick;
                return;
            }
            // c) zuenden -- aber nur, wenn es mich (mit dem Schild, der jetzt steht) nicht umbringt
            float selbst = selbstschaden(p, anker, null);
            if (selbst > m.maxSelfDamage.get() || (m.antiSuicide.get() && selbst >= Sprengung.leben(p) - 1.0f)) {
                grund(p, String.format(java.util.Locale.ROOT,
                        "Not detonating: it would deal %.1f damage to you (Max Self Damage / Anti Suicide). Step back or give me glowstone for a shield.", selbst));
                return;
            }
            // Zuenden mit etwas, das KEIN Glowstone ist -- sonst laedt man nur weiter.
            int anderer = leererPlatz(p);
            if (anderer < 0) anderer = Inv.hotbar(p, s -> !s.is(Items.GLOWSTONE)
                    && !s.is(Items.RESPAWN_ANCHOR) && !(s.getItem() instanceof net.minecraft.world.item.BlockItem));
            if (anderer < 0) {
                grund(p, "Keep one hotbar slot empty (or holding a tool/weapon) to detonate the anchor.");
                return;
            }
            if (p.getOffhandItem().is(Items.GLOWSTONE)) {
                grund(p, "Glowstone in your offhand -- detonating would only charge the anchor.");
                return;
            }
            klickeBlock(mc, p, anker, anderer, m.swing.get());
            ankerGrund = null;
            ankerZuletzt = tick;
            return;
        }

        // 2. Sonst einen setzen: ueber dem Kopf oder neben dem Ziel -- nur wo der
        //    Schaden fuer mich (mit dem geplanten Schild) passt.
        if (ankerPlatz < 0) {
            grund(p, "Put respawn anchors in your hotbar.");
            return;
        }
        BlockPos platz = setzPlatz(mc, p, ziel, m);
        if (platz == null) {
            grund(p, "No spot next to " + ziel.getName().getString()
                    + " within Range where the blast would not hurt you too much (Max Self Damage).");
            return;
        }
        int vorher = p.getInventory().getSelectedSlot();
        p.getInventory().setSelectedSlot(ankerPlatz);
        if (Inv.hatNachbar(mc, platz)) {
            Inv.setze(mc, platz, m.swing.get());
        } else {
            // Kein Block zum Anlehnen (z. B. ueber dem Kopf im Freien):
            // direkt in die Luft setzen -- der Klick zielt auf den leeren
            // Platz selbst, Minecraft setzt den Block dann genau dort hin.
            mc.gameMode.useItemOn(p, InteractionHand.MAIN_HAND,
                    new BlockHitResult(Vec3.atCenterOf(platz), Direction.UP, platz, false));
            if (m.swing.get()) p.swing(InteractionHand.MAIN_HAND);
        }
        p.getInventory().setSelectedSlot(vorher);
        ankerGrund = null;
        ankerZuletzt = tick;
    }

    private static int leererPlatz(LocalPlayer p) {
        for (int i = 0; i < 9; i++) {
            if (p.getInventory().getItem(i).isEmpty()) return i;
        }
        return -1;
    }

    private static int glowAnzahl(LocalPlayer p) {
        int n = 0;
        for (int i = 0; i < 9; i++) {
            ItemStack st = p.getInventory().getItem(i);
            if (st.is(Items.GLOWSTONE)) n += st.getCount();
        }
        return n;
    }

    /** Mein Schaden, wenn der Anker bei "anker" explodiert (optional mit geplantem Schild). */
    private static float selbstschaden(LocalPlayer p, BlockPos anker, BlockPos schild) {
        java.util.Map<BlockPos, BlockState> ersetze = new java.util.HashMap<>();
        ersetze.put(anker, Blocks.AIR.defaultBlockState());
        if (schild != null) ersetze.put(schild, Blocks.GLOWSTONE.defaultBlockState());
        return Sprengung.schaden(p, Vec3.atCenterOf(anker), Sprengung.ANKER, ersetze, 0);
    }

    /**
     * Wo der Schild hin muss: der Nachbar des Ankers, der zu meinen Augen
     * zeigt (staerkste Achse). Frei, nicht in mir oder dem Gegner. Steht dort
     * schon ein Block, deckt der ohnehin ab (null). Deckt der Schild auch den
     * Gegner ab (er steht auf meiner Seite), lieber keinen.
     */
    private static BlockPos schildPlatz(Minecraft mc, LocalPlayer p, BlockPos a, Entity ziel) {
        Vec3 d = p.getEyePosition().subtract(Vec3.atCenterOf(a));
        Direction richtung = Direction.getApproximateNearest(d.x, d.y, d.z);
        BlockPos s = a.relative(richtung);
        BlockState st = mc.level.getBlockState(s);
        if (!st.isAir() && !st.canBeReplaced()) return null;
        if (p.getBoundingBox().intersects(new net.minecraft.world.phys.AABB(s))) return null;
        if (ziel.getBoundingBox().intersects(new net.minecraft.world.phys.AABB(s))) return null;
        if (Vec3.atCenterOf(s).distanceTo(p.getEyePosition()) > 5.5) return null;
        if (ziel instanceof net.minecraft.world.entity.LivingEntity le) {
            java.util.Map<BlockPos, BlockState> ohne = new java.util.HashMap<>();
            ohne.put(a, Blocks.AIR.defaultBlockState());
            java.util.Map<BlockPos, BlockState> mit = new java.util.HashMap<>(ohne);
            mit.put(s, Blocks.GLOWSTONE.defaultBlockState());
            float zielOhne = Sprengung.schaden(le, Vec3.atCenterOf(a), Sprengung.ANKER, ohne, 0);
            float zielMit = Sprengung.schaden(le, Vec3.atCenterOf(a), Sprengung.ANKER, mit, 0);
            if (zielMit < zielOhne * 0.6f) return null;
        }
        return s;
    }

    /**
     * Glowstone an "schild" setzen -- angelehnt an einen Nachbarn, der NICHT
     * der Anker ist (ein Klick auf den Anker wuerde ihn laden statt bauen);
     * ohne Nachbarn direkt in die Luft.
     */
    private static void setzeSchild(Minecraft mc, LocalPlayer p, BlockPos schild, BlockPos anker, int glowPlatz, boolean schwingen) {
        int vorher = p.getInventory().getSelectedSlot();
        p.getInventory().setSelectedSlot(glowPlatz);
        try {
            for (Direction d : Direction.values()) {
                BlockPos n = schild.relative(d);
                if (n.equals(anker)) continue;
                BlockState st = mc.level.getBlockState(n);
                if (st.isAir() || st.canBeReplaced()) continue;
                Direction seite = d.getOpposite();
                Vec3 treffer = Vec3.atCenterOf(n).add(seite.getStepX() * 0.5, seite.getStepY() * 0.5, seite.getStepZ() * 0.5);
                mc.gameMode.useItemOn(p, InteractionHand.MAIN_HAND, new BlockHitResult(treffer, seite, n, false));
                if (schwingen) p.swing(InteractionHand.MAIN_HAND);
                return;
            }
            mc.gameMode.useItemOn(p, InteractionHand.MAIN_HAND,
                    new BlockHitResult(Vec3.atCenterOf(schild), Direction.UP, schild, false));
            if (schwingen) p.swing(InteractionHand.MAIN_HAND);
        } finally {
            p.getInventory().setSelectedSlot(vorher);
        }
    }

    private static boolean ok(LocalPlayer p, BlockPos pos, AutoAnchorModule m) {
        return Vec3.atCenterOf(pos).distanceTo(p.getEyePosition()) <= m.range.get();
    }

    private static BlockPos findeAnker(Minecraft mc, LocalPlayer p, Entity ziel, AutoAnchorModule m) {
        BlockPos fuss = ziel.blockPosition();
        BlockPos best = null;
        double bestD = Double.MAX_VALUE;
        for (int dx = -2; dx <= 2; dx++) {
            for (int dy = -1; dy <= 3; dy++) {
                for (int dz = -2; dz <= 2; dz++) {
                    BlockPos q = fuss.offset(dx, dy, dz);
                    if (mc.level.getBlockState(q).getBlock() != Blocks.RESPAWN_ANCHOR) continue;
                    if (!ok(p, q, m)) continue;
                    double d = q.distSqr(fuss);
                    if (d < bestD) {
                        bestD = d;
                        best = q;
                    }
                }
            }
        }
        return best;
    }

    private static BlockPos setzPlatz(Minecraft mc, LocalPlayer p, Entity ziel, AutoAnchorModule m) {
        BlockPos fuss = ziel.blockPosition();
        BlockPos[] kandidaten = {
                fuss.above(2),
                fuss.north(), fuss.south(), fuss.east(), fuss.west(),
                fuss.above().north(), fuss.above().south(), fuss.above().east(), fuss.above().west()
        };
        boolean schildMoeglich = m.shield.get() && glowAnzahl(p) >= 2;
        for (BlockPos q : kandidaten) {
            BlockState st = mc.level.getBlockState(q);
            if (!st.isAir() && !st.canBeReplaced()) continue;
            if (!ok(p, q, m)) continue;
            // Nicht in einen Spieler hinein (auch nicht in einen selbst)
            if (p.getBoundingBox().intersects(new net.minecraft.world.phys.AABB(q))) continue;
            if (ziel.getBoundingBox().intersects(new net.minecraft.world.phys.AABB(q))) continue;
            // Mein Schaden mit dem Schild, der dort hinkaeme -- sonst lohnt der Anker nicht
            BlockPos schild = schildMoeglich ? schildPlatz(mc, p, q, ziel) : null;
            float selbst = selbstschaden(p, q, schild);
            if (selbst > m.maxSelfDamage.get() || (m.antiSuicide.get() && selbst >= Sprengung.leben(p) - 1.0f)) continue;
            return q;
        }
        return null;
    }

    /** Rechtsklick auf einen Block mit dem Gegenstand aus Hotbar-Platz "platz". */
    private static void klickeBlock(Minecraft mc, LocalPlayer p, BlockPos pos, int platz, boolean schwingen) {
        int vorher = p.getInventory().getSelectedSlot();
        p.getInventory().setSelectedSlot(platz);
        mc.gameMode.useItemOn(p, InteractionHand.MAIN_HAND,
                new BlockHitResult(Vec3.atCenterOf(pos), Direction.UP, pos, false));
        if (schwingen) p.swing(InteractionHand.MAIN_HAND);
        p.getInventory().setSelectedSlot(vorher);
    }
}
