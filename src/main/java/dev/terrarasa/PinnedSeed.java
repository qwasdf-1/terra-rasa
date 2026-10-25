package dev.terrarasa;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import com.google.gson.JsonObject;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import org.jspecify.annotations.Nullable;

/**
 * Keeps every dimension that runs do not reset on the world's original seed.
 *
 * <p>Activation rebinds the server-global seed to the run seed, and a vanilla {@code ServerLevel} reads
 * that global seed: once at construction for terrain and structure placement, and on every chunk for
 * feature decoration. Without a pin, a lobby or an un-reset modded dimension would decorate new chunks
 * with each run's seed straight away and, after a restart, generate terrain that no longer meets the
 * old chunks. The original seed is recorded the first time the library opens the world.
 *
 * <p>Pinning only applies once the host mod has supplied its {@link DimensionPolicy}. Levels that
 * supply their own seed (staged levels, or a mod's subclass overriding {@code getSeed}) are unaffected.
 */
public final class PinnedSeed {
    private static volatile @Nullable DimensionPolicy policy;
    private static volatile @Nullable Long seed;

    private PinnedSeed() {
    }

    /** Called by the host mod during initialization, before any world is opened. */
    public static void setPolicy(DimensionPolicy hostPolicy) {
        policy = hostPolicy;
    }

    /** Loads the world's pinned seed, recording {@code currentSeed} if the world has none yet. */
    static void load(WorldPaths paths, long currentSeed) throws IOException {
        Path file = paths.pinnedSeed();
        if (Files.isRegularFile(file)) {
            seed = Json.GSON.fromJson(Files.readString(file), JsonObject.class).get("seed").getAsLong();
            return;
        }
        if (Files.exists(paths.journal())) {
            TerraRasa.LOGGER.warn("Pinning dimensions that are not reset to the current seed {}; the world's "
                    + "original seed predates this version and is no longer known", currentSeed);
        }
        JsonObject json = new JsonObject();
        json.addProperty("seed", currentSeed);
        AtomicFiles.writeString(file, Json.GSON.toJson(json));
        seed = currentSeed;
    }

    /** The seed {@code dimension} keeps, or null when it follows the server-global seed. */
    public static @Nullable Long forDimension(ResourceKey<Level> dimension) {
        DimensionPolicy current = policy;
        Long pinned = seed;
        if (current == null || pinned == null) {
            return null;
        }
        DimensionClass type = current.classify(dimension.identifier());
        return type == DimensionClass.PERSISTENT_SERVICE || type == DimensionClass.EXTERNAL ? pinned : null;
    }
}
