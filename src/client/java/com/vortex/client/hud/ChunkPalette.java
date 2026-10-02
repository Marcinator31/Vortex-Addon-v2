package com.vortex.client.hud;

import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;

/**
 * "Palette-Verfahren" fuer New Chunks (seit Addon 2.40).
 *
 * Jeder Chunk-Abschnitt (16x16x16) speichert seine Bloecke als Liste der
 * vorkommenden Blockarten ("Palette") plus Verweise darauf. Die REIHENFOLGE
 * dieser Liste verraet, woher der Abschnitt kommt:
 *
 *   - frisch erzeugt: Eintraege in der Reihenfolge, in der der Weltgenerator
 *     sie gesetzt hat (Stein, Luft, Wasser, Erze ... kreuz und quer)
 *   - von der Festplatte geladen: Minecraft baut die Palette beim Speichern
 *     neu auf, sortiert nach dem ersten Vorkommen von unten links nach oben
 *     rechts
 *
 * Der Server schickt die Palette unveraendert mit. Ist sie in jedem
 * Abschnitt nach erstem Vorkommen sortiert, war der Chunk schon gespeichert
 * (alt); sonst ist er neu. Funktioniert ueberall, auch ohne Wasser.
 */
public final class ChunkPalette {

    private ChunkPalette() {}

    /** Ergebnis fuer einen Chunk. */
    public record Befund(int abschnitte, int sortiert) {
        /** Abschnitte mit mindestens zwei Blockarten (nur die sagen etwas). */
        public boolean aussagekraeftig() { return abschnitte >= 2; }
        public boolean alt() { return aussagekraeftig() && sortiert == abschnitte; }
        public boolean neu() { return aussagekraeftig() && sortiert < abschnitte; }
    }

    public static Befund pruefe(LevelChunk chunk) {
        int abschnitte = 0, sortiert = 0;
        for (LevelChunkSection sec : chunk.getSections()) {
            if (sec == null || sec.hasOnlyAir()) continue;
            int r = sortiert(sec.getStates());
            if (r < 0) continue;
            abschnitte++;
            if (r == 1) sortiert++;
        }
        return new Befund(abschnitte, sortiert);
    }

    /**
     * @return 1 = Palette nach erstem Vorkommen sortiert, 0 = nicht,
     *         -1 = keine Aussage (nur eine Blockart oder globale Palette)
     */
    static int sortiert(PalettedContainer<BlockState> states) {
        int[] palette = paletteIds(states);
        if (palette == null || palette.length < 2) return -1;
        // Erstes Vorkommen in Speicherreihenfolge (y, z, x)
        int[] gesehen = new int[palette.length];
        int n = 0;
        java.util.BitSet schon = new java.util.BitSet();
        for (int y = 0; y < 16 && n < palette.length; y++) {
            for (int z = 0; z < 16 && n < palette.length; z++) {
                for (int x = 0; x < 16 && n < palette.length; x++) {
                    int id = Block.BLOCK_STATE_REGISTRY.getId(states.get(x, y, z));
                    if (schon.get(id)) continue;
                    schon.set(id);
                    gesehen[n++] = id;
                }
            }
        }
        // Eintraege, die gar nicht (mehr) vorkommen -> nicht aus dem Speicher
        if (n != palette.length) return 0;
        for (int i = 0; i < n; i++) if (gesehen[i] != palette[i]) return 0;
        return 1;
    }

    /**
     * Palette so, wie sie auch im Netzwerk steht: der Behaelter schreibt sich
     * selbst in einen Puffer (Bits je Eintrag, Palette, Daten); gelesen wird
     * nur der Palettenteil. null = globale Palette (keine Liste).
     */
    static int[] paletteIds(PalettedContainer<BlockState> states) {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        try {
            states.write(buf);
            int bits = buf.readUnsignedByte();
            if (bits == 0) return new int[]{buf.readVarInt()};
            if (bits > 8) return null;
            int n = buf.readVarInt();
            if (n <= 0 || n > 256) return null;
            int[] ids = new int[n];
            for (int i = 0; i < n; i++) ids[i] = buf.readVarInt();
            return ids;
        } catch (Throwable t) {
            return null;
        } finally {
            buf.release();
        }
    }
}
