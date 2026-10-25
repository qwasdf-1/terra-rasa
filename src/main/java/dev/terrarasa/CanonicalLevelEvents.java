package dev.terrarasa;

import java.util.List;

import net.fabricmc.fabric.api.event.Event;
import net.fabricmc.fabric.api.event.EventFactory;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

/**
 * Notifications around canonical level replacement, for mods that keep their own references to
 * {@link ServerLevel} objects.
 *
 * <p>Every activation replaces each resettable canonical level with a new object under the same key, and
 * so does a rollback: the restored levels are rebuilt from the previous run's data. A mod that caches a
 * level must drop or rebind that reference on {@link #REPLACED}, or it keeps operating on a closed level.
 * Fabric's {@code ServerLevelEvents.UNLOAD} and {@code LOAD} fire as well; these events add the pairing
 * between the old and new objects and fire once per swap.
 */
public final class CanonicalLevelEvents {
    /**
     * Fires on the server thread before the first level closes, with every level about to be retired. A
     * listener that throws aborts the activation before anything is retired.
     */
    public static final Event<BeforeRetire> BEFORE_RETIRE = EventFactory.createArrayBacked(BeforeRetire.class,
            listeners -> (server, retiring) -> {
                for (BeforeRetire listener : listeners) {
                    listener.beforeRetire(server, retiring);
                }
            });

    /**
     * Fires on the server thread once the replacement levels are live, after a committed activation or a
     * rollback. Listener failures are logged; the swap has already happened.
     */
    public static final Event<Replaced> REPLACED = EventFactory.createArrayBacked(Replaced.class,
            listeners -> (server, replacements) -> {
                for (Replaced listener : listeners) {
                    listener.onReplaced(server, replacements);
                }
            });

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

    static void fireReplaced(MinecraftServer server, List<ServerLevel> replacements) {
        try {
            REPLACED.invoker().onReplaced(server, List.copyOf(replacements));
        } catch (RuntimeException e) {
            TerraRasa.LOGGER.error("A canonical level replacement listener failed", e);
        }
    }
}
