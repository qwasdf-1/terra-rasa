package dev.terrarasa.compat;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import dev.terrarasa.CanonicalLevelEvents;
import dev.terrarasa.LifecycleException;
import dev.terrarasa.TerraRasa;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

/**
 * Chunky (pregenerator) caches level wrappers: its selection keeps the {@code ServerLevel} it was made
 * for, and running tasks hold theirs. After a swap, {@code /chunky start} would otherwise "complete" a task
 * against the closed level and generate nothing.
 *
 * <p>Before retirement, tasks in the retiring levels are cancelled: a pregeneration task for the old world
 * means nothing in a new one. Afterwards, a selection pointing at a replaced level is rebound to the new
 * level of the same name. Only Chunky's public API and accessors are used, through reflection so Terra Rasa
 * does not depend on Chunky at build time. If they change, activation is refused rather than
 * silently left inconsistent.
 */
public final class ChunkyCompat {
    public static final String MOD_ID = "chunky";

    private ChunkyCompat() {
    }

    public static void register() {
        CanonicalLevelEvents.BEFORE_RETIRE.register(ChunkyCompat::cancelTasks);
        CanonicalLevelEvents.REPLACED.register(ChunkyCompat::rebindSelection);
        TerraRasa.LOGGER.info("Chunky detected: tasks and selections follow canonical level replacement");
    }

    private static void cancelTasks(MinecraftServer server, List<ServerLevel> retiring) throws LifecycleException {
        try {
            Object api = call(chunky(), "getApi");
            for (String name : names(retiring)) {
                if ((Boolean) call(api, "isRunning", name)) {
                    call(api, "cancelTask", name);
                    TerraRasa.LOGGER.warn("Cancelled the Chunky task in {}: that world is being replaced", name);
                }
            }
        } catch (ReflectiveOperationException | ClassCastException e) {
            throw new LifecycleException("Chunky's API changed; refusing to replace levels it may still be generating", e);
        }
    }

    private static void rebindSelection(MinecraftServer server, List<ServerLevel> replacements) {
        try {
            Object chunky = chunky();
            Object selection = call(chunky, "getSelection");
            Object world = call(call(selection, "build"), "world");
            String name = (String) call(world, "getName");
            if (!names(replacements).contains(name)) {
                return;
            }
            Optional<?> fresh = (Optional<?>) call(call(chunky, "getServer"), "getWorld", name);
            if (fresh.isPresent()) {
                Method setter = selection.getClass().getMethod("world", Class.forName("org.popcraft.chunky.platform.World",
                        false, selection.getClass().getClassLoader()));
                setter.invoke(selection, fresh.get());
            }
        } catch (ReflectiveOperationException | ClassCastException e) {
            TerraRasa.LOGGER.error("Could not rebind Chunky's selection to the replaced level; run /chunky world", e);
        }
    }

    private static Object chunky() throws ReflectiveOperationException {
        return Class.forName("org.popcraft.chunky.ChunkyProvider").getMethod("get").invoke(null);
    }

    private static Set<String> names(List<ServerLevel> levels) {
        return levels.stream().map(level -> level.dimension().identifier().toString()).collect(Collectors.toSet());
    }

    private static Object call(Object target, String method, Object... arguments) throws ReflectiveOperationException {
        Class<?>[] types = new Class<?>[arguments.length];
        for (int i = 0; i < arguments.length; i++) {
            types[i] = arguments[i].getClass();
        }
        Method found = findPublic(target.getClass(), method, types);
        return found.invoke(target, arguments);
    }

    /** Finds the method on a public type, so reflection works for package-private implementation classes. */
    private static Method findPublic(Class<?> type, String name, Class<?>[] parameters) throws NoSuchMethodException {
        Method method = type.getMethod(name, parameters);
        if (java.lang.reflect.Modifier.isPublic(method.getDeclaringClass().getModifiers())) {
            return method;
        }
        for (Class<?> candidate : type.getInterfaces()) {
            try {
                return findPublic(candidate, name, parameters);
            } catch (NoSuchMethodException ignored) {
                // Try the next interface.
            }
        }
        if (type.getSuperclass() != null) {
            return findPublic(type.getSuperclass(), name, parameters);
        }
        throw new NoSuchMethodException(type.getName() + "." + name);
    }
}
