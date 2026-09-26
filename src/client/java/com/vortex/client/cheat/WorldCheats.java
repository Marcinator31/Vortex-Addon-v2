package com.vortex.client.cheat;

import com.vortex.client.module.ModuleManager;
import com.vortex.client.module.modules.ChestStealerModule;
import com.vortex.client.module.modules.NukerModule;
import com.vortex.client.module.modules.ScaffoldModule;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ShulkerBoxMenu;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * Welt-Cheats: Scaffold, Chest Stealer, Nuker.
 */
public final class WorldCheats {

    private WorldCheats() {}

    private static long tick = 0;

    public static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            tick++;
            LocalPlayer p = mc.player;
            if (p == null || mc.level == null || mc.gameMode == null) {
                nukerZiel = null;
                return;
            }
            try { chestStealer(mc, p); } catch (Throwable e) { fehler("ChestStealer", e); }
            try { scaffold(mc, p); } catch (Throwable e) { fehler("Scaffold", e); }
            try { nuker(mc, p); } catch (Throwable e) { fehler("Nuker", e); }
        });
    }

    private static void fehler(String wo, Throwable e) {
        com.vortex.client.core.Errors.report("WorldCheats." + wo, e);
    }

    private static <T extends com.vortex.client.module.Module> T an(Class<T> typ) {
        T m = ModuleManager.INSTANCE.get(typ);
        return (m != null && m.isEnabled()) ? m : null;
    }

    // ------------------------------------------------------------------
    // Scaffold
    // ------------------------------------------------------------------

    private static void scaffold(Minecraft mc, LocalPlayer p) {
        ScaffoldModule m = an(ScaffoldModule.class);
        if (m == null || mc.gui.screen() != null || p.getAbilities().flying) return;

        // Der Block unter den Fuessen -- und, bei schneller Bewegung, der
        // unter der Stelle, an der man im naechsten Tick steht. Sonst faellt
        // man beim Sprinten zwischen zwei Setzversuchen ueber die Kante.
        Vec3 v = p.getDeltaMovement();
        BlockPos unten = BlockPos.containing(p.getX(), p.getY() - 0.5, p.getZ());
        BlockPos voraus = BlockPos.containing(p.getX() + v.x * 2, p.getY() - 0.5, p.getZ() + v.z * 2);

        BlockPos ziel = null;
        if (frei(mc, unten)) ziel = unten;
        else if (frei(mc, voraus) && !voraus.equals(unten)) ziel = voraus;
        if (ziel == null) return;

        int platz = Inv.hotbar(p, WorldCheats::bauBlock);
        if (platz < 0) return;

        // Hat der Platz keinen Nachbarn zum Anlehnen, zuerst daneben setzen
        // (z. B. beim Loslaufen ueber einen Abgrund).
        BlockPos setzen = ziel;
        if (!Inv.hatNachbar(mc, ziel)) {
            setzen = null;
            for (Direction d : new Direction[]{Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST}) {
                BlockPos n = ziel.relative(d);
                if (frei(mc, n) && Inv.hatNachbar(mc, n)) {
                    setzen = n;
                    break;
                }
            }
            if (setzen == null) return;
        }

        int vorher = p.getInventory().getSelectedSlot();
        float alterYaw = p.getYRot(), alterPitch = p.getXRot();
        try {
            p.getInventory().setSelectedSlot(platz);
            // Rotate: dem Server kurz "nach hinten unten schauen" melden, wie
            // beim echten Brueckenbauen. Das Setz-Paket selbst traegt keine
            // Blickrichtung -- deshalb ein eigenes Drehpaket davor und danach
            // eins mit der echten Richtung. Die Kamera bewegt sich nicht.
            if (m.rotate.get() && mc.getConnection() != null) {
                mc.getConnection().send(new net.minecraft.network.protocol.game.ServerboundMovePlayerPacket.Rot(
                        alterYaw + 180f, 80f, p.onGround(), p.horizontalCollision));
            }
            Inv.setze(mc, setzen, m.swing.get());
        } finally {
            if (m.rotate.get() && mc.getConnection() != null) {
                mc.getConnection().send(new net.minecraft.network.protocol.game.ServerboundMovePlayerPacket.Rot(
                        alterYaw, alterPitch, p.onGround(), p.horizontalCollision));
            }
            if (m.switchBack.get()) p.getInventory().setSelectedSlot(vorher);
        }

        // Tower: beim Hochspringen auf der Stelle schneller nach oben
        if (m.tower.get() && mc.options.keyJump.isDown() && !bewegtSich(mc) && setzen == unten) {
            Vec3 jetzt = p.getDeltaMovement();
            if (jetzt.y < 0.2) p.setDeltaMovement(0, 0.42, 0);
        }
    }

    private static boolean bewegtSich(Minecraft mc) {
        return mc.options.keyUp.isDown() || mc.options.keyDown.isDown()
                || mc.options.keyLeft.isDown() || mc.options.keyRight.isDown();
    }

    private static boolean frei(Minecraft mc, BlockPos pos) {
        BlockState st = mc.level.getBlockState(pos);
        return st.isAir() || st.canBeReplaced();
    }

    /** Ein normaler, fester Block -- keine Blumen, Fackeln, Sand (faellt). */
    private static boolean bauBlock(ItemStack st) {
        if (!(st.getItem() instanceof BlockItem bi)) return false;
        String name = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(st.getItem()).getPath();
        if (name.contains("sand") || name.contains("gravel") || name.contains("powder")
                || name.contains("anvil") || name.contains("shulker") || name.contains("chest")
                || name.contains("tnt") || name.contains("bed") || name.contains("anchor")) return false;
        // Voller Block (Stein, Erde, Bretter ...) -- keine Fackeln, Blumen,
        // Stufen oder Teppiche. Dieselbe Abfrage wie in Meteor fuer 26.2.
        var level = Minecraft.getInstance().level;
        if (level == null) return false;
        return net.minecraft.world.level.block.Block.isShapeFullBlock(
                bi.getBlock().defaultBlockState().getCollisionShape(level, BlockPos.ZERO));
    }

    // ------------------------------------------------------------------
    // Chest Stealer
    // ------------------------------------------------------------------

    private static long stealZuletzt = -100;
    private static long offenSeit = -1;
    private static Screen letzterBildschirm = null;

    private static void chestStealer(Minecraft mc, LocalPlayer p) {
        ChestStealerModule m = an(ChestStealerModule.class);
        Screen s = mc.gui.screen();
        if (s != letzterBildschirm) {
            letzterBildschirm = s;
            offenSeit = tick;
        }
        if (m == null || !(s instanceof AbstractContainerScreen<?>)) return;
        AbstractContainerScreen<?> cs = (AbstractContainerScreen<?>) s;
        AbstractContainerMenu menu = cs.getMenu();
        if (menu == p.inventoryMenu) return;
        // NUR LAGER-FENSTER. Ohne diese Liste wuerde bei Werkbank oder
        // Dorfbewohner der Ergebnisplatz angeklickt -- also gecraftet oder
        // gehandelt. "Only Chests" aus nimmt Trichter, Spender und Oefen dazu.
        boolean kiste = menu instanceof ChestMenu || menu instanceof ShulkerBoxMenu;
        if (!kiste) {
            if (m.onlyChests.get()) return;
            String art = menu.getClass().getSimpleName();
            boolean lager = menu instanceof net.minecraft.world.inventory.AbstractFurnaceMenu
                    || art.equals("HopperMenu") || art.equals("DispenserMenu");
            if (!lager) return;
        }
        // Kurz warten, bis der Inhalt vom Server da ist -- sonst ist die Kiste
        // im ersten Tick leer und wuerde sofort wieder geschlossen.
        if (tick - offenSeit < 3) return;
        if (tick - stealZuletzt < m.delay.getInt()) return;

        int kisteGroesse = menu.slots.size() - 36;
        if (kisteGroesse <= 0) return;
        for (int i = 0; i < kisteGroesse; i++) {
            if (!menu.getSlot(i).hasItem()) continue;
            Inv.shiftKlick(mc, menu.containerId, i);
            stealZuletzt = tick;
            // Bei 0 Ticks Verzoegerung alles in einem Tick, sonst eins pro Takt
            if (m.delay.getInt() > 0) return;
        }
        // Leer (oder Inventar voll -- dann bleibt der Rest liegen): schliessen
        boolean leer = true;
        for (int i = 0; i < kisteGroesse; i++) {
            if (menu.getSlot(i).hasItem()) {
                leer = false;
                break;
            }
        }
        if ((leer || inventarVoll(p)) && m.autoClose.get()) cs.onClose();
    }

    private static boolean inventarVoll(LocalPlayer p) {
        for (int i = 0; i < 36; i++) {
            if (p.getInventory().getItem(i).isEmpty()) return false;
        }
        return true;
    }

    // ------------------------------------------------------------------
    // Nuker
    // ------------------------------------------------------------------

    private static BlockPos nukerZiel = null;

    private static void nuker(Minecraft mc, LocalPlayer p) {
        NukerModule m = an(NukerModule.class);
        if (m == null || mc.gui.screen() != null) {
            nukerZiel = null;
            return;
        }
        double r = m.range.get();
        int modus = m.mode.getIndex();   // 0 All, 1 Flatten, 2 Instant

        if (modus == 2) {
            // INSTANT: nur Bloecke, die sofort brechen (Gras, Blumen, im
            // Kreativmodus alles) -- mehrere pro Tick.
            int n = 0;
            for (BlockPos q : kandidaten(mc, p, r, false)) {
                BlockState st = mc.level.getBlockState(q);
                if (!p.isCreative() && st.getDestroySpeed(mc.level, q) != 0f) continue;
                mc.gameMode.startDestroyBlock(q, seite(p, q));
                if (m.swing.get()) p.swing(InteractionHand.MAIN_HAND);
                if (++n >= m.perTick.getInt()) break;
            }
            return;
        }

        // ALL / FLATTEN: einen Block nach dem anderen normal abbauen.
        if (nukerZiel != null && !abbaubar(mc, p, nukerZiel, r, modus == 1)) nukerZiel = null;
        if (nukerZiel == null) {
            for (BlockPos q : kandidaten(mc, p, r, modus == 1)) {
                nukerZiel = q;
                mc.gameMode.startDestroyBlock(q, seite(p, q));
                break;
            }
            if (nukerZiel == null) return;
        } else {
            mc.gameMode.continueDestroyBlock(nukerZiel, seite(p, nukerZiel));
        }
        if (m.swing.get()) p.swing(InteractionHand.MAIN_HAND);
    }

    private static boolean abbaubar(Minecraft mc, LocalPlayer p, BlockPos q, double r, boolean flach) {
        BlockState st = mc.level.getBlockState(q);
        if (st.isAir() || !st.getFluidState().isEmpty()) return false;
        if (st.getDestroySpeed(mc.level, q) < 0) return false;   // Grundgestein & Co.
        if (Vec3.atCenterOf(q).distanceTo(p.getEyePosition()) > r) return false;
        // Flatten: nur auf und ueber Fusshoehe -- nie den Boden unter einem
        return !flach || q.getY() >= Math.floor(p.getY());
    }

    /** Abbaubare Bloecke in Reichweite, die naechsten zuerst. */
    private static java.util.List<BlockPos> kandidaten(Minecraft mc, LocalPlayer p, double r, boolean flach) {
        java.util.List<BlockPos> liste = new java.util.ArrayList<>();
        BlockPos mitte = BlockPos.containing(p.getEyePosition());
        int ri = (int) Math.ceil(r);
        for (int dx = -ri; dx <= ri; dx++) {
            for (int dy = -ri; dy <= ri; dy++) {
                for (int dz = -ri; dz <= ri; dz++) {
                    BlockPos q = mitte.offset(dx, dy, dz);
                    if (abbaubar(mc, p, q, r, flach)) liste.add(q.immutable());
                }
            }
        }
        Vec3 auge = p.getEyePosition();
        liste.sort(java.util.Comparator.comparingDouble(q -> Vec3.atCenterOf(q).distanceToSqr(auge)));
        return liste;
    }

    /** Die Seite des Blocks, die zum Auge zeigt. */
    private static Direction seite(LocalPlayer p, BlockPos q) {
        Vec3 d = p.getEyePosition().subtract(Vec3.atCenterOf(q));
        double ax = Math.abs(d.x), ay = Math.abs(d.y), az = Math.abs(d.z);
        if (ay >= ax && ay >= az) return d.y > 0 ? Direction.UP : Direction.DOWN;
        if (ax >= az) return d.x > 0 ? Direction.EAST : Direction.WEST;
        return d.z > 0 ? Direction.SOUTH : Direction.NORTH;
    }
}
