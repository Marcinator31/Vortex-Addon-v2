package com.vortex.client.hud;

import com.mojang.blaze3d.vertex.PoseStack;
import com.vortex.client.core.PacketHooks;
import com.vortex.client.module.ModuleManager;
import com.vortex.client.module.modules.NewChunksModule;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientChunkEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.Vec3;

/**
 * New Chunks (siehe NewChunksModule). Gleiches Verfahren wie Meteor Client:
 *
 *   NEU: Der Server meldet fliessende Fluessigkeit neben einer Quelle -- die
 *        beginnt gerade erst zu fliessen, der Chunk ist also frisch.
 *   ALT: Beim Laden steckt schon fliessende Fluessigkeit im Chunk -- sie ist
 *        frueher geflossen, der Chunk war schon einmal geladen.
 *
 * Pakete kommen auf dem Netzwerk-Thread an; sie werden nur vorgemerkt und im
 * naechsten Tick auf dem Spiel-Thread ausgewertet.
 */
public final class NewChunks {

    private NewChunks() {}

    private static final Set<Long> NEU = ConcurrentHashMap.newKeySet();
    private static final Set<Long> ALT = ConcurrentHashMap.newKeySet();
    private record Update(BlockPos pos, BlockState state) {}
    private static final ConcurrentLinkedQueue<Update> WARTET = new ConcurrentLinkedQueue<>();
    private static Object letzteWelt = null;

    private static final Direction[] SUCHE = {Direction.EAST, Direction.NORTH, Direction.WEST, Direction.SOUTH, Direction.UP};

    private static boolean an() {
        NewChunksModule m = ModuleManager.INSTANCE.get(NewChunksModule.class);
        return m != null && m.isEnabled();
    }

    public static void register() {
        PacketHooks.onReceive(p -> {
            if (!an()) return false;
            if (p instanceof ClientboundBlockUpdatePacket b) {
                if (fliesst(b.getBlockState().getFluidState())) WARTET.add(new Update(b.getPos(), b.getBlockState()));
            } else if (p instanceof ClientboundSectionBlocksUpdatePacket s) {
                s.runUpdates((pos, st) -> {
                    if (fliesst(st.getFluidState())) WARTET.add(new Update(pos.immutable(), st));
                });
            }
            return false;
        });
        ClientChunkEvents.CHUNK_LOAD.register((level, chunk) -> {
            try {
                if (an()) pruefeGeladen(chunk);
            } catch (Throwable e) {
                com.vortex.client.core.Errors.report("NewChunks.load", e);
            }
        });
        ClientTickEvents.END_CLIENT_TICK.register(NewChunks::tick);
        LevelRenderEvents.AFTER_TRANSLUCENT_FEATURES.register(context -> {
            try { zeichnen(context.poseStack(), context.submitNodeCollector()); }
            catch (Throwable e) { com.vortex.client.core.Errors.report("NewChunks.render", e); }
        });
    }

    private static boolean fliesst(FluidState f) {
        return !f.isEmpty() && !f.isSource();
    }

    private static void tick(Minecraft mc) {
        if (mc.level == null || !an()) {
            WARTET.clear();
            if (mc.level == null) { NEU.clear(); ALT.clear(); letzteWelt = null; }
            return;
        }
        if (mc.level != letzteWelt) {
            letzteWelt = mc.level;
            NEU.clear();
            ALT.clear();
        }
        Update u;
        int n = 0;
        while ((u = WARTET.poll()) != null && n++ < 4000) {
            long c = ChunkPos.containing(u.pos()).pack();
            if (ALT.contains(c) || NEU.contains(c)) continue;
            for (Direction d : SUCHE) {
                if (mc.level.getBlockState(u.pos().relative(d)).getFluidState().isSource()) {
                    NEU.add(c);
                    break;
                }
            }
        }
    }

    private static void pruefeGeladen(LevelChunk chunk) {
        long c = chunk.getPos().pack();
        if (NEU.contains(c) || ALT.contains(c)) return;
        for (LevelChunkSection sec : chunk.getSections()) {
            if (sec == null || sec.hasOnlyAir()) continue;
            if (!sec.maybeHas(st -> !st.getFluidState().isEmpty())) continue;
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        if (fliesst(sec.getFluidState(x, y, z))) {
                            ALT.add(c);
                            return;
                        }
                    }
                }
            }
        }
    }

    private static void zeichnen(PoseStack ms, SubmitNodeCollector col) {
        NewChunksModule m = ModuleManager.INSTANCE.get(NewChunksModule.class);
        if (m == null || !m.isEnabled() || ms == null || col == null) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        List<long[]> liste = new ArrayList<>();
        int r = mc.options.renderDistance().get() + 2;
        int pcx = mc.player.getBlockX() >> 4, pcz = mc.player.getBlockZ() >> 4;
        if (m.showNew.get()) for (long c : NEU) sammle(liste, c, 1, pcx, pcz, r);
        if (m.showOld.get()) for (long c : ALT) sammle(liste, c, 0, pcx, pcz, r);
        if (liste.isEmpty()) return;
        float td = mc.getDeltaTracker().getGameTimeDeltaPartialTick(false);
        Vec3 cam = EspRender.cameraOffset(mc, td);
        double y = m.followPlayer.get() ? Math.floor(mc.player.getY()) + 0.02 : m.renderY.get();
        int neu = deckend(m.newColor.get()), alt = deckend(m.oldColor.get());
        EspRender.submitLines(col, ms, (mat, lines) -> {
            for (long[] e : liste) {
                int f = e[2] == 1 ? neu : alt;
                double x0 = (e[0] << 4) + 0.5, z0 = (e[1] << 4) + 0.5, x1 = x0 + 15, z1 = z0 + 15;
                EspRender.drawTracer(mat, lines, new Vec3(x0, y, z0), new Vec3(x1, y, z0), cam, f, 2f);
                EspRender.drawTracer(mat, lines, new Vec3(x1, y, z0), new Vec3(x1, y, z1), cam, f, 2f);
                EspRender.drawTracer(mat, lines, new Vec3(x1, y, z1), new Vec3(x0, y, z1), cam, f, 2f);
                EspRender.drawTracer(mat, lines, new Vec3(x0, y, z1), new Vec3(x0, y, z0), cam, f, 2f);
            }
        });
    }

    private static void sammle(List<long[]> liste, long c, int art, int pcx, int pcz, int r) {
        int cx = ChunkPos.getX(c), cz = ChunkPos.getZ(c);
        if (Math.abs(cx - pcx) > r || Math.abs(cz - pcz) > r) return;
        liste.add(new long[]{cx, cz, art});
    }

    private static int deckend(int c) {
        return (c >>> 24) == 0 ? c | 0xFF000000 : c;
    }
}
