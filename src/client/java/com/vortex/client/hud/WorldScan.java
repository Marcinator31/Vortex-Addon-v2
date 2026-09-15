package com.vortex.client.hud;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.Container;
import net.minecraft.world.level.block.entity.BlockEntity;
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
        Be(BlockPos pos, boolean inventory, boolean spawner) {
            this.pos = pos; this.inventory = inventory; this.spawner = spawner;
        }
    }

    /** Fertige Momentaufnahme -- wird nur ersetzt, nie veraendert. */
    public static final class Snapshot {
        public final List<Be> entries;
        /** Pro Chunk: [0] = Kisten/Inventare, [1] = sonstige Block-Entities. */
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

    /** Radius in Chunks -- gross genug fuer alle Nutzer (StashFinder braucht 12). */
    private static final int RADIUS = 12;
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
    private static final int MAX_CHUNKS_PER_TICK = 32;
    /** Sicherheitsgrenze fuer die Gesamtzahl gesammelter Eintraege. */
    private static final int MAX_ENTRIES = 6000;

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

        int side = RADIUS * 2 + 1;
        int total = side * side;

        // Neue Runde: Mittelpunkt auf die aktuelle Spielerposition setzen.
        if (cursor == 0) {
            originX = client.player.getBlockX() >> 4;
            originZ = client.player.getBlockZ() >> 4;
            building = new ArrayList<>();
            buildingCounts = new HashMap<>();
        }

        int done = 0;
        long deadline = System.nanoTime() + TIME_BUDGET_NANOS;
        while (cursor < total && done < MAX_CHUNKS_PER_TICK
                && System.nanoTime() < deadline) {
            int dx = (cursor % side) - RADIUS;
            int dz = (cursor / side) - RADIUS;
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
        if (chunk == null) return;

        int inv = 0, other = 0;
        // Sicherer Zugriff: wir sind auf dem Haupt-Thread, niemand veraendert
        // die Liste waehrenddessen.
        for (BlockEntity be : chunk.getBlockEntities().values()) {
            if (be == null) continue;
            boolean isInv = be instanceof Container;
            boolean isSpawner = be instanceof SpawnerBlockEntity;
            if (isInv) inv++; else other++;
            if (building.size() < MAX_ENTRIES) {
                building.add(new Be(be.getBlockPos(), isInv, isSpawner));
            }
        }
        if (inv > 0 || other > 0) {
            buildingCounts.put(key(cx, cz), new int[] { inv, other });
        }
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
