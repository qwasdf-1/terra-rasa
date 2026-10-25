package dev.terrarasa;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ChunkLevel;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.Ticket;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.TicketStorage;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.jspecify.annotations.Nullable;

/**
 * Generates one {@link PregenerationTask} in one level, a few chunks at a time, nearest tiles first.
 *
 * <p>Each chunk is generated to {@link ChunkStatus#SPAWN}: terrain, structures, features, light and
 * initial mobs, everything except becoming a live chunk. That last step is the part of generation that
 * runs on the server thread; it happens when a player first loads the chunk, and most pregenerated chunks
 * are never visited.
 *
 * <p>The area is covered in {@value #TILE}x{@value #TILE}-chunk tiles. Generating a chunk also loads a ring
 * of partly generated neighbours about ten chunks wide, so a tile keeps its finished chunks loaded until
 * the whole tile is done: neighbours are shared within the tile instead of being saved and loaded again
 * for every chunk. Then the tile is released a few chunks per tick, vanilla saves and unloads it in spare time, and memory stays
 * flat however large the area. (Releasing each chunk as soon as it was done cost about 70 region writes
 * per pregenerated chunk.)
 */
final class Pregenerator {
    /** Non-persistent: pregeneration tickets never leak into saved ticket data. */
    static final TicketType TICKET = Registry.register(BuiltInRegistries.TICKET_TYPE,
            Identifier.fromNamespaceAndPath(TerraRasa.MOD_ID, "pregenerate"), new TicketType(0L, TicketType.FLAG_LOADING));
    static final ChunkStatus TARGET = ChunkStatus.SPAWN;
    /** Chunks started per tick at most; the generation budget usually allows fewer. */
    private static final int STARTS_PER_TICK = 8;
    /**
     * Tickets of finished tiles removed per tick. Each removal changes the ticket levels of a ring of
     * neighbours, which vanilla recomputes in the next tick; releasing a whole tile at once made one long tick.
     */
    private static final int RELEASES_PER_TICK = 8;
    /** Tile side in chunks: a region file is 32, and a tile plus its neighbours stays around 1,300 chunks. */
    static final int TILE = 16;
    private static final Map<Integer, int[]> ORDERS = new HashMap<>();

    private final ServerLevel level;
    private final PregenerationTask task;
    private final int[] order;
    private int next;
    /** Chunks with a ticket that are not done yet, to their index in the order (ascending). */
    private final LinkedHashMap<ChunkPos, Integer> loading = new LinkedHashMap<>();
    /** Finished chunks still holding their ticket, by tile, until their tile is done. */
    private final Map<Long, List<ChunkPos>> finished = new LinkedHashMap<>();
    /** Chunks of finished tiles whose ticket is still to be removed, a few per tick. */
    private final ArrayDeque<ChunkPos> releasing = new ArrayDeque<>();

    Pregenerator(ServerLevel level, PregenerationTask task) {
        this.level = level;
        this.task = task.copy();
        this.order = order(task.radiusChunks);
        this.next = task.next;
    }

    ServerLevel level() {
        return this.level;
    }

    /** Releases finished chunks and starts new ones within the shared budget. */
    void tick(MinecraftServer server) {
        var chunkMap = this.level.getChunkSource().chunkMap;
        Iterator<ChunkPos> generating = this.loading.keySet().iterator();
        while (generating.hasNext()) {
            ChunkPos pos = generating.next();
            ChunkStatus reached = chunkMap.getLatestStatus(ChunkPos.pack(pos.x(), pos.z()));
            if (reached != null && reached.isOrAfter(TARGET)) {
                this.finished.computeIfAbsent(tile(pos), key -> new ArrayList<>()).add(pos);
                generating.remove();
            }
        }
        releaseFinishedTiles();
        for (int i = 0; i < RELEASES_PER_TICK && !this.releasing.isEmpty(); i++) {
            tickets().removeTicket(ticket(), this.releasing.poll());
        }
        int starts = Math.min(STARTS_PER_TICK, GenerationBudget.available(server, this, true));
        int total = this.order.length / 2;
        for (int i = 0; i < starts && this.next < total; i++) {
            ChunkPos pos = position(this.next);
            tickets().addTicket(ticket(), pos);
            this.loading.put(pos, this.next);
            this.next++;
        }
        GenerationBudget.report(this, this.loading.size());
    }

