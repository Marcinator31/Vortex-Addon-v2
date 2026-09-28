package com.vortex.client.cheat;

import com.vortex.client.module.Module;
import com.vortex.client.module.ModuleManager;
import com.vortex.client.module.modules.AntiVoidModule;
import com.vortex.client.module.modules.GhostHandModule;
import com.vortex.client.module.modules.MiddleClickPearlModule;
import com.vortex.client.module.modules.OffhandModule;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.ButtonBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.EnderChestBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Module aus 2.30.0: Offhand, Middle Click Pearl, Anti Void, Ghost Hand.
 *
 * Jedes in seinem eigenen try-Block -- ein Fehler legt nicht die anderen lahm.
 */
public final class HandCheats {

    private HandCheats() {}

    private static long tick = 0;

    public static void register() {
        ClientTickEvents.START_CLIENT_TICK.register(mc -> {
            LocalPlayer p = mc.player;
            if (p == null || mc.level == null || mc.gameMode == null) return;
            try { perle(mc, p); } catch (Throwable e) { fehler("MiddleClickPearl", e); }
            try { geisterHand(mc, p); } catch (Throwable e) { fehler("GhostHand", e); }
        });
        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            tick++;
            LocalPlayer p = mc.player;
            if (p == null || mc.level == null || mc.gameMode == null) { sicher = null; return; }
            if (!p.isAlive()) return;
            try { offhand(mc, p); } catch (Throwable e) { fehler("Offhand", e); }
            try { antiVoid(mc, p); } catch (Throwable e) { fehler("AntiVoid", e); }
        });
    }

    private static void fehler(String wo, Throwable e) {
        com.vortex.client.core.Errors.report("HandCheats." + wo, e);
    }

    private static <T extends Module> T an(Class<T> typ) {
        T m = ModuleManager.INSTANCE.get(typ);
        return (m != null && m.isEnabled()) ? m : null;
    }

    private static void hinweis(Minecraft mc, String text) {
        if (mc.player != null) mc.player.sendOverlayMessage(Component.literal("§d" + text));
    }

    // ======================================================================
    // Offhand
    // ======================================================================

    private static long offhandZuletzt = -100;
    private static boolean autoTotemGemeldet = false;

    private static void offhand(Minecraft mc, LocalPlayer p) {
        OffhandModule m = an(OffhandModule.class);
        if (m == null) { autoTotemGemeldet = false; return; }
        var at = ModuleManager.INSTANCE.get(com.vortex.client.module.modules.AutoTotemModule.class);
        if (at != null && at.isEnabled()) {
            if (!autoTotemGemeldet) {
                autoTotemGemeldet = true;
                p.sendSystemMessage(Component.literal("§d[Vortex]§r Offhand pauses while Auto Totem is on -- use one of the two."));
            }
            return;
        }
        autoTotemGemeldet = false;
        var s = mc.gui.screen();
        if (s != null && !(s instanceof InventoryScreen)) return;
        if (!p.inventoryMenu.getCarried().isEmpty()) return;
        if (tick - offhandZuletzt < m.delay.getInt()) return;

        float leben = p.getHealth() + p.getAbsorptionAmount();
        boolean notfall = leben <= m.totemHealth.get()
                || (m.totemFalling.get() && !p.isFallFlying() && !p.onGround() && p.fallDistance - 3 >= leben - 1);
        // Isst gerade aus der Off-Hand: nicht wegnehmen (ausser im Notfall).
        if (!notfall && p.isUsingItem() && p.getUsedItemHand() == InteractionHand.OFF_HAND) return;

        Item[] wunsch;
        if (notfall) {
            wunsch = new Item[]{Items.TOTEM_OF_UNDYING};
        } else if (m.swordGap.get() && mc.options.keyUse.isDown() && waffe(p.getMainHandItem())) {
            wunsch = new Item[]{Items.ENCHANTED_GOLDEN_APPLE, Items.GOLDEN_APPLE};
        } else {
            wunsch = switch (m.item.getIndex()) {
                case 1 -> new Item[]{Items.END_CRYSTAL};
                case 2 -> new Item[]{Items.ENCHANTED_GOLDEN_APPLE, Items.GOLDEN_APPLE};
                default -> new Item[]{Items.TOTEM_OF_UNDYING};
            };
        }
        ItemStack links = p.getOffhandItem();
        for (Item w : wunsch) if (links.is(w)) return;

        int platz = suche(p, wunsch);
        if (platz < 0 && m.fallback.get() && wunsch[0] != Items.TOTEM_OF_UNDYING && !links.is(Items.TOTEM_OF_UNDYING)) {
            platz = suche(p, new Item[]{Items.TOTEM_OF_UNDYING});
        }
        if (platz < 0) return;
        mc.gameMode.handleContainerInput(p.inventoryMenu.containerId, Inv.fensterPlatz(platz), 40, ContainerInput.SWAP, p);
        offhandZuletzt = tick;
    }

    private static boolean waffe(ItemStack st) {
        return st.is(ItemTags.SWORDS) || st.is(ItemTags.AXES) || st.is(Items.MACE);
    }

    /** Inventar-Index (0..35) des ersten passenden Gegenstands, in Wunsch-Reihenfolge. */
    private static int suche(LocalPlayer p, Item[] wunsch) {
        for (Item w : wunsch) {
            for (int i = 0; i < 36; i++) if (p.getInventory().getItem(i).is(w)) return i;
        }
        return -1;
    }

    // ======================================================================
    // Middle Click Pearl
    // ======================================================================

    private static void perle(Minecraft mc, LocalPlayer p) {
        MiddleClickPearlModule m = an(MiddleClickPearlModule.class);
        if (m == null || mc.gui.screen() != null) return;
        if (m.notOnPlayers.get() && mc.crosshairPickEntity instanceof Player) return;
        int hotbar = Inv.hotbar(p, st -> st.is(Items.ENDER_PEARL));
        int inv = hotbar < 0 && m.fromInventory.get() ? Inv.inventar(p, st -> st.is(Items.ENDER_PEARL)) : -1;
        if (hotbar < 0 && inv < 0) return;          // keine Perle: Mittelklick bleibt normal
        boolean klick = false;
        while (mc.options.keyPickItem.consumeClick()) klick = true;
        if (!klick) return;
        if (p.getCooldowns().isOnCooldown(new ItemStack(Items.ENDER_PEARL))) {
            hinweis(mc, "Ender pearl is on cooldown.");
            return;
        }
        if (hotbar >= 0) {
            Inv.benutzeMitBlick(mc, hotbar, null);
        } else {
            int sel = p.getInventory().getSelectedSlot();
            int fenster = Inv.fensterPlatz(inv);
            mc.gameMode.handleContainerInput(p.inventoryMenu.containerId, fenster, sel, ContainerInput.SWAP, p);
            mc.gameMode.useItem(p, InteractionHand.MAIN_HAND);
            mc.gameMode.handleContainerInput(p.inventoryMenu.containerId, fenster, sel, ContainerInput.SWAP, p);
        }
        p.swing(InteractionHand.MAIN_HAND);
    }

    // ======================================================================
    // Anti Void
    // ======================================================================

    private static Vec3 sicher = null;
    private static long voidZuletzt = -100;

    private static void antiVoid(Minecraft mc, LocalPlayer p) {
        AntiVoidModule m = an(AntiVoidModule.class);
        if (m == null) return;
        if (p.onGround() && fest(mc, p.blockPosition().below())) sicher = p.position();
        if (p.onGround() || p.isFallFlying() || p.getAbilities().flying || p.isInWater() || Teleport.aktiv()) return;
        if (tick - voidZuletzt < 10) return;

        boolean gefahr;
        int boden = mc.level.getMinY();
        if (m.trigger.getIndex() == 1) {
            gefahr = p.getY() < boden;
        } else {
            gefahr = p.getDeltaMovement().y < 0 && p.fallDistance >= m.minFall.get() && keinBoden(mc, p);
        }
        if (!gefahr) return;
        voidZuletzt = tick;

        switch (m.mode.getIndex()) {
            case 1 -> {   // Bounce
                Vec3 v = p.getDeltaMovement();
                p.setDeltaMovement(v.x, 1.1, v.z);
                p.resetFallDistance();
            }
            case 2 -> {   // Hover
                p.setDeltaMovement(0, 0, 0);
                p.resetFallDistance();
                voidZuletzt = tick - 9;   // jeden Tick halten
            }
            default -> {  // Return
                if (sicher == null || !Teleport.starte(mc, p, sicher, 9.0, 0, "Anti Void")) {
                    Vec3 v = p.getDeltaMovement();
                    p.setDeltaMovement(v.x, 1.1, v.z);
                    hinweis(mc, "Anti Void: no free way back -- bouncing instead.");
                } else {
                    hinweis(mc, "Anti Void: going back.");
                }
            }
        }
    }

    private static boolean fest(Minecraft mc, BlockPos b) {
        BlockState st = mc.level.getBlockState(b);
        return !st.getCollisionShape(mc.level, b).isEmpty();
    }

    /** Unter dem Spieler bis zum Weltende nichts, worauf man landen koennte? */
    private static boolean keinBoden(Minecraft mc, LocalPlayer p) {
        BlockPos.MutableBlockPos b = new BlockPos.MutableBlockPos();
        int x = p.getBlockX(), z = p.getBlockZ();
        for (int y = p.getBlockY(); y >= mc.level.getMinY(); y--) {
            b.set(x, y, z);
            BlockState st = mc.level.getBlockState(b);
            if (!st.getCollisionShape(mc.level, b).isEmpty() || !st.getFluidState().isEmpty()) return false;
        }
        return true;
    }

    // ======================================================================
    // Ghost Hand
    // ======================================================================

    private static void geisterHand(Minecraft mc, LocalPlayer p) {
        GhostHandModule m = an(GhostHandModule.class);
        if (m == null || mc.gui.screen() != null || p.isShiftKeyDown()) return;
        // Schaut man direkt auf etwas Benutzbares, macht das Spiel es selbst.
        if (mc.hitResult instanceof BlockHitResult bh && mc.hitResult.getType() == HitResult.Type.BLOCK
                && benutzbar(mc, bh.getBlockPos(), m)) return;
        if (mc.hitResult != null && mc.hitResult.getType() == HitResult.Type.ENTITY) return;
        BlockPos ziel = strahl(mc, p, m);
        if (ziel == null) return;
        boolean klick = false;
        while (mc.options.keyUse.consumeClick()) klick = true;
        if (!klick) return;
        Vec3 auge = p.getEyePosition();
        Vec3 mitte = Vec3.atCenterOf(ziel);
        Direction seite = Direction.getApproximateNearest(auge.subtract(mitte));
        Vec3 treffer = mitte.add(seite.getStepX() * 0.5, seite.getStepY() * 0.5, seite.getStepZ() * 0.5);
        mc.gameMode.useItemOn(p, InteractionHand.MAIN_HAND, new BlockHitResult(treffer, seite, ziel, false));
        if (m.swing.get()) p.swing(InteractionHand.MAIN_HAND);
    }

    private static boolean benutzbar(Minecraft mc, BlockPos pos, GhostHandModule m) {
        BlockState st = mc.level.getBlockState(pos);
        if (st.isAir()) return false;
        if (st.getBlock() instanceof EnderChestBlock) return true;
        if (st.getMenuProvider(mc.level, pos) != null) return true;
        if (m.containersOnly.get()) return false;
        var b = st.getBlock();
        return b instanceof ButtonBlock || b instanceof LeverBlock || b instanceof DoorBlock
                || b instanceof TrapDoorBlock || b instanceof FenceGateBlock;
    }

    /** Erstes benutzbares Objekt auf der Blicklinie (durch Waende), sonst null. */
    private static BlockPos strahl(Minecraft mc, LocalPlayer p, GhostHandModule m) {
        Vec3 auge = p.getEyePosition();
        Vec3 blick = p.getViewVector(1f);
        double weite = p.blockInteractionRange();
        BlockPos letzte = null;
        for (double t = 0.2; t <= weite; t += 0.1) {
            BlockPos b = BlockPos.containing(auge.add(blick.scale(t)));
            if (b.equals(letzte)) continue;
            letzte = b;
            if (benutzbar(mc, b, m)) return b;
        }
        return null;
    }
}
