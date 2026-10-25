package dev.terrarasa;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.storage.LevelResource;
import org.jspecify.annotations.Nullable;

/**
 * Pregeneration that continues in the live canonical worlds: whatever of a run's area was not generated
 * before the run started. Owned by the library and ticked at the end of every server tick, at background
 * priority. Progress is saved ({@link PregenerationProgress}) every few seconds and at
 * shutdown, so a restart continues where it stopped; a crash repeats at most those few seconds of work,
 * and a chunk that is already on disk is only loaded, not generated again.
 */
public final class LivePregeneration {
    private static final int SAVE_INTERVAL_TICKS = 100;

    private static final List<PregenerationTask> TASKS = new ArrayList<>();
    /** Tasks bound to the level objects they currently generate in; rebuilt whenever a level is replaced. */
    private static final Map<PregenerationTask, Pregenerator> RUNNING = new IdentityHashMap<>();
    private static @Nullable PregenerationProgress progress;
    private static boolean dirty;
    private static int ticks;

    private LivePregeneration() {
    }

    /** True while live pregeneration has work left; an empty server stays awake until it is done. */
    public static boolean keepsServerAwake() {
        return !TASKS.isEmpty();
    }

    static void onServerStarted(MinecraftServer server) {
        progress = new PregenerationProgress(
                new WorldPaths(server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize()).pregeneration());
        TASKS.clear();
        RUNNING.clear();
        TASKS.addAll(progress.load());
        if (!TASKS.isEmpty()) {
            TerraRasa.LOGGER.info("Resuming pregeneration of {} area(s)", TASKS.size());
        }
    }

    static void tick(MinecraftServer server) {
        if (TASKS.isEmpty()) {
            return;
        }
        boolean finished = false;
        Iterator<PregenerationTask> tasks = TASKS.iterator();
        while (tasks.hasNext()) {
            PregenerationTask task = tasks.next();
            ServerLevel level = server.getLevel(CanonicalActivationService.dimensionKey(Identifier.parse(task.dimension)));
            Pregenerator running = RUNNING.get(task);
            if (running != null && running.level() != level) {
                // The level was replaced (a swap rolled back and reopened it): continue in the new object.
                task.next = running.snapshot().next;
                running.abandon();
                running = null;
            }
            if (level == null) {
                continue;
            }
            if (running == null) {
                running = new Pregenerator(level, task);
                RUNNING.put(task, running);
            }
            running.tick(server);
            if (running.isDone()) {
                running.release();
                RUNNING.remove(task);
                tasks.remove();
                TerraRasa.LOGGER.info("Pregenerated {} chunks of {}", task.total(), task.dimension);
                finished = true;
            }
            dirty = true;
        }
        if (finished || (++ticks % SAVE_INTERVAL_TICKS == 0 && dirty)) {
            save(false);
        }
    }

    /** Before the canonical levels are retired: let go of them, keeping the progress. */
    static void pause() {
        for (Map.Entry<PregenerationTask, Pregenerator> entry : RUNNING.entrySet()) {
            entry.getKey().next = entry.getValue().snapshot().next;
            entry.getValue().release();
        }
        RUNNING.clear();
    }

    /** After a swap commits: the new run's remaining areas replace the previous run's. */
    static void replace(List<PregenerationTask> remaining) {
        pause();
        TASKS.clear();
        for (PregenerationTask task : remaining) {
            if (!task.isComplete()) {
                TASKS.add(task.copy());
            }
        }
        save(true);
    }

    static void onServerStopping() {
        pause();
        save(true);
        TASKS.clear();
    }

    private static void save(boolean wait) {
        PregenerationProgress target = progress;
        if (target == null) {
            return;
        }
        List<PregenerationTask> snapshot = new ArrayList<>();
        for (PregenerationTask task : TASKS) {
            Pregenerator running = RUNNING.get(task);
            snapshot.add(running != null ? running.snapshot() : task.copy());
        }
        dirty = false;
        target.save(snapshot, wait);
    }

    public static JsonObject describe() {
        JsonObject json = new JsonObject();
        JsonArray areas = new JsonArray();
        for (PregenerationTask task : TASKS) {
            Pregenerator running = RUNNING.get(task);
            PregenerationTask current = running != null ? running.snapshot() : task;
            JsonObject area = new JsonObject();
            area.addProperty("dimension", task.dimension);
            area.addProperty("done", current.next);
            area.addProperty("total", task.total());
            areas.add(area);
        }
        json.add("live", areas);
        return json;
    }
}
