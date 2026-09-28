package com.vortex.client.hud;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.Container;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.entity.SpawnerBlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Gemeinsamer Sammler fuer Block-Entities (Kisten, Spawner, ...).
 *
 * WARUM ES DAS GIBT -- wichtig zu verstehen:
 * Frueher haben drei Hintergrund-Threads (Container-ESP, StashFinder, SusChunks)
 * die Block-Entity-Listen der Chunks direkt aus der Welt gelesen. Das ist
 * gefaehrlich: Diese Listen gehoeren dem Haupt-Thread und werden dort staendig
 * veraendert. Liest ein anderer Thread gleichzeitig, kann der Lesevorgang in eine
 * Endlosschleife geraten -- der Thread dreht dann mit voller Last im Kreis und
 * die Bildrate bricht dauerhaft ein, ohne sich je zu erholen.
 *
 * Eine Kopie zu ziehen hilft NICHT: Auch das Kopieren muss die Liste durchlaufen
 * und kann genauso haengen bleiben. Und es fliegt dabei keine Ausnahme, ein
 * try/catch faengt also nichts ab.
 *
 * LOESUNG: Gesammelt wird ausschliesslich hier -- auf dem Haupt-Thread, wo der
 * Zugriff sicher ist. Damit das nicht ruckelt, werden pro Tick nur wenige Chunks
 * abgearbeitet (Rundlauf). Ist eine Runde fertig, wird eine unveraenderliche
 * Momentaufnahme veroeffentlicht, mit der alle anderen gefahrlos arbeiten koennen.
 */
public final class WorldScan {

    /** Ein gefundenes Block-Entity, auf das Noetigste reduziert. */
    public static final class Be {
        public final BlockPos pos;
        public final boolean inventory;
        public final boolean spawner;
        /** Lager-Art fuer den Stash Finder (LAGER_*), sonst 0. */
        public final byte lager;
        Be(BlockPos pos, boolean inventory, boolean spawner, byte lager) {
            this.pos = pos; this.inventory = inventory; this.spawner = spawner; this.lager = lager;
        }
    }

    // Lager-Arten (fuer den Stash Finder). Nur echte Aufbewahrung zaehlt --
    // Oefen, Trichter, Kruege oder Plattenspieler sind kein Lager.
    public static final byte LAGER_TRUHE = 1, LAGER_FASS = 2, LAGER_SHULKER = 3;

    // Indizes in chunkCounts.
    /** Container (alles mit Inventar) -- wie frueher. */
    public static final int C_INV = 0;
    /** Sonstige Block-Entities -- wie frueher. */
    public static final int C_OTHER = 1;
    /** Truhen + Faesser + Shulker. */
    public static final int C_LAGER = 2;
    /** Davon Shulker. */
    public static final int C_SHULKER = 3;
    /** Gewichteter "Spieler war hier"-Wert (siehe susGewicht). */
    public static final int C_SUS = 4;
    /** Hoehenbereich der Spielerspuren (nur Bloecke mit Gewicht > 0). */
    public static final int C_MIN_Y = 5, C_MAX_Y = 6;

    /** Fertige Momentaufnahme -- wird nur ersetzt, nie veraendert. */
    public static final class Snapshot {
        public final List<Be> entries;
        /** Pro Chunk, Indizes C_*: Inventare, Sonstige, Lager, Shulker, Sus-Wert, Min-/Max-Y. */
        public final Map<Long, int[]> chunkCounts;
        public final int version;
        Snapshot(List<Be> entries, Map<Long, int[]> counts, int version) {
            this.entries = entries; this.chunkCounts = counts; this.version = version;
        }
    }

    private static final Snapshot EMPTY =
            new Snapshot(List.of(), Map.of(), 0);

    private static final AtomicReference<Snapshot> SNAPSHOT =
            new AtomicReference<>(EMPTY);

    /**
     * Radius in Chunks: die eingestellte Sichtweite (4..32). Frueher fest 12 --
     * bei hoeherer Sichtweite lagen geladene Chunks ausserhalb und Stashes
     * darin wurden nie gefunden.
     */
    private static int radius = 12;
    /**
     * Zeitbudget pro Tick in Nanosekunden (1 ms).
     *
     * Frueher wurde eine feste Anzahl Chunks pro Tick abgearbeitet. Das ist
     * truegerisch: ein leerer Chunk kostet fast nichts, ein Chunk in einer Basis
     * mit hunderten Kisten dagegen sehr viel. Bei festen Stueckzahlen schwankt
     * der Aufwand deshalb enorm -- genau das erzeugt Ruckler.
     *
     * Jetzt gilt: es wird gearbeitet, bis das Budget aufgebraucht ist, und dann
     * beim naechsten Tick weitergemacht. Damit kann der Scanner den Tick nie
     * mehr als um diesen Betrag verlaengern, egal wie voll die Chunks sind.
     */
    private static final long TIME_BUDGET_NANOS = 1_000_000L;

