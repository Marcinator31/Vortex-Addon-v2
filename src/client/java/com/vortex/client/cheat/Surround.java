package com.vortex.client.cheat;

import com.vortex.client.module.ModuleManager;
import com.vortex.client.module.modules.SurroundModule;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Surround (siehe SurroundModule): haelt die Plaetze rund um die Fuesse mit
 * Obsidian (oder anderen sprengfesten Bloecken) besetzt.
 *
 * Ablauf je Tick:
 *   1. Ziel-Plaetze bestimmen: die vier Seiten auf Fusshoehe (bei "Protect
 *      Head" auch Kopfhoehe und darueber). Steht der Spieler nicht mittig,
 *      werden alle Bloecke umschlossen, die er beruehrt.
 *   2. Fehlt unter einem Platz der Halt, zuerst eine Stuetze darunter.
 *   3. Liegt ein End-Kristall im Weg: zerschlagen -- aber nur, wenn seine
 *      Explosion dich nicht umbringt (gleiche Rechnung wie Crystal Aura).
 *   4. Block setzen: kurz auf den Hotbar-Platz mit dem Block, setzen, zurueck.
 */
public final class Surround {

    private Surround() {}

    private static BlockPos start = null;
    private static boolean gemeldet = false;

    public static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            try {
                tick(mc);
            } catch (Throwable e) {
                com.vortex.client.core.Errors.report("Surround", e);
            }
        });
    }

    /** Beim Einschalten: Startblock merken, auf Wunsch in die Mitte ruecken. */
    public static void start(SurroundModule m) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        gemeldet = false;
        if (p == null) { start = null; return; }
        start = p.blockPosition();
        if (m.center.get() && p.onGround()) {
            double cx = start.getX() + 0.5, cz = start.getZ() + 0.5;
            if (Math.abs(p.getX() - cx) > 0.05 || Math.abs(p.getZ() - cz) > 0.05) {
                p.setPos(cx, p.getY(), cz);
                p.setDeltaMovement(0, p.getDeltaMovement().y, 0);
            }
        }
    }

    private static void tick(Minecraft mc) {
        SurroundModule m = ModuleManager.INSTANCE.get(SurroundModule.class);
        if (m == null || !m.isEnabled()) { start = null; return; }
        LocalPlayer p = mc.player;
        if (p == null || mc.level == null || mc.gameMode == null) return;
        if (start == null) start(m);

        BlockPos jetzt = p.blockPosition();
        if (m.offWhenMoving.get() && (jetzt.getX() != start.getX() || jetzt.getZ() != start.getZ()
                || p.getY() > start.getY() + 0.7)) {
            m.setEnabled(false);
            return;
        }
        if (!p.onGround()) return;

        int platz = Inv.hotbar(p, st -> passt(st, m));
        if (platz < 0) {
            if (!gemeldet) {
                gemeldet = true;
                p.sendSystemMessage(Component.literal("§c[Surround]§r No obsidian in your hotbar."));
            }
            return;
        }
        gemeldet = false;

        int gesetzt = 0, max = m.perTick.getInt();
        for (BlockPos ziel : ziele(p, m)) {
            if (gesetzt >= max) break;
            BlockState st = mc.level.getBlockState(ziel);
            if (!st.canBeReplaced()) continue;
            // Kristall im Weg?
            List<Entity> imWeg = mc.level.getEntities((Entity) null, new AABB(ziel), e -> e.isAlive() && e.blocksBuilding);
            if (!imWeg.isEmpty()) {
                if (m.breakCrystals.get()) {
                    for (Entity e : imWeg) {
                        if (e instanceof EndCrystal k && sicherZuZerschlagen(p, k)) {
                            mc.gameMode.attack(p, k);
                            if (m.swing.get()) p.swing(InteractionHand.MAIN_HAND);
                            break;
                        }
                    }
                }
                continue;
            }
            // Halt: ohne festen Nachbarn erst darunter stuetzen
            BlockPos wo = ziel;
            if (!Inv.hatNachbar(mc, ziel)) {
                BlockPos unten = ziel.below();
                if (!mc.level.getBlockState(unten).canBeReplaced() || !Inv.hatNachbar(mc, unten)) continue;
                wo = unten;
            }
            if (setze(mc, p, wo, platz, m.swing.get())) gesetzt++;
        }
    }

    /** Alle Plaetze, die besetzt sein sollen -- naheliegende zuerst. */
    static List<BlockPos> ziele(LocalPlayer p, SurroundModule m) {
        // Alle Bloecke, die der Spieler auf Fusshoehe beruehrt
        AABB box = p.getBoundingBox();
        int y = (int) Math.floor(box.minY + 0.01);
        List<BlockPos> fuss = new ArrayList<>();
        for (int x = (int) Math.floor(box.minX); x <= (int) Math.floor(box.maxX - 1e-4); x++)
            for (int z = (int) Math.floor(box.minZ); z <= (int) Math.floor(box.maxZ - 1e-4); z++)
                fuss.add(new BlockPos(x, y, z));
        List<BlockPos> ziele = new ArrayList<>();
        for (BlockPos f : fuss) {
            for (Direction d : Direction.Plane.HORIZONTAL) {
                BlockPos n = f.relative(d);
                if (!fuss.contains(n) && !ziele.contains(n)) ziele.add(n);
            }
        }
        if (m.head.get()) {
            List<BlockPos> kopf = new ArrayList<>();
            for (BlockPos z : ziele) kopf.add(z.above());
            ziele.addAll(kopf);
            for (BlockPos f : fuss) ziele.add(f.above(2));
        }
        // Unter den Fuessen muss auch etwas sein (sonst kann man hineinfallen / dort ein Kristall)
        for (BlockPos f : fuss) {
            BlockPos u = f.below();
            if (!ziele.contains(u)) ziele.add(0, u);
        }
        return ziele;
    }

    private static boolean passt(ItemStack st, SurroundModule m) {
        if (st.is(Items.OBSIDIAN)) return true;
        if (m.blocks.getIndex() == 0) return false;
        if (st.is(Items.CRYING_OBSIDIAN) || st.is(Items.ENDER_CHEST) || st.is(Items.NETHERITE_BLOCK)
                || st.is(Items.ANCIENT_DEBRIS) || st.is(Items.RESPAWN_ANCHOR) || st.is(Items.ENCHANTING_TABLE)
                || st.is(Items.ANVIL)) return true;
        if (st.getItem() instanceof BlockItem bi) {
            Block b = bi.getBlock();
            return b.getExplosionResistance() >= 600f;
        }
        return false;
    }

    /** Zerschlagen nur, wenn du die Explosion sicher ueberlebst (mit Reserve). */
    private static boolean sicherZuZerschlagen(LocalPlayer p, EndCrystal k) {
        float schaden = Sprengung.schaden(p, k.position(), Sprengung.KRISTALL, null, 0);
        boolean totem = p.getOffhandItem().is(Items.TOTEM_OF_UNDYING) || p.getMainHandItem().is(Items.TOTEM_OF_UNDYING);
        return schaden < Sprengung.leben(p) - 2f || totem;
    }

    private static boolean setze(Minecraft mc, LocalPlayer p, BlockPos pos, int platz, boolean schwingen) {
        // Nicht in den eigenen Koerper setzen
        if (p.getBoundingBox().intersects(new AABB(pos))) return false;
        int vorher = p.getInventory().getSelectedSlot();
        try {
            if (platz != vorher) p.getInventory().setSelectedSlot(platz);
            return Inv.setze(mc, pos, schwingen);
        } finally {
            if (platz != vorher) p.getInventory().setSelectedSlot(vorher);
        }
    }

    /** Fuer den Bot-Test: sind alle Plaetze auf Fusshoehe belegt? */
    public static boolean dicht(Minecraft mc) {
        LocalPlayer p = mc.player;
        SurroundModule m = ModuleManager.INSTANCE.get(SurroundModule.class);
        if (p == null || m == null || mc.level == null) return false;
        for (BlockPos z : ziele(p, m)) if (mc.level.getBlockState(z).canBeReplaced()) return false;
        return true;
    }

    static Vec3 mitte(BlockPos b) {
        return Vec3.atCenterOf(b);
    }
}
