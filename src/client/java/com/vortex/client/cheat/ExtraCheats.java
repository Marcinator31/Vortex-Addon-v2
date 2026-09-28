package com.vortex.client.cheat;

import com.mojang.blaze3d.platform.InputConstants;
import com.vortex.client.module.Module;
import com.vortex.client.module.ModuleManager;
import com.vortex.client.module.modules.AutoFishModule;
import com.vortex.client.module.modules.AutoLogModule;
import com.vortex.client.module.modules.AutoPotModule;
import com.vortex.client.module.modules.AutoRespawnModule;
import com.vortex.client.module.modules.AutoWebModule;
import com.vortex.client.module.modules.ElytraSwapModule;
import com.vortex.client.module.modules.ParkourModule;
import com.vortex.client.module.modules.SafeWalkModule;
import com.vortex.client.module.modules.SpiderModule;
import com.vortex.client.module.modules.WTapModule;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Module aus 2.24.0: W-Tap, Elytra Swap, Auto Pot, Auto Web, Auto Log,
 * Auto Respawn, Auto Fish (Tick) sowie Spider, Parkour und Safe Walk
 * (Bewegung, ueber CheatMoveMixin / SafeWalkMixin).
 *
 * Jedes Modul steht in seinem eigenen try-Block: ein Fehler in einem legt
 * nicht die anderen lahm.
 */
public final class ExtraCheats {

    private ExtraCheats() {}

    private static long tick = 0;