    /** Obergrenze, damit auch bei leeren Chunks nicht endlos gearbeitet wird. */
    private static final int MAX_CHUNKS_PER_TICK = 64;
    /** Sicherheitsgrenze fuer die Gesamtzahl gesammelter Eintraege. */
    private static final int MAX_ENTRIES = 20000;

    // Zustand des laufenden Durchgangs.
    private static List<Be> building = new ArrayList<>();
    private static Map<Long, int[]> buildingCounts = new HashMap<>();
    private static int cursor = 0;          // Position im Rundlauf
    private static int originX = 0, originZ = 0;
    private static int version = 0;

    private WorldScan() {}

    public static Snapshot get() {
        return SNAPSHOT.get();
    }

    /** Chunk-Koordinaten zu einem Schluessel zusammenfassen. */
    public static long key(int cx, int cz) {
        return (((long) cx) << 32) ^ (cz & 0xFFFFFFFFL);
    }

    public static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            long t0 = System.nanoTime();
            try {
                tick(client);
            } catch (Throwable pvpErr) {
                com.vortex.client.core.Errors.report("WorldScan", pvpErr);
            } finally {
                com.vortex.client.core.Profiler.record("WorldScan",
                        System.nanoTime() - t0);
            }
        });
    }

    private static void tick(Minecraft client) {
        ClientLevel world = client.level;
        if (world == null || client.player == null) {
            if (SNAPSHOT.get() != EMPTY) SNAPSHOT.set(EMPTY);
            building = new ArrayList<>();
            buildingCounts = new HashMap<>();
            cursor = 0;
            return;
        }

        // Wird nur gebraucht, wenn mindestens ein Nutzer aktiv ist.
        if (!anyConsumerActive()) {
            if (SNAPSHOT.get() != EMPTY) SNAPSHOT.set(EMPTY);
            return;
        }

        // Neue Runde: Mittelpunkt auf die aktuelle Spielerposition setzen.
        if (cursor == 0) {
            try {
                radius = Math.max(4, Math.min(32, client.options.getEffectiveRenderDistance() + 1));
            } catch (Throwable t) {
                radius = 12;
            }
            originX = client.player.getBlockX() >> 4;
            originZ = client.player.getBlockZ() >> 4;
            building = new ArrayList<>();
            buildingCounts = new HashMap<>();
        }
        int side = radius * 2 + 1;
        int total = side * side;

        int done = 0;
        long deadline = System.nanoTime() + TIME_BUDGET_NANOS;
        while (cursor < total && done < MAX_CHUNKS_PER_TICK
                && System.nanoTime() < deadline) {
            int dx = (cursor % side) - radius;
            int dz = (cursor / side) - radius;
            cursor++;
            done++;

            int cx = originX + dx;
            int cz = originZ + dz;
            scanChunk(world, cx, cz);
            if (building.size() >= MAX_ENTRIES) {
                cursor = total; // Runde vorzeitig beenden
                break;
            }
        }

        // Runde fertig -> veroeffentlichen und neu beginnen.
        if (cursor >= total) {
            version++;
            // Die aufgebauten Listen werden direkt uebergeben und danach durch
            // frische ersetzt. Frueher wurde hier zusaetzlich kopiert (copyOf)
            // -- das legte am Rundenende bis zu 6000 Eintraege auf einen Schlag
            // neu an und war ein spuerbarer Ruckler alle paar Sekunden.
            // Da "building" sofort ersetzt wird, kann niemand die veroeffentlichte
            // Liste mehr veraendern; eine Kopie ist damit ueberfluessig.
            SNAPSHOT.set(new Snapshot(building, buildingCounts, version));
            building = new ArrayList<>();
            buildingCounts = new HashMap<>();
            cursor = 0;
        }
    }

    /** Ein Chunk -- laeuft auf dem Haupt-Thread, daher sicher. */
    private static void scanChunk(ClientLevel world, int cx, int cz) {
        // Verifizierte Variante: getWorldChunk(BlockPos). Die Mitte des Chunks
        // als Bezugspunkt nehmen.
        BlockPos center = new BlockPos((cx << 4) + 8, 0, (cz << 4) + 8);
        LevelChunk chunk;
        try {
            chunk = world.getChunkAt(center);
        } catch (Throwable t) {
            return;
        }
        if (chunk == null || chunk.isEmpty()) return;

        int inv = 0, other = 0, lager = 0, shulker = 0, sus = 0;
        int minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE;
        // Sicherer Zugriff: wir sind auf dem Haupt-Thread, niemand veraendert
        // die Liste waehrenddessen.
        for (BlockEntity be : chunk.getBlockEntities().values()) {
            if (be == null) continue;
            boolean isInv = be instanceof Container;
            boolean isSpawner = be instanceof SpawnerBlockEntity;
            if (isInv) inv++; else other++;
            byte art = lagerArt(be);
            if (art != 0) lager++;
            if (art == LAGER_SHULKER) shulker++;
            int g = susGewicht(be);
            if (g > 0) {
                sus += g;
                int y = be.getBlockPos().getY();
                if (y < minY) minY = y;
                if (y > maxY) maxY = y;
            }
            if (building.size() < MAX_ENTRIES) {
                building.add(new Be(be.getBlockPos(), isInv, isSpawner, art));
            }
        }
        if (inv > 0 || other > 0) {
            buildingCounts.put(key(cx, cz), new int[] { inv, other, lager, shulker, sus, minY, maxY });
        }
    }

    private static byte lagerArt(BlockEntity be) {
        BlockEntityType<?> t = be.getType();
        if (t == BlockEntityType.CHEST || t == BlockEntityType.TRAPPED_CHEST) return LAGER_TRUHE;
        if (t == BlockEntityType.BARREL) return LAGER_FASS;
        if (t == BlockEntityType.SHULKER_BOX) return LAGER_SHULKER;
        return 0;
    }

    /**
     * Wie stark ein Block-Entity auf SPIELER hindeutet.
     *
     * Vorher zaehlte jedes Block-Entity gleich (Kisten x3, Rest x1). Dadurch
     * leuchteten vor allem natuerliche Orte auf: Antike Staedte (hunderte
     * Sculk-Sensoren und -Kreischer), Bienennester, Doerfer (Glocken, Betten,
     * Lesepulte), Pfad-Ruinen (verdaechtiger Sand, Kruege), Trial Chambers
     * (Spawner, Tresore). Jetzt:
     *
     *   0  kommt natuerlich vor oder sagt nichts (Sculk, Spawner, Tresore,
     *      BienenNEST, Kruege, verdaechtiger Sand, Glocken, Portale ...)
     *   1  auch in Doerfern/Strukturen, aber meist vom Spieler (Truhe, Ofen, Bett ...)
     *   2+ praktisch nur vom Spieler (Schild, Banner, Trichter, Shulker, Beacon ...)
     */
    public static int susGewicht(BlockEntity be) {
        BlockEntityType<?> t = be.getType();
        if (t == BlockEntityType.BEACON) return 10;
        if (t == BlockEntityType.SHULKER_BOX) return 8;
        if (t == BlockEntityType.CONDUIT) return 6;
        if (t == BlockEntityType.ENDER_CHEST || t == BlockEntityType.COMMAND_BLOCK) return 5;
        if (t == BlockEntityType.HOPPER || t == BlockEntityType.ENCHANTING_TABLE) return 4;
        if (t == BlockEntityType.COMPARATOR || t == BlockEntityType.DAYLIGHT_DETECTOR
                || t == BlockEntityType.JUKEBOX || t == BlockEntityType.DROPPER
                || t == BlockEntityType.SHELF) return 3;
        if (t == BlockEntityType.BEEHIVE) {
            // Gebauter Bienenstock ja, natuerliches Bienennest nein.
            return be.getBlockState().is(Blocks.BEEHIVE) ? 3 : 0;
        }
        if (t == BlockEntityType.SIGN || t == BlockEntityType.HANGING_SIGN
                || t == BlockEntityType.BANNER || t == BlockEntityType.SKULL
                || t == BlockEntityType.TRAPPED_CHEST || t == BlockEntityType.CRAFTER
                || t == BlockEntityType.COPPER_GOLEM_STATUE) return 2;
        if (t == BlockEntityType.CHEST || t == BlockEntityType.BARREL
                || t == BlockEntityType.FURNACE || t == BlockEntityType.SMOKER
                || t == BlockEntityType.BLAST_FURNACE || t == BlockEntityType.BREWING_STAND
                || t == BlockEntityType.BED || t == BlockEntityType.LECTERN
                || t == BlockEntityType.CAMPFIRE || t == BlockEntityType.DISPENSER
                || t == BlockEntityType.CHISELED_BOOKSHELF) return 1;
        return 0;
    }

    /** Laeuft ueberhaupt eines der Module, das die Daten braucht? */
    private static boolean anyConsumerActive() {
        var mm = com.vortex.client.module.ModuleManager.INSTANCE;
        var cont = mm.get(com.vortex.client.module.modules.ContainerEspModule.class);
        if (cont != null && cont.isEnabled()) return true;
        var spawn = mm.get(com.vortex.client.module.modules.SpawnerEspModule.class);
        if (spawn != null && spawn.isEnabled()) return true;
        var stash = mm.get(com.vortex.client.module.modules.StashFinderModule.class);
        if (stash != null && stash.isEnabled()) return true;
        var sus = mm.get(com.vortex.client.module.modules.SusChunksModule.class);
        return sus != null && sus.isEnabled();
    }
}