    /** A tile is released once nothing in it is still generating and nothing more of it will start. */
    private void releaseFinishedTiles() {
        int total = this.order.length / 2;
        @Nullable Long current = this.next < total ? tile(position(this.next)) : null;
        Iterator<Map.Entry<Long, List<ChunkPos>>> tiles = this.finished.entrySet().iterator();
        while (tiles.hasNext()) {
            Map.Entry<Long, List<ChunkPos>> tile = tiles.next();
            boolean busy = tile.getKey().equals(current)
                    || this.loading.keySet().stream().anyMatch(pos -> tile(pos) == tile.getKey());
            if (!busy) {
                this.releasing.addAll(tile.getValue());
                tiles.remove();
            }
        }
    }

    private ChunkPos position(int index) {
        return new ChunkPos(this.task.centerChunkX + this.order[index * 2], this.task.centerChunkZ + this.order[index * 2 + 1]);
    }

    private long tile(ChunkPos pos) {
        return ChunkPos.pack(Math.floorDiv(pos.x() - this.task.centerChunkX, TILE), Math.floorDiv(pos.z() - this.task.centerChunkZ, TILE));
    }

    boolean isDone() {
        return this.next >= this.order.length / 2 && this.loading.isEmpty() && this.finished.isEmpty() && this.releasing.isEmpty();
    }

    /** The task with its progress: everything before {@code next} is generated. */
    PregenerationTask snapshot() {
        PregenerationTask snapshot = this.task.copy();
        snapshot.next = this.loading.isEmpty() ? this.next : this.loading.values().iterator().next();
        return snapshot;
    }

    /** Stops: releases every ticket. Chunks part-way through are saved as they are and finished later. */
    void release() {
        for (ChunkPos pos : this.loading.keySet()) {
            tickets().removeTicket(ticket(), pos);
        }
        this.finished.values().forEach(tile -> tile.forEach(pos -> tickets().removeTicket(ticket(), pos)));
        this.releasing.forEach(pos -> tickets().removeTicket(ticket(), pos));
        this.loading.clear();
        this.finished.clear();
        this.releasing.clear();
        GenerationBudget.report(this, 0);
    }

    /** Drops tracking without touching the level, which is already closed. */
    void abandon() {
        this.loading.clear();
        this.finished.clear();
        this.releasing.clear();
        GenerationBudget.report(this, 0);
    }

    private TicketStorage tickets() {
        return this.level.getChunkSource().getDataStorage().computeIfAbsent(TicketStorage.TYPE);
    }

    /** Vanilla matches tickets by type and level, so a fresh instance removes the one that was added. */
    private static Ticket ticket() {
        return new Ticket(TICKET, ChunkLevel.byStatus(TARGET));
    }

    /**
     * Chunk offsets within {@code radius} as {@code [dx0, dz0, dx1, dz1, ...]}: tile by tile, nearest tile
     * first, and row by row within a tile. Ties are broken by angle, so the order is deterministic and a
     * saved index means the same chunk later.
     */
    static int[] order(int radius) {
        return ORDERS.computeIfAbsent(radius, r -> {
            List<int[]> offsets = new ArrayList<>();
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (dx * dx + dz * dz <= r * r) {
                        offsets.add(new int[] {dx, dz});
                    }
                }
            }
            // Tile centres relative to the area centre, in half-chunks so they stay integers.
            Comparator<int[]> byTile = Comparator.<int[]>comparingInt(o -> {
                int tx = Math.floorDiv(o[0], TILE) * 2 * TILE + TILE;
                int tz = Math.floorDiv(o[1], TILE) * 2 * TILE + TILE;
                return tx * tx + tz * tz;
            }).thenComparingDouble(o -> Math.atan2(Math.floorDiv(o[1], TILE) + 0.5, Math.floorDiv(o[0], TILE) + 0.5));
            offsets.sort(byTile.thenComparingInt(o -> Math.floorDiv(o[0], TILE)).thenComparingInt(o -> Math.floorDiv(o[1], TILE))
                    .thenComparingInt(o -> o[1]).thenComparingInt(o -> o[0]));
            int[] packed = new int[offsets.size() * 2];
            for (int i = 0; i < offsets.size(); i++) {
                packed[i * 2] = offsets.get(i)[0];
                packed[i * 2 + 1] = offsets.get(i)[1];
            }
            return packed;
        });
    }
}