    public static void register() {
        ClientTickEvents.START_CLIENT_TICK.register(mc -> {
            try { wTapTasten(mc); } catch (Throwable e) { fehler("WTap", e); }
        });
        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            tick++;
            LocalPlayer p = mc.player;
            if (p == null || mc.level == null || mc.gameMode == null) {
                geloggt = false;
                totemsZuletzt = -1;
                return;
            }
            try { autoRespawn(mc, p); } catch (Throwable e) { fehler("AutoRespawn", e); }
            if (!p.isAlive()) return;
            try { autoLog(mc, p); } catch (Throwable e) { fehler("AutoLog", e); }
            try { elytraSwap(mc, p); } catch (Throwable e) { fehler("ElytraSwap", e); }
            try { autoPot(mc, p); } catch (Throwable e) { fehler("AutoPot", e); }
            try { autoWeb(mc, p); } catch (Throwable e) { fehler("AutoWeb", e); }
            try { autoFish(mc, p); } catch (Throwable e) { fehler("AutoFish", e); }
            try { parkourSprint(mc, p); } catch (Throwable e) { fehler("Parkour", e); }
        });
    }

    private static void fehler(String wo, Throwable e) {
        com.vortex.client.core.Errors.report("ExtraCheats." + wo, e);
    }

    private static <T extends Module> T an(Class<T> typ) {
        T m = ModuleManager.INSTANCE.get(typ);
        return (m != null && m.isEnabled()) ? m : null;
    }

    private static void chat(Minecraft mc, String text) {
        if (mc.player != null) mc.player.sendSystemMessage(Component.literal("§d[Vortex]§r " + text));
    }

    /** Kein fremdes Fenster offen (Inventar ist erlaubt), nichts am Mauszeiger. */
    private static boolean inventarFrei(Minecraft mc, LocalPlayer p) {
        var s = mc.gui.screen();
        if (s != null && !(s instanceof InventoryScreen)) return false;
        return p.inventoryMenu.getCarried().isEmpty();
    }

    // ======================================================================
    // W-Tap
    // ======================================================================

    private static long sprintAb = -1;          // Legit: ab diesem Tick wieder sprinten
    private static boolean erzwungen = false;   // Packet: Sprint fuer diesen Schlag selbst gesetzt

    /**
     * Darf der Spieler gerade sprinten? (wie LocalPlayer.canStartSprinting,
     * die ist privat): vorwaerts, nicht schleichen, nichts benutzen, genug
     * Hunger, nicht blind, nicht reiten/gleiten/schwimmen, nicht an der Wand.
     */
    private static boolean kannSprinten(LocalPlayer p) {
        return p.input != null && p.input.hasForwardImpulse() && !p.isShiftKeyDown() && !p.isUsingItem()
                && !p.hasEffect(net.minecraft.world.effect.MobEffects.BLINDNESS)
                && (p.getFoodData().getFoodLevel() > 6 || p.getAbilities().mayfly)
                && !p.isPassenger() && !p.isFallFlying() && !p.isInWater() && !p.horizontalCollision;
    }

    /**
     * Vom MaceKillMixin, direkt vor dem Angriffspaket.
     *
     * WARUM ES VORHER NICHTS TAT: Nach einem Sprint-Schlag setzt Minecraft
     * Sprinten auf AUS -- beim Server und beim Client (Player.causeExtraKnockback).
     * Die alte Fassung machte nur etwas, wenn der Client noch sprintete; genau
     * dann sprintet aber auch der Server schon. Beim zweiten Schlag (dem, um
     * den es geht) sprintete der Client nicht mehr -> es passierte nichts.
     *
     * Jetzt: sprintet der Client gerade NICHT, darf er aber (vorwaerts, genug
     * Hunger ...), melden wir "sprintet" direkt vor dem Schlag. Der Server gibt
     * den vollen Sprint-Rueckstoss (Player.attack: isSprinting und Aufladung
     * > 0,9) und setzt Sprinten danach selbst wieder aus -- Client und Server
     * bleiben gleich, weil der Client dieselbe Rechnung macht.
     */
    public static void wTapVorher(Player spieler, Entity ziel) {
        erzwungen = false;
        WTapModule m = an(WTapModule.class);
        if (m == null || m.mode.getIndex() != 0) return;
        if (!(ziel instanceof LivingEntity)) return;            // Kristalle usw.: kein Rueckstoss
        if (!(spieler instanceof LocalPlayer p) || p != Minecraft.getInstance().player) return;
        if (m.onlyPlayers.get() && !(ziel instanceof Player)) return;
        if (p.isSprinting() || !kannSprinten(p)) return;
        var net = Minecraft.getInstance().getConnection();
        if (net == null) return;
        net.send(new ServerboundPlayerCommandPacket(p, ServerboundPlayerCommandPacket.Action.START_SPRINTING));
        p.setSprinting(true);
        erzwungen = true;
    }

    /** Vom MaceKillMixin, nach dem Schlag. */
    public static void wTapNachher(Player spieler, Entity ziel) {
        boolean warErzwungen = erzwungen;
        erzwungen = false;
        WTapModule m = an(WTapModule.class);
        if (m == null) return;
        if (!(ziel instanceof LivingEntity)) return;
        if (!(spieler instanceof LocalPlayer p) || p != Minecraft.getInstance().player) return;
        if (m.onlyPlayers.get() && !(ziel instanceof Player)) return;
        if (m.mode.getIndex() == 1) {
            // Legit: wie ein echter W-Tap -- ein paar Ticks ohne Sprint, dann weiter.
            sprintAb = tick + m.releaseTicks.getInt();
        } else if (warErzwungen && !p.isSprinting()) {
            // Packet: Sprint fuer den naechsten Schlag gleich wieder an (sieht fluessig aus).
            sprintAb = tick + 1;
        }
    }

    /** Jeden Tick: nach der Pause wieder sprinten, wenn es geht. */
    private static void wTapTasten(Minecraft mc) {
        if (sprintAb < 0 || tick < sprintAb) return;
        sprintAb = -1;
        LocalPlayer p = mc.player;
        if (p == null || an(WTapModule.class) == null) return;
        // setSprinting reicht: LocalPlayer meldet den Wechsel im naechsten Tick selbst an den Server.
        if (!p.isSprinting() && kannSprinten(p)) p.setSprinting(true);
    }

    // ======================================================================
    // Elytra Swap
    // ======================================================================

    private static long swapZuletzt = -100;
    private static boolean tasteVorher = false;
    private static boolean sprungVorher = false;

    private static final Item[] BRUST = {
            Items.NETHERITE_CHESTPLATE, Items.DIAMOND_CHESTPLATE, Items.IRON_CHESTPLATE,
            Items.CHAINMAIL_CHESTPLATE, Items.GOLDEN_CHESTPLATE, Items.LEATHER_CHESTPLATE
    };

    private static void elytraSwap(Minecraft mc, LocalPlayer p) {
        ElytraSwapModule m = an(ElytraSwapModule.class);
        boolean sprung = mc.options.keyJump.isDown();
        boolean sprungNeu = sprung && !sprungVorher;
        sprungVorher = sprung;
        if (m == null) { tasteVorher = false; return; }

        boolean taste = false;
        if (m.swapKey.isBound() && mc.gui.screen() == null) {
            taste = InputConstants.isKeyDown(mc.getWindow(), m.swapKey.getKeyCode());
        }
        boolean tasteNeu = taste && !tasteVorher;
        tasteVorher = taste;

        if (tick - swapZuletzt < m.delay.getInt()) return;
        if (!inventarFrei(mc, p)) return;
        ItemStack brust = p.getItemBySlot(EquipmentSlot.CHEST);
        boolean traegtElytra = brust.is(Items.ELYTRA);

        if (tasteNeu) {
            if (traegtElytra) ziehe(mc, p, besteBrustplatte(p));
            else ziehe(mc, p, elytraPlatz(p));
            return;
        }
        // Im Fallen Sprung gedrueckt -> Elytra an und losgleiten
        if (m.autoElytra.get() && sprungNeu && !traegtElytra && !p.onGround()
                && !p.isFallFlying() && !p.isInWater() && p.fallDistance >= m.minFall.get()) {
            int e = elytraPlatz(p);
            if (e >= 0 && ziehe(mc, p, e) && m.autoGlide.get()) {
                var net = mc.getConnection();
                if (net != null) {
                    net.send(new ServerboundPlayerCommandPacket(p, ServerboundPlayerCommandPacket.Action.START_FALL_FLYING));
                    p.startFallFlying();
                }
            }
            return;
        }
        // Gelandet -> Brustplatte wieder an
        if (m.autoChestplate.get() && traegtElytra && p.onGround() && !p.isFallFlying()) {
            ziehe(mc, p, besteBrustplatte(p));
        }
    }

    /** Tauscht Inventar-Index mit dem Brust-Platz (Fenster-Platz 6). */
    private static boolean ziehe(Minecraft mc, LocalPlayer p, int index) {
        if (index < 0) return false;
        Inv.tausche(mc, Inv.fensterPlatz(index), 6);
        swapZuletzt = tick;
        return true;
    }

    private static int elytraPlatz(LocalPlayer p) {
        int best = -1, bestRest = -1;
        for (int i = 0; i < 36; i++) {
            ItemStack st = p.getInventory().getItem(i);
            if (!st.is(Items.ELYTRA)) continue;
            int rest = st.getMaxDamage() - st.getDamageValue();
            if (rest <= 1) continue;                    // kaputte Elytra gleitet nicht
            if (rest > bestRest) { bestRest = rest; best = i; }
        }
        return best;
    }

    private static int besteBrustplatte(LocalPlayer p) {
        for (Item it : BRUST) {
            for (int i = 0; i < 36; i++) if (p.getInventory().getItem(i).is(it)) return i;
        }
        for (int i = 0; i < 36; i++) {
            ItemStack st = p.getInventory().getItem(i);
            if (!st.isEmpty() && st.is(ItemTags.CHEST_ARMOR) && !st.is(Items.ELYTRA)) return i;
        }
        return -1;
    }

    // ======================================================================
    // Auto Pot
    // ======================================================================

    private static long potZuletzt = -100;
    private static long heilZuletzt = -100;
    private static int heilRest = 0;
    private static Holder<MobEffect> wartetAuf = null;   // Trank wurde in die Hotbar geholt

    private static void autoPot(Minecraft mc, LocalPlayer p) {
        AutoPotModule m = an(AutoPotModule.class);
        if (m == null) { heilRest = 0; return; }
        if (m.pauseEating.get() && p.isUsingItem()) return;
        if (tick - potZuletzt < m.minDelay.getInt()) return;
        if (m.onlyOnGround.get() && !p.onGround()) return;
        if (mc.gui.screen() != null && !(mc.gui.screen() instanceof InventoryScreen)) return;

        Holder<MobEffect> brauche = null;
        float leben = p.getHealth();

        // 1. Heilen -- auch mehrere Traenke hintereinander
        if (m.heal.get()) {
            if (heilRest > 0 && leben < p.getMaxHealth()) {
                brauche = MobEffects.INSTANT_HEALTH;
            } else if (leben <= m.healBelow.get() && tick - heilZuletzt >= m.healDelay.getInt()) {
                heilRest = m.healCount.getInt();
                brauche = MobEffects.INSTANT_HEALTH;
            } else {
                heilRest = 0;
            }
        }
        // 2. Feuerschutz, wenn es brennt
        if (brauche == null && m.fireRes.get() && m.fireResWhen.getIndex() == 0
                && (p.isOnFire() || p.isInLava()) && !p.hasEffect(MobEffects.FIRE_RESISTANCE)) {
            brauche = MobEffects.FIRE_RESISTANCE;
        }
        // 3. Regeneration
        if (brauche == null && m.regen.get() && leben <= m.regenBelow.get()
                && fehlt(p, MobEffects.REGENERATION, 0)) {
            brauche = MobEffects.REGENERATION;
        }
        // 4. Buffs -- auf Wunsch nur im Kampf
        if (brauche == null && (!m.onlyInCombat.get() || gegnerNah(mc, p, m.combatRange.get()))) {
            int rest = m.refreshSeconds.getInt() * 20;
            if (m.strength.get() && fehlt(p, MobEffects.STRENGTH, rest)) brauche = MobEffects.STRENGTH;
            else if (m.speed.get() && fehlt(p, MobEffects.SPEED, rest)) brauche = MobEffects.SPEED;
            else if (m.fireRes.get() && m.fireResWhen.getIndex() == 1
                    && fehlt(p, MobEffects.FIRE_RESISTANCE, rest)) brauche = MobEffects.FIRE_RESISTANCE;
        }
        if (brauche == null) return;

        final Holder<MobEffect> eff = brauche;
        int slot = Inv.hotbar(p, st -> wurftrankMit(st, eff));
        if (slot < 0) {
            if (!m.fromInventory.get() || !inventarFrei(mc, p)) { heilRest = 0; return; }
            int inv = Inv.inventar(p, st -> wurftrankMit(st, eff));
            if (inv < 0) { heilRest = 0; return; }
            // In die Hotbar holen, geworfen wird im naechsten Durchgang.
            Inv.tausche(mc, Inv.fensterPlatz(inv), Inv.fensterPlatz(m.potSlot.getInt() - 1));
            potZuletzt = tick;
            return;
        }
        Inv.benutzeMitBlick(mc, slot, 90f);
        potZuletzt = tick;
        if (eff == MobEffects.INSTANT_HEALTH) {
            heilZuletzt = tick;
            if (heilRest > 0) heilRest--;
        }
    }

    private static boolean fehlt(LocalPlayer p, Holder<MobEffect> eff, int restTicks) {
        MobEffectInstance e = p.getEffect(eff);
        if (e == null) return true;
        if (e.isInfiniteDuration()) return false;
        return e.getDuration() <= restTicks;
    }

    private static boolean wurftrankMit(ItemStack st, Holder<MobEffect> eff) {
        if (!st.is(Items.SPLASH_POTION)) return false;
        PotionContents pc = st.get(DataComponents.POTION_CONTENTS);
        if (pc == null) return false;
        for (MobEffectInstance e : pc.getAllEffects()) if (e.is(eff)) return true;
        return false;
    }

    private static boolean gegnerNah(Minecraft mc, LocalPlayer p, double weite) {
        double w2 = weite * weite;
        for (Player o : mc.level.players()) {
            if (o == p || !o.isAlive() || o.isSpectator()) continue;
            if (com.vortex.client.core.Friends.schuetzt(o)) continue;
            if (o.distanceToSqr(p) <= w2) return true;
        }
        return false;
    }

    // ======================================================================
    // Auto Web
    // ======================================================================

    private static long webZuletzt = -100;

    private static void autoWeb(Minecraft mc, LocalPlayer p) {
        AutoWebModule m = an(AutoWebModule.class);
        if (m == null) return;
        if (tick - webZuletzt < m.delay.getInt()) return;
        if (mc.gui.screen() != null || p.isUsingItem()) return;
        int slot = Inv.hotbar(p, st -> st.is(Items.COBWEB));
        if (slot < 0) return;

        double r = m.range.get();
        LivingEntity ziel = null;
        double bestD = r * r;
        Iterable<? extends LivingEntity> kandidaten = m.onlyPlayers.get()
                ? mc.level.players()
                : mc.level.getEntitiesOfClass(LivingEntity.class, p.getBoundingBox().inflate(r));
        for (LivingEntity e : kandidaten) {
            if (e == p || !e.isAlive() || e.isSpectator()) continue;
            if (com.vortex.client.core.Friends.schuetzt(e)) continue;
            if (m.onlyOnGround.get() && !e.onGround()) continue;
            double d = e.distanceToSqr(p);
            if (d < bestD) { bestD = d; ziel = e; }
        }
        if (ziel == null) return;

        BlockPos fuss = ziel.blockPosition();
        BlockPos[] orte = m.where.getIndex() == 1
                ? new BlockPos[]{fuss, fuss.above()} : new BlockPos[]{fuss};
        for (BlockPos pos : orte) {
            if (!webPasst(mc, p, pos, m)) continue;
            int vorher = p.getInventory().getSelectedSlot();
            p.getInventory().setSelectedSlot(slot);
            boolean ok = Inv.setze(mc, pos, m.swing.get());
            p.getInventory().setSelectedSlot(vorher);
            if (ok) { webZuletzt = tick; return; }
        }
    }

    private static boolean webPasst(Minecraft mc, LocalPlayer p, BlockPos pos, AutoWebModule m) {
        BlockState st = mc.level.getBlockState(pos);
        if (st.is(Blocks.COBWEB)) return false;
        if (!st.isAir() && !st.canBeReplaced()) return false;
        if (m.notSelf.get() && p.getBoundingBox().inflate(0.3).intersects(new AABB(pos))) return false;
        if (p.getEyePosition().distanceToSqr(Vec3.atCenterOf(pos)) > m.range.get() * m.range.get()) return false;
        return Inv.hatNachbar(mc, pos);
    }

    // ======================================================================
    // Auto Log
    // ======================================================================

    private static boolean geloggt = false;
    private static int totemsZuletzt = -1;

    private static void autoLog(Minecraft mc, LocalPlayer p) {
        int totems = zaehleTotems(p);
        int vorher = totemsZuletzt;
        totemsZuletzt = totems;
        AutoLogModule m = an(AutoLogModule.class);
        if (m == null || geloggt) return;
        if (m.notSingleplayer.get() && mc.isLocalServer()) return;

        String grund = null;
        float leben = p.getHealth();
        if (m.health.getInt() > 0 && leben <= m.health.get()) {
            grund = "Leben " + Math.round(leben);
        } else if (m.totems.getInt() > 0 && totems < m.totems.getInt()) {
            grund = "nur noch " + totems + " Totem(s)";
        } else if (m.onPop.get() && vorher > totems && vorher >= 0) {
            grund = "Totem geplatzt";
        } else if (m.onPlayer.get()) {
            double r2 = m.playerRange.get() * m.playerRange.get();
            for (Player o : mc.level.players()) {
                if (o == p || o.isSpectator()) continue;
                if (m.ignoreFriends.get() && com.vortex.client.core.Friends.istFreund(o)) continue;
                if (o.distanceToSqr(p) <= r2) {
                    grund = "Spieler " + o.getName().getString() + " in "
                            + Math.round(Math.sqrt(o.distanceToSqr(p))) + " Bloecken";
                    break;
                }
            }
        }
        if (grund == null) return;

        geloggt = true;
        // Sonst waere man sofort wieder drin.
        for (Module mod : ModuleManager.INSTANCE.getModules()) {
            if (mod.getName().equalsIgnoreCase("Auto Reconnect") && mod.isEnabled()) mod.setEnabled(false);
        }
        if (m.disableAfter.get()) m.setEnabled(false);
        var net = mc.getConnection();
        if (net != null) net.getConnection().disconnect(Component.literal("[Vortex] Auto Log: " + grund));
    }

    private static int zaehleTotems(LocalPlayer p) {
        int n = 0;
        for (int i = 0; i < 36; i++) {
            ItemStack st = p.getInventory().getItem(i);
            if (st.is(Items.TOTEM_OF_UNDYING)) n += st.getCount();
        }
        if (p.getOffhandItem().is(Items.TOTEM_OF_UNDYING)) n += p.getOffhandItem().getCount();
        return n;
    }

    // ======================================================================
    // Auto Respawn
    // ======================================================================

    private static int totSeit = -1;

    private static void autoRespawn(Minecraft mc, LocalPlayer p) {
        AutoRespawnModule m = an(AutoRespawnModule.class);
        boolean tot = mc.gui.screen() instanceof net.minecraft.client.gui.screens.DeathScreen;
        if (!tot) { totSeit = -1; return; }
        if (m == null) return;
        if (totSeit < 0) {
            totSeit = (int) tick;
            if (m.deathCoords.get()) {
                BlockPos b = p.blockPosition();
                String dim = mc.level.dimension().identifier().getPath();
                chat(mc, "Gestorben bei " + b.getX() + " " + b.getY() + " " + b.getZ() + " (" + dim + ")");
            }
        }
        if (tick - totSeit < m.delay.getInt()) return;
        p.respawn();
        mc.gui.setScreen(null);
        totSeit = -1;
    }

    // ======================================================================
    // Auto Fish
    // ======================================================================

    private static long bissSeit = -1;
    private static long auswerfenAb = 0;
    private static long geworfenBei = -1;
    private static long drehZuletzt = 0;

    private static void autoFish(Minecraft mc, LocalPlayer p) {
        AutoFishModule m = an(AutoFishModule.class);
        if (m == null) { bissSeit = -1; return; }
        if (mc.gui.screen() != null) return;

        if (!p.getMainHandItem().is(Items.FISHING_ROD)) {
            if (!m.pickRod.get()) return;
            int rute = Inv.hotbar(p, st -> st.is(Items.FISHING_ROD));
            if (rute < 0) return;
            p.getInventory().setSelectedSlot(rute);
            return;
        }
        ItemStack rute = p.getMainHandItem();
        if (m.saveRod.get() && rute.isDamageableItem()
                && rute.getMaxDamage() - rute.getDamageValue() <= 3) {
            if (p.fishing != null) mc.gameMode.useItem(p, InteractionHand.MAIN_HAND);
            chat(mc, "Auto Fish: Angel fast kaputt -- aufgehoert.");
            m.setEnabled(false);
            return;
        }

        var haken = p.fishing;
        if (haken != null) {
            boolean biss = ((com.vortex.client.mixin.client.FishingHookAccessor) haken).vortex$isBiting();
            if (biss) {
                if (bissSeit < 0) bissSeit = tick;
                if (tick - bissSeit >= m.reelDelay.getInt()) {
                    mc.gameMode.useItem(p, InteractionHand.MAIN_HAND);   // einholen
                    bissSeit = -1;
                    auswerfenAb = tick + m.recastDelay.getInt();
                }
                return;
            }
            bissSeit = -1;
            // Haengt fest (an Land, an einem Block) oder beisst ewig nichts:
            // einholen und neu werfen.
            boolean festgehakt = haken.onGround() || haken.horizontalCollision;
            if ((festgehakt && geworfenBei >= 0 && tick - geworfenBei > 60)
                    || (geworfenBei >= 0 && tick - geworfenBei > 20 * 90)) {
                mc.gameMode.useItem(p, InteractionHand.MAIN_HAND);
                auswerfenAb = tick + m.recastDelay.getInt();
            }
            return;
        }
        bissSeit = -1;
        if (m.antiAfk.get() && tick - drehZuletzt > 20 * 30) {
            drehZuletzt = tick;
            p.setYRot(p.getYRot() + (float) (Math.random() * 6 - 3));
        }
        if (m.autoCast.get() && tick >= auswerfenAb) {
            mc.gameMode.useItem(p, InteractionHand.MAIN_HAND);        // auswerfen
            geworfenBei = tick;
            auswerfenAb = tick + 40;       // nicht doppelt, waehrend der Haken erscheint
        }
    }

    // ======================================================================
    // Bewegung: Spider, Parkour, Safe Walk
    // ======================================================================

    /** Parkour will diesen Tick an der Kante stehen bleiben. */
    private static boolean parkourBremse = false;

    /** Vom SafeWalkMixin: soll die Kanten-Bremse wie beim Schleichen gelten? */
    public static boolean kantenBremse(Minecraft mc) {
        if (parkourBremse) return true;
        SafeWalkModule m = an(SafeWalkModule.class);
        if (m == null) return false;
        if (m.allowJump.get() && mc.options.keyJump.isDown()) return false;
        return true;
    }

    /** Vom CheatMoveMixin, nach MoveCheats.bewegung. */
    public static Vec3 bewegung(LocalPlayer p, Vec3 v) {
        parkourBremse = false;
        if (com.vortex.client.freecam.Freecam.isActive()) return v;
        Minecraft mc = Minecraft.getInstance();

        SpiderModule sp = an(SpiderModule.class);
        if (sp != null && p.horizontalCollision && !p.isFallFlying()
                && !p.getAbilities().flying && hatEingabe(p)) {
            double hoch = p.isShiftKeyDown() ? 0.0 : sp.speed.get();
            Vec3 neu = new Vec3(v.x, Math.max(v.y, hoch), v.z);
            p.setDeltaMovement(neu);
            p.fallDistance = 0;
            return neu;
        }

        ParkourModule pk = an(ParkourModule.class);
        if (pk != null) return parkour(mc, p, v, pk);
        return v;
    }

    private static boolean hatEingabe(LocalPlayer p) {
        var mv = p.input.getMoveVector();
        return Math.abs(mv.x) > 0.01f || Math.abs(mv.y) > 0.01f;
    }

    private static Vec3 parkour(Minecraft mc, LocalPlayer p, Vec3 v, ParkourModule m) {
        if (!p.onGround() || p.isShiftKeyDown() || p.isFallFlying() || p.isInWater()
                || p.isInLava() || p.getAbilities().flying || p.isPassenger()) return v;
        double flach = Math.sqrt(v.x * v.x + v.z * v.z);
        if (flach < 0.03 || !hatEingabe(p)) return v;
        double dx = v.x / flach, dz = v.z / flach;

        // --- Einen Block hoch springen -----------------------------------
        if (m.jumpUp.get() && p.horizontalCollision && stufeVorne(mc, p, dx, dz)) {
            return springe(p, v);
        }

        // --- An der Kante -------------------------------------------------
        // Faellt der Spieler in DIESEM Tick von der Kante? Dafuer die
        // Fuesse um die geplante Bewegung verschieben und darunter pruefen.
        AABB bb = p.getBoundingBox().move(v.x, 0, v.z);
        AABB unter = new AABB(bb.minX + 0.001, bb.minY - 0.6, bb.minZ + 0.001,
                bb.maxX - 0.001, bb.minY - 0.001, bb.maxZ - 0.001);
        if (!mc.level.noCollision(p, unter)) return v;      // Boden bleibt -> nichts tun

        boolean sicher = !m.checkLanding.get() || landeplatz(mc, p, dx, dz, m);
        if (m.edgeJump.get() && sicher) return springe(p, v);
        if (!sicher && m.stopUnsafe.get()) parkourBremse = true;    // wirkt wie Schleichen
        return v;
    }

    /** Sprung genau jetzt, mit allem, was Minecraft beim Springen dazurechnet. */
    private static Vec3 springe(LocalPlayer p, Vec3 v) {
        Vec3 alt = p.getDeltaMovement();
        p.jumpFromGround();
        Vec3 dm = p.getDeltaMovement();
        Vec3 neu = new Vec3(v.x + (dm.x - alt.x), dm.y, v.z + (dm.z - alt.z));
        p.setDeltaMovement(neu);
        return neu;
    }

    /** Vorne ein einzelner Block auf Fusshoehe, darueber zwei frei? */
    private static boolean stufeVorne(Minecraft mc, LocalPlayer p, double dx, double dz) {
        BlockPos fuss = BlockPos.containing(p.getX() + dx * 0.8, p.getY() + 0.01, p.getZ() + dz * 0.8);
        if (!fest(mc, fuss)) return false;
        BlockPos ueberKopf = BlockPos.containing(p.getX(), p.getY() + 2.1, p.getZ());
        return !fest(mc, fuss.above()) && !fest(mc, fuss.above(2)) && !fest(mc, ueberKopf);
    }

    /**
     * Gibt es in Laufrichtung einen Landeplatz?
     *
     * Reichweite: ein Sprint-Sprung schafft auf gleicher Hoehe knapp 4
     * Bloecke Luecke, ein Sprung im Gehen gut 2. Gelandet werden darf auf
     * gleicher Hoehe, einen Block hoeher oder bis "Max Drop" tiefer -- nicht
     * in Lava, Feuer oder Magma.
     */
    private static boolean landeplatz(Minecraft mc, LocalPlayer p, double dx, double dz, ParkourModule m) {
        int luecke = m.maxGap.getInt();
        if (!p.isSprinting()) luecke = Math.min(luecke, 2);
        int fussY = (int) Math.floor(p.getY() + 0.01);
        for (double d = 1.0; d <= luecke + 1.0; d += 0.5) {
            int x = (int) Math.floor(p.getX() + dx * d);
            int z = (int) Math.floor(p.getZ() + dz * d);
            // Einen Block hoeher (Oberkante auf fussY+1) nur bei kurzen Spruengen
            int von = d <= 3.0 ? fussY : fussY - 1;
            for (int y = von; y >= fussY - 1 - m.maxDrop.getInt(); y--) {
                BlockPos b = new BlockPos(x, y, z);
                BlockState st = mc.level.getBlockState(b);
                if (st.is(Blocks.LAVA) || st.is(Blocks.FIRE) || st.is(Blocks.MAGMA_BLOCK)
                        || st.is(Blocks.CAMPFIRE) || st.is(Blocks.SOUL_FIRE)) break;
                if (!fest(mc, b)) continue;
                if (!fest(mc, b.above()) && !fest(mc, b.above(2))) return true;
                break;
            }
        }
        return false;
    }

    private static boolean fest(Minecraft mc, BlockPos b) {
        return mc.level.getBlockState(b).blocksMotion();
    }

    /** Auto Sprint fuer Parkour (im Tick, damit der Client selbst meldet). */
    private static void parkourSprint(Minecraft mc, LocalPlayer p) {
        ParkourModule m = an(ParkourModule.class);
        if (m == null || !m.autoSprint.get()) return;
        if (p.isSprinting() || p.isShiftKeyDown() || p.isUsingItem()) return;
        if (!p.input.hasForwardImpulse() || !p.canSprint()) return;
        if (p.getFoodData().getFoodLevel() <= 6 && !p.getAbilities().mayfly) return;
        p.setSprinting(true);
    }
}
