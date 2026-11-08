package dev.terrarasa;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

/**
 * Notifications around canonical level replacement, for mods that keep their own references to
 * {@link ServerLevel} objects.
 *
 * <p>Every activation replaces each resettable canonical level with a new object under the same key, and
 * so does a rollback: the restored levels are rebuilt from the previous run's data. A mod that caches a
 * level must drop or rebind that reference on {@link #registerReplaced}, or it keeps operating on a closed
 * level. The loader's own world load and unload events fire as well; these events add the pairing between
 * the old and new objects and fire once per swap.
 *
 * <p>Listeners are held in a plain list rather than a loader event bus, so this API is identical on
 * every loader Terra Rasa supports.
 */
public final class CanonicalLevelEvents {
    private static final List<BeforeRetire> BEFORE_RETIRE_LISTENERS = new CopyOnWriteArrayList<>();
    private static final List<Replaced> REPLACED_LISTENERS = new CopyOnWriteArrayList<>();

    /**
     * Adds a listener that runs on the server thread before the first level closes, with every level
     * about to be retired. A listener that throws aborts the activation before anything is retired.
     */
    public static void registerBeforeRetire(BeforeRetire listener) {
        BEFORE_RETIRE_LISTENERS.add(listener);
    }

    /** Adds a listener that runs on the server thread once the replacement levels are live. */
    public static void registerReplaced(Replaced listener) {
        REPLACED_LISTENERS.add(listener);
    }

    /** Fires on the server thread before the first level closes, with every level about to be retired. */
    public static void fireBeforeRetire(MinecraftServer server, List<ServerLevel> retiring) throws LifecycleException {
        for (BeforeRetire listener : BEFORE_RETIRE_LISTENERS) {
            listener.beforeRetire(server, retiring);
        }
    }

    /**
     * Fires on the server thread once the replacement levels are live, after a committed activation or a
     * rollback. Listener failures are logged; the swap has already happened.
     */
    public static void fireReplaced(MinecraftServer server, List<ServerLevel> replacements) {
        List<ServerLevel> snapshot = List.copyOf(replacements);
        for (Replaced listener : REPLACED_LISTENERS) {
            try {
                listener.onReplaced(server, snapshot);
            } catch (RuntimeException e) {
                TerraRasa.LOGGER.error("A canonical level replacement listener failed", e);
            }
        }
    }

    private CanonicalLevelEvents() {
    }

    @FunctionalInterface
    public interface BeforeRetire {
        void beforeRetire(MinecraftServer server, List<ServerLevel> retiring) throws LifecycleException;
    }

    @FunctionalInterface
    public interface Replaced {
        void onReplaced(MinecraftServer server, List<ServerLevel> replacements);
    }
}
