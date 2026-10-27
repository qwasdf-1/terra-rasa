package dev.terrarasa;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.locks.LockSupport;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;

/**
 * Retires one level the way {@code MinecraftServer.stopServer()} does for all of them: drain chunk work,
 * flush, then close. {@link ServerLevel#close()} joins the region, POI and entity IO workers and the
 * level's saved-data storage, so once this returns no writer for the level's directory remains.
 */
public final class LevelRetirement {
    private LevelRetirement() {
    }

    /** Blocking drain-save-close on the server thread. The level must contain no players. */
    public static void drainSaveAndClose(MinecraftServer server, ServerLevel level, Duration timeout) throws LifecycleException {
        assertServerThread(server);
        if (!level.players().isEmpty()) {
            throw new LifecycleException("Refusing to retire " + level.dimension().identifier() + " with players inside");
        }
        MinecraftCompat.levelUnloaded(server, level);
        level.noSave = false;
        ServerChunkCache chunkSource = level.getChunkSource();
        long deadline = System.nanoTime() + timeout.toNanos();
        while (chunkSource.chunkMap.hasWork()) {
            chunkSource.deactivateTicketsOnClosing();
            chunkSource.tick(() -> true, false);
            for (int i = 0; i < 4096 && chunkSource.pollTask(); i++) {
                // Run chunk main-thread tasks scheduled by worldgen/IO completions.
            }
            if (System.nanoTime() > deadline) {
                throw new LifecycleException("Timed out draining chunk work for " + level.dimension().identifier());
            }
            LockSupport.parkNanos(1_000_000L);
        }
        try {
            level.save(null, true, false);
            level.close();
        } catch (IOException | RuntimeException e) {
            throw new CloseFailedException("Failed to save/close " + level.dimension().identifier(), e);
        } finally {
            // POI updates the drain queued as server tasks run after this; they must not touch closed IO.
            ((ClosedPoi) level.getPoiManager()).terrarasa$markClosed();
        }
    }

    /** Implemented on {@code PoiManager}: once marked, POI updates are ignored. */
    public interface ClosedPoi {
        void terrarasa$markClosed();
    }

    private static int ignoredPoiUpdates;

    /** Called on the server thread for every POI update that arrived after its level was closed. */
    public static void poiUpdateIgnored() {
        ignoredPoiUpdates++;
    }

    /** POI updates ignored since startup because their level was already closed. */
    public static int ignoredPoiUpdates() {
        return ignoredPoiUpdates;
    }

    /** Thrown once saving/closing began: the level is no longer usable and cannot be returned to service. */
    public static final class CloseFailedException extends LifecycleException {
        public CloseFailedException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    public static void assertServerThread(MinecraftServer server) throws LifecycleException {
        if (!server.isSameThread()) {
            throw new LifecycleException("World lifecycle operations must run on the server thread");
        }
    }
}
