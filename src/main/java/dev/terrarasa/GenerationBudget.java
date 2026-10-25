package dev.terrarasa;

import java.util.IdentityHashMap;
import java.util.Map;

import net.minecraft.server.MinecraftServer;

/**
 * One cap on the chunks Terra Rasa generates at once, shared by everything it generates: the next run's
 * spawn area, its pregeneration, and pregeneration that continues in the live worlds. Server thread only.
 *
 * <p>Generation runs on vanilla's worker threads, but every chunk it finishes costs the server thread
 * some work, and a large batch keeps every core busy. A small cap spreads both over more ticks.
 */
final class GenerationBudget {
    /** Chunks generating at once while players are online. */
    static final int MAX_LOADING_CHUNKS = 6;
    /** With nobody online there is nobody to protect from the CPU use; generate faster. */
    static final int MAX_LOADING_CHUNKS_EMPTY_SERVER = 16;
    /**
     * Above this smoothed server tick time, nothing new starts generating, except one chunk when nothing
     * is generating at all, so a busy server still makes progress.
     */
    static final float BUSY_TICK_MILLIS = 30.0F;

    private static final Map<Object, Integer> LOADING = new IdentityHashMap<>();
    /** Owners of essential work (the next run's spawn area) that still have chunks to start. */
    private static final Map<Object, Boolean> ESSENTIAL_WAITING = new IdentityHashMap<>();

    private GenerationBudget() {
    }

    /**
     * How many more chunks {@code owner} may start generating now. Background work (pregeneration) waits
     * while essential work has chunks to start.
     */
    static int available(MinecraftServer server, Object owner, boolean background) {
        if (background && !ESSENTIAL_WAITING.isEmpty()) {
            return 0;
        }
        int loading = LOADING.values().stream().mapToInt(Integer::intValue).sum();
        int cap;
        if (server.getCurrentSmoothedTickTime() > BUSY_TICK_MILLIS) {
            cap = 1;
        } else {
            cap = server.getPlayerList().getPlayerCount() == 0 ? MAX_LOADING_CHUNKS_EMPTY_SERVER : MAX_LOADING_CHUNKS;
        }
        return Math.max(0, cap - loading);
    }

    /** Records how many chunks {@code owner} currently has generating. */
    static void report(Object owner, int loading) {
        if (loading > 0) {
            LOADING.put(owner, loading);
        } else {
            LOADING.remove(owner);
        }
    }

    static void essentialWaiting(Object owner, boolean waiting) {
        if (waiting) {
            ESSENTIAL_WAITING.put(owner, Boolean.TRUE);
        } else {
            ESSENTIAL_WAITING.remove(owner);
        }
    }

    /** Server stop: owners are gone with their levels. */
    static void clear() {
        LOADING.clear();
        ESSENTIAL_WAITING.clear();
    }
}
