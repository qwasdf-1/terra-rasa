package dev.terrarasa;

import java.util.concurrent.CompletableFuture;
import net.minecraft.server.level.ServerLevel;

/**
 * Gets a level's data onto disk without blocking the server thread. Vanilla's {@code save(flush=true)}
 * and {@code close()} wait for every queued chunk, entity and POI write and then flush the region files;
 * after a few hundred chunks unload that wait is hundreds of milliseconds. Starting the writes and the
 * flush here, then closing once the returned future completes, leaves the final close with nothing to do.
 */
final class LevelIo {
    private LevelIo() {
    }

    /** Queues every pending write of {@code level} and completes once all of it is flushed to disk. */
    static CompletableFuture<Void> flushAsync(ServerLevel level) {
        return MinecraftCompat.flushLevelIoAsync(level);
    }
}
