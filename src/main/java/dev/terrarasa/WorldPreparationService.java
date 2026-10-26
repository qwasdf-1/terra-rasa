package dev.terrarasa;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.stream.Stream;

import com.google.gson.JsonObject;
import dev.terrarasa.mixin.MinecraftServerAccessor;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.PlayerSpawnFinder;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Prepares the next run under staging identities while the current run keeps playing.
 *
 * <p>Everything advances one step per server tick from {@link #tick}; nothing here blocks on world
 * generation. Staged levels are registered in the server's level map only so the server ticks their chunk
 * systems; they are never canonical and never contain players. A run becomes adoptable only after every
 * staged level was saved and fully closed and its manifest was durably written as
 * {@link PreparationState#CLOSED_READY}.
 */
public final class WorldPreparationService {
    /** Non-persistent loading ticket: preload tickets must never leak into saved ticket data. */
    public static final TicketType PRELOAD_TICKET = Registry.register(BuiltInRegistries.TICKET_TYPE,
            Identifier.fromNamespaceAndPath(TerraRasa.MOD_ID, "preload"), new TicketType(0L, TicketType.FLAG_LOADING));
    private static final AtomicInteger ACTIVE_PREPARATIONS = new AtomicInteger();
    /** After this, close anyway and save what is left synchronously. */
    private static final long FLUSH_WAIT_MILLIS = 60_000L;
    /**
     * Chunk tickets added per tick. Each ticket eventually costs the server thread a chunk promotion to
     * full status; adding a few per tick spreads that work instead of finishing a whole square in one tick.
     */
    private static final int TICKETS_PER_TICK = 8;
    /** Chunks the spawn spiral examines per tick. */
    private static final int SPAWN_CHECKS_PER_TICK = 8;
    /** Vanilla's initial-spawn spiral searches an 11x11 chunk area around the climate spawn chunk. */
    private static final int SPAWN_SEARCH_RADIUS = 5;

    private final WorldPaths paths;
    private final int preloadRadius;
    /** Pregeneration radius in blocks per resettable dimension; missing or 0 means none. */
    private final Map<Identifier, Integer> pregenerationRadii;
    private final Duration timeout;
    private final Duration drainTimeout;
    private final Consumer<ServerPlayer> evacuator;
    /** Manifest files are written off the server thread, in order. */
    private final ExecutorService manifestWriter = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "Terra Rasa manifest writer");
        thread.setDaemon(true);
        return thread;
    });
    private CompletableFuture<Void> lastManifestWrite = CompletableFuture.completedFuture(null);
    private @Nullable Preparation active;
    private @Nullable RunManifest ready;
    private @Nullable String lastFailure;

    /**
     * @param evacuator moves a player out of a staged level before it closes. Staged levels never hold
     *        players on their own, but an operator can teleport into one; the host decides where they go.
     */
    public WorldPreparationService(WorldPaths paths, int preloadRadius, Duration timeout, Duration drainTimeout,
            Consumer<ServerPlayer> evacuator) {
        this(paths, preloadRadius, Map.of(), timeout, drainTimeout, evacuator);
    }

    /**
     * @param pregenerationRadii radius in blocks to pregenerate per resettable dimension, around the run
     *        spawn (scaled for the Nether) or the End's origin. Pregeneration runs after the spawn area is
     *        ready and stops early when a start is requested ({@link #finishSoon}); the rest continues in
     *        the live world after the swap.
     * @param timeout bounds everything up to the spawn area; pregeneration that is still running at the
     *        timeout stops there instead of failing the preparation.
     */
    public WorldPreparationService(WorldPaths paths, int preloadRadius, Map<Identifier, Integer> pregenerationRadii,
            Duration timeout, Duration drainTimeout, Consumer<ServerPlayer> evacuator) {
        this.paths = paths;
        this.preloadRadius = preloadRadius;
        this.pregenerationRadii = Map.copyOf(pregenerationRadii);
        this.timeout = timeout;
        this.drainTimeout = drainTimeout;
        this.evacuator = evacuator;
    }

    public boolean isPreparing() {
        return this.active != null;
    }

    /**
     * True while any preparation is in progress. Vanilla pauses an empty server after
     * {@code pause-when-empty-seconds}, which would freeze chunk generation halfway; the server stays
     * awake until the preparation finishes and then pauses as usual.
     */
    public static boolean keepsServerAwake() {
        return ACTIVE_PREPARATIONS.get() > 0;
    }

    private void setActive(@Nullable Preparation preparation) {
        if ((this.active == null) != (preparation == null)) {
            ACTIVE_PREPARATIONS.addAndGet(preparation != null ? 1 : -1);
        }
        this.active = preparation;
    }

    /**
     * A start was requested: stop pregenerating and get the prepared run ready as soon as possible. What
     * is not pregenerated yet continues in the live world. No effect before the spawn area is ready.
     */
    public void finishSoon() {
        if (this.active != null) {
            this.active.finishRequested = true;
        }
    }

    public Optional<RunManifest> ready() {
        return Optional.ofNullable(this.ready);
    }

    public @Nullable String activeFingerprint() {
        return this.active != null ? this.active.manifest.fingerprint : null;
    }

    public @Nullable String lastFailure() {
        return this.lastFailure;
    }

    /** Hands the ready run to activation. If activation rolls back, {@link #restoreReady} returns it. */
    public RunManifest takeReady() {
        RunManifest manifest = this.ready;
        if (manifest == null) {
            throw new IllegalStateException("No prepared run is ready");
        }
        this.ready = null;
        return manifest;
    }

    public void restoreReady(RunManifest manifest) {
        this.ready = manifest;
    }

    /**
     * Startup: adopt a durable CLOSED_READY run whose fingerprint still matches; delete everything else.
     * Runs before any staged level is opened, so deleting stage directories cannot race a writer.
     */
    public void loadFromDisk(MinecraftServer server, DimensionPolicy policy) {
        String fingerprint = RegistryFingerprint.compute(server, policy);
        List<UUID> candidates = new ArrayList<>();
        if (Files.isDirectory(this.paths.stageManifests())) {
            try (Stream<Path> runs = Files.list(this.paths.stageManifests())) {
                runs.forEach(dir -> {
                    try {
                        candidates.add(UUID.fromString(dir.getFileName().toString()));
                    } catch (IllegalArgumentException e) {
                        TerraRasa.LOGGER.warn("Ignoring unexpected staging entry {}", dir);
                    }
                });
            } catch (IOException e) {
                TerraRasa.LOGGER.error("Could not list staged runs", e);
            }
        }
        Path stageRoot = this.paths.worldRoot().resolve("dimensions").resolve(StageKeys.NAMESPACE).resolve("stage");
        if (Files.isDirectory(stageRoot)) {
            try (Stream<Path> dirs = Files.list(stageRoot)) {
                dirs.forEach(dir -> {
                    try {
                        UUID id = UUID.fromString(dir.getFileName().toString());
                        if (!candidates.contains(id)) {
                            candidates.add(id);
                        }
                    } catch (IllegalArgumentException ignored) {
                    }
                });
            } catch (IOException e) {
                TerraRasa.LOGGER.error("Could not list staged dimension data", e);
            }
        }
        for (UUID runId : candidates) {
            Optional<RunManifest> manifest = readManifest(runId);
            boolean adoptable = manifest.isPresent()
                    && manifest.get().state == PreparationState.CLOSED_READY
                    && fingerprint.equals(manifest.get().fingerprint)
                    && sameTargets(manifest.get(), policy)
                    && manifest.get().dimensions.stream().allMatch(d -> Files.isDirectory(
                            this.paths.dimension(Identifier.parse(d.stage))))
                    && this.ready == null;
            if (adoptable) {
                this.ready = manifest.get();
                TerraRasa.LOGGER.info("Adopted prepared run {} (seed {}) from disk", runId, this.ready.seed);
            } else {
                TerraRasa.LOGGER.info("Discarding stale or incomplete staged run {}", runId);
                deleteStageData(runId);
            }
        }
    }

    /** Starts preparing a run with the given seed. Caller guarantees no preparation is active. */
    public void begin(MinecraftServer server, long seed, DimensionPolicy policy) throws LifecycleException {
        begin(server, seed, policy, this.preloadRadius);
    }

    public void begin(MinecraftServer server, long seed, DimensionPolicy policy, int preloadRadius) throws LifecycleException {
        LevelRetirement.assertServerThread(server);
        if (this.active != null) {
            throw new LifecycleException("A run is already being prepared");
        }
        Registry<LevelStem> stems = server.registryAccess().lookupOrThrow(Registries.LEVEL_STEM);
        List<Identifier> stemIds = stems.keySet().stream().toList();
        List<String> problems = policy.validate(stemIds);
        if (!problems.isEmpty()) {
            throw new LifecycleException("Dimension policy preflight failed: " + String.join("; ", problems));
        }

        long beginStarted = System.nanoTime();
        UUID runId = UUID.randomUUID();
        RunManifest manifest = RunManifest.create(runId, seed, SharedConstants.getCurrentVersion().id(),
                RegistryFingerprint.compute(server, policy));
        manifest.preloadRadius = preloadRadius;
        Preparation preparation = new Preparation(runId, seed, manifest, preloadRadius);
        setActive(preparation);
        this.lastFailure = null;
        try {
            Map<ResourceKey<Level>, ServerLevel> levels = ((MinecraftServerAccessor) server).terrarasa$levels();
            for (Identifier target : policy.resettable()) {
                LevelStem stem = stems.getValue(target);
                if (stem == null) {
                    throw new LifecycleException("No level stem for " + target);
                }
                Identifier stageId = StageKeys.stageId(runId, target);
                ResourceKey<Level> stageKey = ResourceKey.create(Registries.DIMENSION, stageId);
                if (levels.containsKey(stageKey) || Files.exists(this.paths.dimension(stageId))) {
                    throw new LifecycleException("Staging identity collision for " + stageId);
                }
                preparation.toConstruct.add(new StagedLevel(target, stageKey, stem));
                manifest.dimensions.add(new RunManifest.Dimension(target.toString(), stageId.toString()));
            }
            // The levels themselves are built one per tick (tickCreating).
            writeManifest(runId, manifest);
            manifest.timingsMillis.put("begin", (System.nanoTime() - beginStarted) / 1_000_000L);
            TerraRasa.LOGGER.info("Preparing run {} with seed {} ({} dimensions)", runId, seed, manifest.dimensions.size());
        } catch (RuntimeException | LifecycleException e) {
            fail(server, "Could not start preparation: " + e);
            throw e instanceof LifecycleException le ? le : new LifecycleException("Could not start preparation", e);
        }
    }

    public void tick(MinecraftServer server) {
        Preparation preparation = this.active;
        if (preparation == null) {
            return;
        }
        try {
            if (preparation.elapsed().compareTo(this.timeout) > 0) {
                if (preparation.state != PreparationState.PREGENERATING) {
                    throw new LifecycleException("Preparation timed out in state " + preparation.state);
                }
                preparation.finishRequested = true;
            }
            PreparationState state = preparation.state;
            long started = System.nanoTime();
            preparation.chunks.applyQueued(TICKETS_PER_TICK, server);
            preparation.manifest.timingsMillis.merge("maxTick.tickets", (System.nanoTime() - started) / 1_000_000L, Math::max);
            switch (state) {
                case CREATING -> tickCreating(server, preparation);
                case SPAWN_FINDING -> tickSpawnFinding(preparation);
                case PRELOADING -> tickPreloading(preparation);
                case PREGENERATING -> tickPregenerating(server, preparation);
                case FLUSHING -> tickFlushing(server, preparation);
                case PUBLISHING -> tickPublishing(preparation);
                default -> {
                }
            }
            // The longest single server-thread step per phase: what a player could feel as a hitch.
            String phase = state.name().toLowerCase(java.util.Locale.ROOT);
            preparation.manifest.timingsMillis.merge("maxTick." + phase, (System.nanoTime() - started) / 1_000_000L, Math::max);
            // The whole server tick (all levels, staged ones included) during the phase, for comparison.
            long serverTick = server.getTickTimesNanos()[server.getTickCount() % server.getTickTimesNanos().length];
            preparation.manifest.timingsMillis.merge("maxServerTick." + phase, serverTick / 1_000_000L, Math::max);
        } catch (LifecycleException | IOException | RuntimeException e) {
            TerraRasa.LOGGER.error("Run preparation {} failed", preparation.runId, e);
            fail(server, e.toString());
        }
    }

    /**
     * Builds the active preparation's levels now instead of one per tick. Constructing a level cannot be
     * split; with some mods (C2ME compiles density functions per level) it takes seconds. A host can call
     * this where players already wait, such as right after a swap.
     */
    public void constructNow(MinecraftServer server) {
        Preparation preparation = this.active;
        if (preparation == null) {
            return;
        }
        try {
            while (preparation.state == PreparationState.CREATING && this.active == preparation) {
                tickCreating(server, preparation);
            }
        } catch (RuntimeException e) {
            TerraRasa.LOGGER.error("Run preparation {} failed", preparation.runId, e);
            fail(server, e.toString());
        }
    }

    /** Builds one staged level per tick: constructing a level is a few to a few hundred milliseconds. */
    private void tickCreating(MinecraftServer server, Preparation preparation) {
        StagedLevel next = preparation.toConstruct.poll();
        if (next != null) {
            ServerLevel level = LevelFactory.createStaged(server, next.key(), next.stem(), preparation.seed);
            ((MinecraftServerAccessor) server).terrarasa$levels().put(next.key(), level);
            MinecraftCompat.levelLoaded(server, level);
            preparation.levels.put(next.target(), level);
            return;
        }
        preparation.manifest.timingsMillis.put("construct", preparation.phaseElapsedMillis());
        preparation.manifest.state = PreparationState.SPAWN_FINDING;
        writeManifest(preparation.runId, preparation.manifest);
        preparation.enter(PreparationState.SPAWN_FINDING);
    }

    private void tickSpawnFinding(Preparation preparation) {
        ServerLevel overworld = preparation.overworld();
        if (preparation.spawnSearchCenter == null) {
            // Vanilla performs its climate search off-thread and returns the selected spawn position.
            if (preparation.climateSearch == null) {
                preparation.climateSearch = PlayerSpawnFinder.findSpawn(overworld, BlockPos.ZERO);
            }
            if (!preparation.climateSearch.isDone()) {
                return;
            }
            Vec3 spawn = preparation.climateSearch.join();
            preparation.spawnSearchCenter = MinecraftCompat.chunkAt(BlockPos.containing(spawn.x, 0, spawn.z));
            preparation.chunks.add(overworld, preparation.spawnSearchCenter, SPAWN_SEARCH_RADIUS);
            return;
        }
        if (preparation.chunks.hasQueuedAdds()
                || countFull(overworld, preparation.spawnSearchCenter, SPAWN_SEARCH_RADIUS) < square(SPAWN_SEARCH_RADIUS)) {
            return;
        }
        if (preparation.spawnSearch == null) {
            preparation.spawnSearch = new SpawnSpiral(overworld, preparation.spawnSearchCenter);
        }
        BlockPos spawn = preparation.spawnSearch.advance(SPAWN_CHECKS_PER_TICK);
        if (spawn == null) {
            return;
        }
        preparation.manifest.spawn = new RunManifest.Spawn(spawn.getX(), spawn.getY(), spawn.getZ());
        preparation.manifest.timingsMillis.put("spawn_finding", preparation.phaseElapsedMillis());
        preparation.preloadCenter = MinecraftCompat.chunkAt(spawn);
        preparation.chunks.add(overworld, preparation.preloadCenter, preparation.preloadRadius);
        preparation.enter(PreparationState.PRELOADING);
    }

    private void tickPreloading(Preparation preparation) {
        ServerLevel overworld = preparation.overworld();
        if (preparation.chunks.hasQueuedAdds()) {
            return;
        }
        // Released only once every preload ticket is in place, so chunks shared by both squares never unload.
        if (preparation.spawnSearchCenter != null) {
            preparation.chunks.remove(overworld, preparation.spawnSearchCenter, SPAWN_SEARCH_RADIUS);
            preparation.spawnSearchCenter = null;
        }
        preparation.preloadedChunks = countFull(overworld, preparation.preloadCenter, preparation.preloadRadius);
        if (preparation.preloadedChunks < square(preparation.preloadRadius)) {
            return;
        }
        preparation.manifest.timingsMillis.put("preloading", preparation.phaseElapsedMillis());
        BlockPos spawn = new BlockPos(preparation.manifest.spawn.x, preparation.manifest.spawn.y, preparation.manifest.spawn.z);
        for (Map.Entry<Identifier, ServerLevel> staged : preparation.levels.entrySet()) {
            int radius = this.pregenerationRadii.getOrDefault(staged.getKey(), 0);
            if (radius > 0) {
                PregenerationTask task = PregenerationTask.around(staged.getKey(), staged.getValue(), spawn, radius);
                preparation.manifest.pregeneration.add(task);
                preparation.pregenerators.add(new Pregenerator(staged.getValue(), task));
            }
        }
        preparation.enter(PreparationState.PREGENERATING);
    }

    /** Generates the wider area, keeping the spawn area loaded, until done or until a start is requested. */
    private void tickPregenerating(MinecraftServer server, Preparation preparation) {
        boolean done = true;
        for (Pregenerator pregenerator : preparation.pregenerators) {
            if (!preparation.finishRequested) {
                pregenerator.tick(server);
            }
            done &= pregenerator.isDone();
        }
        if (!done && !preparation.finishRequested) {
            return;
        }
        preparation.manifest.pregeneration.clear();
        for (Pregenerator pregenerator : preparation.pregenerators) {
            preparation.manifest.pregeneration.add(pregenerator.snapshot());
            pregenerator.release();
        }
        preparation.pregenerators.clear();
        preparation.manifest.timingsMillis.put("pregenerating", preparation.phaseElapsedMillis());
        preparation.chunks.queueReleaseAll();
        preparation.enter(PreparationState.FLUSHING);
    }

    private void tickFlushing(MinecraftServer server, Preparation preparation) throws LifecycleException, IOException {
        // Let the normal server tick unload the released chunks: vanilla saves each one through the IO worker
        // as it unloads, a little per tick. Closing earlier would save every chunk still loaded in one tick.
        preparation.chunks.releaseQueued(TICKETS_PER_TICK);
        boolean busy = preparation.chunks.hasQueuedReleases() || preparation.levels.values().stream().anyMatch(level ->
                level.getChunkSource().chunkMap.hasWork() || level.getChunkSource().getLoadedChunksCount() > 0);
        if (busy && preparation.phaseElapsedMillis() < FLUSH_WAIT_MILLIS) {
            return;
        }
        // Then write everything out in the background; the close below finds nothing left to wait for.
        if (preparation.ioFlush == null) {
            preparation.ioFlush = CompletableFuture.allOf(preparation.levels.values().stream()
                    .map(LevelIo::flushAsync).toArray(CompletableFuture[]::new));
        }
        if (!preparation.ioFlush.isDone() && preparation.phaseElapsedMillis() < FLUSH_WAIT_MILLIS) {
            return;
        }
        // One level per tick: each close still flushes region files.
        Map<ResourceKey<Level>, ServerLevel> levels = ((MinecraftServerAccessor) server).terrarasa$levels();
        Iterator<ServerLevel> open = preparation.levels.values().iterator();
        if (open.hasNext()) {
            ServerLevel level = open.next();
            evacuate(level);
            LevelRetirement.drainSaveAndClose(server, level, this.drainTimeout);
            levels.remove(level.dimension(), level);
            open.remove();
            if (!preparation.levels.isEmpty()) {
                return;
            }
        }
        RunManifest manifest = preparation.manifest;
        manifest.timingsMillis.put("flushing", preparation.phaseElapsedMillis());
        manifest.timingsMillis.put("total", preparation.elapsed().toMillis());
        manifest.state = PreparationState.CLOSED_READY;
        // The run is adoptable only once this manifest is durable; publish it when the write has landed.
        preparation.published = writeManifest(preparation.runId, manifest);
        preparation.enter(PreparationState.PUBLISHING);
    }

    private void tickPublishing(Preparation preparation) throws LifecycleException {
        CompletableFuture<Void> published = Objects.requireNonNull(preparation.published);
        if (!published.isDone()) {
            return;
        }
        if (published.isCompletedExceptionally()) {
            throw new LifecycleException("Could not write the manifest of run " + preparation.runId, published.exceptionNow());
        }
        RunManifest manifest = preparation.manifest;
        setActive(null);
        if (this.ready == null) {
            this.ready = manifest;
        } else {
            // A pool of size one: keep the older ready run, discard this one.
            deleteStageData(preparation.runId);
        }
        TerraRasa.LOGGER.info("Run {} is prepared (seed {}, spawn {} {} {}) in {} ms", preparation.runId, manifest.seed,
                manifest.spawn.x, manifest.spawn.y, manifest.spawn.z, manifest.timingsMillis.get("total"));
    }

    /** Serializes on the calling (server) thread; writes on the manifest thread, in submission order. */
    private CompletableFuture<Void> writeManifest(UUID runId, RunManifest manifest) {
        String json = manifest.toJson();
        Path file = this.paths.manifest(runId);
        this.lastManifestWrite = CompletableFuture.runAsync(() -> {
            try {
                AtomicFiles.writeString(file, json);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }, this.manifestWriter);
        return this.lastManifestWrite;
    }

    /** Pending manifest writes must land before a run's directory is deleted, or they would recreate it. */
    private void awaitManifestWrites() {
        try {
            this.lastManifestWrite.join();
        } catch (CompletionException | CancellationException e) {
            TerraRasa.LOGGER.warn("A manifest write failed", e);
        }
    }

    /** Abandons the active preparation: closes staged levels and deletes their data. */
    public void fail(MinecraftServer server, String reason) {
        Preparation preparation = this.active;
        setActive(null);
        this.lastFailure = reason;
        if (preparation == null) {
            return;
        }
        Map<ResourceKey<Level>, ServerLevel> levels = ((MinecraftServerAccessor) server).terrarasa$levels();
        preparation.chunks.releaseAll();
        preparation.pregenerators.forEach(Pregenerator::release);
        for (ServerLevel level : preparation.levels.values()) {
            try {
                evacuate(level);
                LevelRetirement.drainSaveAndClose(server, level, this.drainTimeout);
            } catch (LifecycleException | RuntimeException e) {
                TerraRasa.LOGGER.error("Could not close staged level {}", level.dimension().identifier(), e);
            }
            levels.remove(level.dimension(), level);
        }
        preparation.manifest.state = PreparationState.FAILED;
        preparation.manifest.failure = reason;
        deleteStageData(preparation.runId);
    }

    private void evacuate(ServerLevel level) throws LifecycleException {
        for (ServerPlayer player : List.copyOf(level.players())) {
            this.evacuator.accept(player);
        }
        if (!level.players().isEmpty()) {
            throw new LifecycleException("players remain in staged level " + level.dimension().identifier());
        }
    }

    /** Deletes a run's staged dimension data and manifest. Only call for runs with no open levels. */
    public void deleteStageData(UUID runId) {
        awaitManifestWrites();
        try {
            AtomicFiles.deleteRecursively(this.paths.stageDimensionsRoot(runId));
            AtomicFiles.deleteRecursively(this.paths.manifest(runId).getParent());
        } catch (IOException e) {
            TerraRasa.LOGGER.error("Could not delete staged data for {}", runId, e);
        }
    }

    public JsonObject describe() {
        JsonObject json = new JsonObject();
        Preparation preparation = this.active;
        if (preparation != null) {
            JsonObject active = new JsonObject();
            active.addProperty("runId", preparation.runId.toString());
            active.addProperty("seed", preparation.seed);
            active.addProperty("state", preparation.state.name());
            active.addProperty("elapsedMillis", preparation.elapsed().toMillis());
            active.addProperty("preloadedChunks", preparation.preloadedChunks);
            active.addProperty("preloadTarget", square(preparation.preloadRadius));
            if (!preparation.pregenerators.isEmpty()) {
                int done = 0;
                int total = 0;
                for (Pregenerator pregenerator : preparation.pregenerators) {
                    PregenerationTask progress = pregenerator.snapshot();
                    done += progress.next;
                    total += progress.total();
                }
                active.addProperty("pregenerated", done);
                active.addProperty("pregenerationTarget", total);
            }
            json.add("active", active);
        }
        RunManifest manifest = this.ready;
        if (manifest != null) {
            json.add("ready", Json.GSON.toJsonTree(manifest));
        }
        if (this.lastFailure != null) {
            json.addProperty("lastFailure", this.lastFailure);
        }
        return json;
    }

    /** Fraction in [0, 1] for progress displays. */
    public float progress() {
        Preparation preparation = this.active;
        if (preparation == null) {
            return this.ready != null ? 1.0F : 0.0F;
        }
        return switch (preparation.state) {
            case CREATING, SPAWN_FINDING -> 0.05F;
            case PRELOADING -> 0.1F + 0.85F * preparation.preloadedChunks / Math.max(1, square(preparation.preloadRadius));
            case PREGENERATING -> 0.96F;
            case FLUSHING -> 0.97F;
            default -> 1.0F;
        };
    }

    private Optional<RunManifest> readManifest(UUID runId) {
        Path file = this.paths.manifest(runId);
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        try {
            return Optional.of(RunManifest.load(file));
        } catch (IOException e) {
            TerraRasa.LOGGER.warn("Unreadable manifest for {}", runId, e);
            return Optional.empty();
        }
    }

    private static boolean sameTargets(RunManifest manifest, DimensionPolicy policy) {
        List<Identifier> targets = manifest.dimensions.stream().map(d -> Identifier.parse(d.target)).toList();
        return targets.equals(policy.resettable());
    }

    private static int countFull(ServerLevel level, ChunkPos center, int radius) {
        ServerChunkCache chunkSource = level.getChunkSource();
        int full = 0;
        for (int x = MinecraftCompat.chunkX(center) - radius; x <= MinecraftCompat.chunkX(center) + radius; x++) {
            for (int z = MinecraftCompat.chunkZ(center) - radius; z <= MinecraftCompat.chunkZ(center) + radius; z++) {
                if (chunkSource.getChunkNow(x, z) != null) {
                    full++;
                }
            }
        }
        return full;
    }

    private static int square(int radius) {
        int side = radius * 2 + 1;
        return side * side;
    }

    private static final class Preparation {
        final UUID runId;
        final long seed;
        final RunManifest manifest;
        final int preloadRadius;
        final Map<Identifier, ServerLevel> levels = new LinkedHashMap<>();
        final StagedChunkTickets chunks = new StagedChunkTickets(PRELOAD_TICKET);
        final List<Pregenerator> pregenerators = new ArrayList<>();
        boolean finishRequested;
        private @Nullable CompletableFuture<Void> ioFlush;
        final ArrayDeque<StagedLevel> toConstruct = new ArrayDeque<>();
        @Nullable SpawnSpiral spawnSearch;
        @Nullable CompletableFuture<Vec3> climateSearch;
        @Nullable CompletableFuture<Void> published;
        final long startedNanos = System.nanoTime();
        PreparationState state = PreparationState.CREATING;
        long phaseStartedNanos = System.nanoTime();
        @Nullable ChunkPos spawnSearchCenter;
        @Nullable ChunkPos preloadCenter;
        int preloadedChunks;

        Preparation(UUID runId, long seed, RunManifest manifest, int preloadRadius) {
            this.runId = runId;
            this.seed = seed;
            this.manifest = manifest;
            this.preloadRadius = preloadRadius;
        }

        ServerLevel overworld() {
            return this.levels.get(DimensionPolicy.OVERWORLD);
        }

        void enter(PreparationState next) {
            this.state = next;
            this.phaseStartedNanos = System.nanoTime();
        }

        long phaseElapsedMillis() {
            return (System.nanoTime() - this.phaseStartedNanos) / 1_000_000L;
        }

        Duration elapsed() {
            return Duration.ofNanos(System.nanoTime() - this.startedNanos);
        }

    }

    private record StagedLevel(Identifier target, ResourceKey<Level> key, LevelStem stem) {
    }
}
