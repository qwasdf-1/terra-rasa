package dev.terrarasa;

import java.util.function.Supplier;

import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import org.jspecify.annotations.Nullable;

/**
 * Supplies an independent seed to a staged level while it is being constructed on the server thread.
 *
 * <p>Staged levels are generated while the previous run is still active, so they cannot use the
 * server-global seed. The override is scoped to one dimension key and one constructor call; the
 * level then keeps the value for its lifetime (see {@code ServerLevelMixin}).
 */
public final class SeedOverride {
    private static final ThreadLocal<Pending> PENDING = new ThreadLocal<>();

    private SeedOverride() {
    }

    public static <T> T construct(ResourceKey<Level> dimension, long seed, Supplier<T> constructor) {
        if (PENDING.get() != null) {
            throw new IllegalStateException("Nested seed-override level construction for " + dimension.identifier());
        }
        PENDING.set(new Pending(dimension, seed));
        try {
            return constructor.get();
        } finally {
            PENDING.remove();
        }
    }

    public static @Nullable Long pendingFor(ResourceKey<Level> dimension) {
        Pending pending = PENDING.get();
        return pending != null && pending.dimension().equals(dimension) ? pending.seed() : null;
    }

    /** Implemented by {@code ServerLevel} through a mixin. */
    public interface Holder {
        @Nullable Long terrarasa$seedOverride();
    }

    private record Pending(ResourceKey<Level> dimension, long seed) {
    }
}
