package dev.terrarasa;

import java.util.concurrent.CompletableFuture;

import dev.terrarasa.mixin.EntityStorageAccessor;
import dev.terrarasa.mixin.PersistentEntitySectionManagerAccessor;
import dev.terrarasa.mixin.SectionStorageAccessor;
import dev.terrarasa.mixin.ServerLevelAccessor;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.storage.EntityStorage;

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
        level.save(null, false, false);
        CompletableFuture<?> chunks = level.getChunkSource().chunkMap.synchronize(true);
        CompletableFuture<?> poi = ((SectionStorageAccessor) level.getPoiManager()).terrarasa$regionStorage().synchronize(true);
        CompletableFuture<?> entities = CompletableFuture.completedFuture(null);
        Object storage = ((PersistentEntitySectionManagerAccessor) ((ServerLevelAccessor) level).terrarasa$entityManager())
                .terrarasa$permanentStorage();
        if (storage instanceof EntityStorage entityStorage) {
            entities = ((EntityStorageAccessor) entityStorage).terrarasa$regionStorage().synchronize(true);
        }
        CompletableFuture<?> data = level.getChunkSource().getDataStorage().scheduleSave();
        return CompletableFuture.allOf(chunks, poi, entities, data);
    }
}
