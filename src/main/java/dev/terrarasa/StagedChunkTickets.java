package dev.terrarasa;

import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.function.Consumer;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ChunkLevel;
import net.minecraft.server.level.FullChunkStatus;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.Ticket;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.TicketStorage;

/**
 * The chunk tickets a preparation holds in staged levels. Squares of chunks are loaded to full status and
 * no further, overlapping squares share a chunk's vanilla ticket, and tickets are added and removed a few
 * per tick: each one eventually costs the server thread a chunk promotion or an unload. New tickets are
 * also paced by how many chunks are still generating, so generation runs as a steady trickle rather than
 * as one burst that competes with the server thread for CPU.
 */
final class StagedChunkTickets {
    private final TicketType type;
    private final Map<LoadedChunk, Integer> tickets = new HashMap<>();
    /** Chunks whose vanilla ticket is still to be added. */
    private final LinkedHashSet<LoadedChunk> queuedTickets = new LinkedHashSet<>();
    /** Chunks whose vanilla ticket is in place but that are not loaded yet. */
    private final LinkedHashSet<LoadedChunk> loading = new LinkedHashSet<>();
    /** Chunks whose vanilla ticket is still to be removed. */
    private final LinkedHashSet<LoadedChunk> queuedReleases = new LinkedHashSet<>();

    StagedChunkTickets(TicketType type) {
        this.type = type;
    }

    /**
     * Loads the square of chunks around {@code center} to FULL status and no further: one ticket per
     * chunk at exactly the FULL level. A radius ticket would make the inner chunks block- and
     * entity-ticking, spending server-thread time on random ticks in a world nobody is in.
     */
    void add(ServerLevel level, ChunkPos center, int radius) {
        forEachChunk(center, radius, pos -> {
            LoadedChunk chunk = new LoadedChunk(level, pos);
            if (this.tickets.merge(chunk, 1, Integer::sum) == 1) {
                this.queuedTickets.add(chunk);
            }
        });
    }

    /**
     * Adds up to {@code limit} queued tickets within the shared {@link GenerationBudget}. This is the next
     * run's spawn area, so it goes before any pregeneration.
     */
    void applyQueued(int limit, MinecraftServer server) {
        this.loading.removeIf(chunk -> chunk.level().getChunkSource().getChunkNow(chunk.pos().x(), chunk.pos().z()) != null);
        int starts = Math.min(limit, GenerationBudget.available(server, this, false));
        Iterator<LoadedChunk> queued = this.queuedTickets.iterator();
        for (int i = 0; i < starts && queued.hasNext(); i++) {
            LoadedChunk chunk = queued.next();
            queued.remove();
            tickets(chunk.level()).addTicket(fullOnly(), chunk.pos());
            this.loading.add(chunk);
        }
        reportBudget();
    }

    private void reportBudget() {
        GenerationBudget.report(this, this.loading.size());
        GenerationBudget.essentialWaiting(this, !this.queuedTickets.isEmpty());
    }

    boolean hasQueuedAdds() {
        return !this.queuedTickets.isEmpty();
    }

    void remove(ServerLevel level, ChunkPos center, int radius) {
        forEachChunk(center, radius, pos -> {
            // Overlapping squares share a chunk's vanilla ticket; release it with the last square.
            LoadedChunk chunk = new LoadedChunk(level, pos);
            Integer count = this.tickets.get(chunk);
            if (count == null) {
                return;
            }
            if (count > 1) {
                this.tickets.put(chunk, count - 1);
            } else {
                this.tickets.remove(chunk);
                this.loading.remove(chunk);
                if (!this.queuedTickets.remove(chunk)) {
                    tickets(level).removeTicket(fullOnly(), pos);
                }
            }
        });
        reportBudget();
    }

    /**
     * Releases every ticket a few per tick: each released chunk unloads, and unloading serializes it on
     * the server thread, so releasing a whole square at once makes one long tick.
     */
    void queueReleaseAll() {
        for (LoadedChunk chunk : this.tickets.keySet()) {
            if (!this.queuedTickets.contains(chunk)) {
                this.queuedReleases.add(chunk);
            }
        }
        this.tickets.clear();
        this.queuedTickets.clear();
        this.loading.clear();
        reportBudget();
    }

    void releaseQueued(int limit) {
        Iterator<LoadedChunk> queued = this.queuedReleases.iterator();
        for (int i = 0; i < limit && queued.hasNext(); i++) {
            LoadedChunk chunk = queued.next();
            queued.remove();
            tickets(chunk.level()).removeTicket(fullOnly(), chunk.pos());
        }
    }

    boolean hasQueuedReleases() {
        return !this.queuedReleases.isEmpty();
    }

    void releaseAll() {
        for (LoadedChunk chunk : this.tickets.keySet()) {
            if (!this.queuedTickets.contains(chunk)) {
                tickets(chunk.level()).removeTicket(fullOnly(), chunk.pos());
            }
        }
        this.tickets.clear();
        this.queuedTickets.clear();
        this.loading.clear();
        reportBudget();
        releaseQueued(Integer.MAX_VALUE);
    }

    private static void forEachChunk(ChunkPos center, int radius, Consumer<ChunkPos> action) {
        for (int x = center.x() - radius; x <= center.x() + radius; x++) {
            for (int z = center.z() - radius; z <= center.z() + radius; z++) {
                action.accept(new ChunkPos(x, z));
            }
        }
    }

    /** The level's live ticket storage (cached saved data, the instance its chunk cache uses). */
    private static TicketStorage tickets(ServerLevel level) {
        return level.getChunkSource().getDataStorage().computeIfAbsent(TicketStorage.TYPE);
    }

    /** Exactly FULL: the chunk is generated and loaded but never block- or entity-ticks. Vanilla matches
     *  tickets by type and level, so a fresh instance removes the one that was added. */
    private Ticket fullOnly() {
        return new Ticket(this.type, ChunkLevel.byStatus(FullChunkStatus.FULL));
    }

    private record LoadedChunk(ServerLevel level, ChunkPos pos) {
    }
}
