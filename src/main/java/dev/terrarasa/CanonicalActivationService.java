package dev.terrarasa;

import java.io.IOException;
import java.lang.ref.WeakReference;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.terrarasa.mixin.MinecraftServerAccessor;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLevelEvents;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Util;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.TicketStorage;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.levelgen.WorldGenSettings;
import net.minecraft.world.level.levelgen.WorldOptions;
import org.jspecify.annotations.Nullable;

/**
 * Replaces the live canonical levels with a prepared run inside one server-thread critical section.
 *
 * <p>The whole protocol runs synchronously between two server ticks. No tick, packet handler or queued
 * server task can run while it executes, so no ordinary game code can observe a canonical key that is
 * missing or mapped to a closed level ("observable atomicity"). The level map is never left without a
 * canonical entry: closed levels stay mapped until their replacement is {@code put} over them, and only
 * code called from this class runs in between.
 *
 * <p>Every destructive step is preceded by a durable journal write. A failure at any point rolls back in
 * process to the previous run's data and seed; if even that fails, the server halts and startup recovery
 * finishes the job from the journal.
 */
public final class CanonicalActivationService {
    private final WorldPaths paths;
    private final Duration drainTimeout;
    private final List<WeakReference<ServerLevel>> retiredLevels = new ArrayList<>();
    private final Map<String, Long> lastTimings = new LinkedHashMap<>();
    private int activations;
    private int rollbacks;

    public CanonicalActivationService(WorldPaths paths, Duration drainTimeout) {
        this.paths = paths;
        this.drainTimeout = drainTimeout;
    }

    public record Activation(UUID runId, long seed, BlockPos spawn, Map<String, Long> timingsMillis) {
    }

    /**
     * Activates a prepared run. Preconditions (checked): server thread, no players in any resettable
     * level, the lobby is live. On return the canonical levels are new objects backed by the prepared
     * data and the server-global seed is the run seed.
     */
    public Activation activate(MinecraftServer server, RunManifest manifest, DimensionPolicy policy) throws LifecycleException {
        long started = System.nanoTime();
        this.lastTimings.clear();
        preflight(server, manifest, policy);
        Map<ResourceKey<Level>, ServerLevel> levels = ((MinecraftServerAccessor) server).terrarasa$levels();
        List<ResourceKey<Level>> order = policy.resettable().stream().map(CanonicalActivationService::dimensionKey).toList();
        UUID runId = manifest.runUuid();

        ActivationJournal journal = new ActivationJournal();
        journal.runId = runId.toString();
        journal.previousSeed = server.getWorldGenSettings().options().seed();
        journal.newSeed = manifest.seed;
        journal.previousRunLabel = "replaced-by-" + runId;
        if (manifest.spawn != null) {
            journal.spawnX = manifest.spawn.x;
            journal.spawnY = manifest.spawn.y;
            journal.spawnZ = manifest.spawn.z;
        }
        for (RunManifest.Dimension dimension : manifest.dimensions) {
            Identifier target = Identifier.parse(dimension.target);
            journal.moves.add(new ActivationJournal.Move(dimension.target,
                    this.paths.relativize(this.paths.dimension(target)),
                    this.paths.relativize(this.paths.quarantine(journal.previousRunLabel, target)),
                    this.paths.relativize(this.paths.dimension(Identifier.parse(dimension.stage)))));
        }

        Attempt attempt = new Attempt(journal, order);
        try {
            journal.phase = ActivationJournal.Phase.RETIRING;
            journal.save(this.paths);
            CanonicalLevelEvents.BEFORE_RETIRE.invoker().beforeRetire(server,
                    order.stream().map(key -> Objects.requireNonNull(levels.get(key), key::toString)).toList());
            // Live pregeneration lets go of the levels it generates in; after a rollback it resumes in the
            // reopened ones.
            LivePregeneration.pause();
            retireAll(server, attempt);
            promoteAll(journal);
            reconstructAll(server, manifest.seed, attempt);

            long phase = System.nanoTime();
            FaultInjection.check(FaultInjection.VALIDATE);
            BlockPos spawn = validate(server, manifest, attempt.constructed, attempt.retired);
            mark("validate", phase);

            journal.phase = ActivationJournal.Phase.COMMITTED;
            // No forced save here: the journal re-asserts the seed at startup, and an extra in-place save of
            // vanilla's global saved data would only widen the window for torn writes on a crash.
            journal.save(this.paths);
            FaultInjection.check(FaultInjection.HALT_AFTER_COMMIT);
            this.activations++;
            mark("total", started);
            cleanupAfterCommit(runId, journal);
            // What the staged phase did not pregenerate continues in the new live worlds.
            LivePregeneration.replace(manifest.pregeneration);
            TerraRasa.LOGGER.info("Activated run {} (seed {}) on canonical dimensions in {} ms", runId, manifest.seed,
                    this.lastTimings.get("total"));
            CanonicalLevelEvents.fireReplaced(server, List.copyOf(attempt.constructed.values()));
            return new Activation(runId, manifest.seed, spawn, Map.copyOf(this.lastTimings));
        } catch (LifecycleException | IOException | RuntimeException failure) {
            rollback(server, attempt, failure);
            throw new LifecycleException("Activation of run " + runId + " failed and was rolled back: " + failure.getMessage(), failure);
        }
    }

    /** What one activation has changed so far, for rollback. */
    private static final class Attempt {
        final ActivationJournal journal;
        final List<ResourceKey<Level>> order;
        final Map<ResourceKey<Level>, ServerLevel> retired = new LinkedHashMap<>();
        final Map<ResourceKey<Level>, ServerLevel> constructed = new LinkedHashMap<>();
        /** The level whose drain is in progress: still open if draining failed. */
        @Nullable ResourceKey<Level> retiring;

        Attempt(ActivationJournal journal, List<ResourceKey<Level>> order) {
            this.journal = journal;
            this.order = order;
        }
    }

    /** Drains, saves and closes the canonical levels; the Overworld last, because others read its data storage. */
    private void retireAll(MinecraftServer server, Attempt attempt) throws LifecycleException {
        long phase = System.nanoTime();
        Map<ResourceKey<Level>, ServerLevel> levels = ((MinecraftServerAccessor) server).terrarasa$levels();
        for (int i = attempt.order.size() - 1; i >= 0; i--) {
            ResourceKey<Level> key = attempt.order.get(i);
            FaultInjection.check(FaultInjection.RETIRE);
            ServerLevel level = Objects.requireNonNull(levels.get(key), key::toString);
            attempt.retiring = key;
            LevelRetirement.drainSaveAndClose(server, level, this.drainTimeout);
            attempt.retiring = null;
            attempt.retired.put(key, level);
            this.retiredLevels.add(new WeakReference<>(level));
        }
        mark("retire", phase);
    }

    /** Quarantines the closed canonical data and installs the staged data. */
    private void promoteAll(ActivationJournal journal) throws LifecycleException, IOException {
        long phase = System.nanoTime();
        journal.phase = ActivationJournal.Phase.PROMOTING;
        journal.save(this.paths);
        for (int i = 0; i < journal.moves.size(); i++) {
            ActivationJournal.promote(this.paths, journal.moves.get(i));
            if (i == 0) {
                FaultInjection.check(FaultInjection.HALT_AFTER_FIRST_MOVE);
                FaultInjection.check(FaultInjection.PROMOTE);
            }
        }
        journal.phase = ActivationJournal.Phase.PROMOTED;
        journal.save(this.paths);
        mark("promote", phase);
        FaultInjection.check(FaultInjection.HALT_AFTER_PROMOTE);
    }

    /** Rebinds the server-global seed, then builds new vanilla levels, Overworld first. */
    private void reconstructAll(MinecraftServer server, long seed, Attempt attempt) throws LifecycleException, IOException {
        long phase = System.nanoTime();
        setGlobalSeed(server, seed);
        Map<ResourceKey<Level>, ServerLevel> levels = ((MinecraftServerAccessor) server).terrarasa$levels();
        Registry<LevelStem> stems = server.registryAccess().lookupOrThrow(Registries.LEVEL_STEM);
        for (ResourceKey<Level> key : attempt.order) {
            FaultInjection.check(FaultInjection.RECONSTRUCT);
            LevelStem stem = Objects.requireNonNull(stems.getValue(key.identifier()), () -> "stem " + key);
            ServerLevel level = LevelFactory.createCanonical(server, key, stem);
            levels.put(key, level);
            attempt.constructed.put(key, level);
        }
        bringOnline(server, attempt.constructed.values());
        attempt.journal.phase = ActivationJournal.Phase.RECONSTRUCTED;
        attempt.journal.save(this.paths);
        mark("reconstruct", phase);
    }

    private static void bringOnline(MinecraftServer server, Collection<ServerLevel> levels) {
        for (ServerLevel level : levels) {
            activateSavedTickets(level);
            ServerLevelEvents.LOAD.invoker().onLevelLoad(server, level);
        }
        server.updateMobSpawningFlags();
    }

    private void preflight(MinecraftServer server, RunManifest manifest, DimensionPolicy policy) throws LifecycleException {
        LevelRetirement.assertServerThread(server);
        if (manifest.state != PreparationState.CLOSED_READY) {
            throw new LifecycleException("Prepared run is not CLOSED_READY: " + manifest.state);
        }
        if (!SharedConstants.getCurrentVersion().id().equals(manifest.minecraftVersion)) {
            throw new LifecycleException("Prepared run targets Minecraft " + manifest.minecraftVersion);
        }
        if (!RegistryFingerprint.compute(server, policy).equals(manifest.fingerprint)) {
            throw new LifecycleException("Registry/data-pack fingerprint changed since preparation");
        }
        List<Identifier> targets = manifest.dimensions.stream().map(d -> Identifier.parse(d.target)).toList();
        if (!targets.equals(policy.resettable())) {
            throw new LifecycleException("Prepared dimensions " + targets + " do not match policy " + policy.resettable());
        }
        if (manifest.spawn == null) {
            throw new LifecycleException("Prepared run has no spawn");
        }
        for (RunManifest.Dimension dimension : manifest.dimensions) {
            Identifier target = Identifier.parse(dimension.target);
            if (policy.classify(target) != DimensionClass.RESETTABLE_CANONICAL) {
                throw new LifecycleException(target + " is not resettable");
            }
            ServerLevel live = server.getLevel(dimensionKey(target));
            if (live == null) {
                throw new LifecycleException("Canonical level " + target + " is not loaded");
            }
            if (!live.players().isEmpty()) {
                throw new LifecycleException("Players are still inside " + target);
            }
            if (server.getLevel(dimensionKey(Identifier.parse(dimension.stage))) != null) {
                throw new LifecycleException("Staged level " + dimension.stage + " is still open");
            }
            if (!Files.isDirectory(this.paths.dimension(Identifier.parse(dimension.stage)))) {
                throw new LifecycleException("Staged data missing for " + target);
            }
        }
        for (Identifier persistent : policy.persistent()) {
            if (server.getLevel(dimensionKey(persistent)) == null) {
                throw new LifecycleException("Persistent level " + persistent + " is not loaded");
            }
        }
        try {
            ActivationJournal.load(this.paths).ifPresent(previous -> {
                if (previous.recoveryDecision() != ActivationJournal.Decision.NOTHING) {
                    throw new IllegalStateException("An unrecovered activation journal exists (" + previous.phase + ")");
                }
            });
            probeAtomicMove();
        } catch (IOException | IllegalStateException e) {
            throw new LifecycleException("Preflight failed: " + e.getMessage(), e);
        }
    }

    /** Promotion requires same-filesystem atomic renames between the dimensions and quarantine roots. */
    private void probeAtomicMove() throws IOException {
        Path source = this.paths.worldRoot().resolve("dimensions").resolve(StageKeys.NAMESPACE).resolve("probe-" + UUID.randomUUID());
        Path target = this.paths.quarantineRoot().resolve(source.getFileName().toString());
        Files.createDirectories(source);
        try {
            AtomicFiles.moveDirectory(source, target);
        } finally {
            Files.deleteIfExists(source);
            Files.deleteIfExists(target);
        }
    }

    private BlockPos validate(MinecraftServer server, RunManifest manifest,
            Map<ResourceKey<Level>, ServerLevel> constructed, Map<ResourceKey<Level>, ServerLevel> retired) throws LifecycleException {
        long seed = manifest.seed;
        if (server.getWorldGenSettings().options().seed() != seed) {
            throw new LifecycleException("Server-global seed was not rebound");
        }
        for (Map.Entry<ResourceKey<Level>, ServerLevel> entry : constructed.entrySet()) {
            ResourceKey<Level> key = entry.getKey();
            ServerLevel level = entry.getValue();
            if (server.getLevel(key) != level) {
                throw new LifecycleException("getLevel(" + key.identifier() + ") does not return the new level");
            }
            if (!level.dimension().equals(key)) {
                throw new LifecycleException("New level reports dimension " + level.dimension().identifier());
            }
            if (retired.containsValue(level)) {
                throw new LifecycleException("A retired level object is still mapped as " + key.identifier());
            }
            if (((SeedOverride.Holder) level).terrarasa$seedOverride() != null || level.getSeed() != seed) {
                throw new LifecycleException("Level " + key.identifier() + " does not use the run seed");
            }
        }
        if (server.overworld() != constructed.get(Level.OVERWORLD)) {
            throw new LifecycleException("server.overworld() is not the new Overworld");
        }
        for (RunManifest.Dimension dimension : manifest.dimensions) {
            if (server.getLevel(dimensionKey(Identifier.parse(dimension.stage))) != null) {
                throw new LifecycleException("Staged identity " + dimension.stage + " is still registered");
            }
        }
        // Smoke test: the prepared spawn chunk loads from promoted storage.
        BlockPos spawn = new BlockPos(manifest.spawn.x, manifest.spawn.y, manifest.spawn.z);
        ServerLevel overworld = server.overworld();
        ChunkPos spawnChunk = ChunkPos.containing(spawn);
        if (overworld.getChunkSource().getChunk(spawnChunk.x(), spawnChunk.z(), ChunkStatus.FULL, true) == null) {
            throw new LifecycleException("Prepared spawn chunk could not be loaded");
        }
        return spawn;
    }

    private void rollback(MinecraftServer server, Attempt attempt, Throwable failure) {
        ActivationJournal journal = attempt.journal;
        List<ResourceKey<Level>> order = attempt.order;
        Map<ResourceKey<Level>, ServerLevel> retired = attempt.retired;
        Map<ResourceKey<Level>, ServerLevel> constructed = attempt.constructed;
        ResourceKey<Level> interruptedRetirement = attempt.retiring;
        TerraRasa.LOGGER.error("Canonical activation failed in phase {}; rolling back", journal.phase, failure);
        this.rollbacks++;
        Map<ResourceKey<Level>, ServerLevel> levels = ((MinecraftServerAccessor) server).terrarasa$levels();
        try {
            journal.failure = String.valueOf(failure);
            if (journal.phase != null) {
                journal.save(this.paths);
            }
            if (failure instanceof LevelRetirement.CloseFailedException) {
                throw new IllegalStateException("A level failed while closing and cannot be restored in process", failure);
            }
            List<ServerLevel> resumed = new ArrayList<>();
            if (interruptedRetirement != null && levels.get(interruptedRetirement) != null) {
                // Draining failed before any save/close: the level is still open; undo the drain.
                resumed.add(levels.get(interruptedRetirement));
            }
            List<ResourceKey<Level>> constructedKeys = new ArrayList<>(constructed.keySet());
            for (int i = constructedKeys.size() - 1; i >= 0; i--) {
                ServerLevel level = constructed.get(constructedKeys.get(i));
                LevelRetirement.drainSaveAndClose(server, level, this.drainTimeout);
                this.retiredLevels.add(new WeakReference<>(level));
            }
            journal.rollBackFiles(this.paths);
            setGlobalSeed(server, journal.previousSeed);
            Registry<LevelStem> stems = server.registryAccess().lookupOrThrow(Registries.LEVEL_STEM);
            List<ServerLevel> restored = new ArrayList<>();
            for (ResourceKey<Level> key : order) {
                if (!retired.containsKey(key)) {
                    continue;
                }
                ServerLevel level = LevelFactory.createCanonical(server, key, Objects.requireNonNull(stems.getValue(key.identifier())));
                levels.put(key, level);
                restored.add(level);
            }
            restored.addAll(resumed);
            bringOnline(server, restored);
            journal.phase = ActivationJournal.Phase.ROLLED_BACK;
            journal.save(this.paths);
            TerraRasa.LOGGER.warn("Rolled back to the previous canonical levels (seed {})", journal.previousSeed);
            if (!retired.isEmpty()) {
                CanonicalLevelEvents.fireReplaced(server, restored);
            }
        } catch (Throwable fatal) {
            // The server cannot keep ticking without valid canonical levels: stop, and let startup recovery
            // reconcile from the journal. Unmap closed levels so shutdown does not close them twice.
            TerraRasa.LOGGER.error("FATAL: rollback failed; halting the server for journal recovery", fatal);
            for (ResourceKey<Level> key : order) {
                ServerLevel mapped = levels.get(key);
                if (mapped != null && (retired.containsValue(mapped) || constructed.containsValue(mapped))) {
                    levels.remove(key);
                }
            }
            server.halt(false);
        }
    }

    /** Rebinds the server-global seed; it is saved with global saved data and reasserted from the journal on startup. */
    public static void setGlobalSeed(MinecraftServer server, long seed) {
        WorldGenSettings current = server.getWorldGenSettings();
        if (current.options().seed() == seed) {
            return;
        }
        WorldOptions options = current.options();
        WorldGenSettings updated = new WorldGenSettings(
                new WorldOptions(seed, options.generateStructures(), options.generateBonusChest()), current.dimensions());
        ((MinecraftServerAccessor) server).terrarasa$setWorldGenSettings(updated);
        server.getDataStorage().set(WorldGenSettings.TYPE, updated);
    }

    private static void activateSavedTickets(ServerLevel level) {
        TicketStorage tickets = level.getDataStorage().get(TicketStorage.TYPE);
        if (tickets != null) {
            tickets.activateAllDeactivatedTickets();
        }
    }

    /** Old data is only deleted after commit, asynchronously, once no reader or writer remains. */
    private void cleanupAfterCommit(UUID runId, ActivationJournal journal) {
        Path quarantine = this.paths.quarantineRoot().resolve(journal.previousRunLabel);
        Path stageRoot = this.paths.stageDimensionsRoot(runId);
        Path manifestDir = this.paths.manifest(runId).getParent();
        Util.ioPool().execute(() -> {
            try {
                AtomicFiles.deleteRecursively(quarantine);
                AtomicFiles.deleteRecursively(manifestDir);
                // Every staged dimension was moved out; only empty namespace directories remain. The shared
                // stage root stays: the next preparation may be creating its directories right now.
                AtomicFiles.deleteRecursively(stageRoot);
            } catch (IOException e) {
                TerraRasa.LOGGER.warn("Could not clean up replaced run data at {}", quarantine, e);
            }
        });
    }

    private void mark(String phase, long startedNanos) {
        this.lastTimings.put(phase, (System.nanoTime() - startedNanos) / 1_000_000L);
    }

    public static ResourceKey<Level> dimensionKey(Identifier id) {
        return ResourceKey.create(Registries.DIMENSION, id);
    }

    /** Retired level objects still reachable from somewhere (a leak indicator, meaningful after GC). */
    public int reachableRetiredLevels() {
        this.retiredLevels.removeIf(reference -> reference.get() == null);
        return this.retiredLevels.size();
    }

    public JsonObject describe() {
        JsonObject json = new JsonObject();
        json.addProperty("activations", this.activations);
        json.addProperty("rollbacks", this.rollbacks);
        JsonObject timings = new JsonObject();
        this.lastTimings.forEach(timings::addProperty);
        json.add("lastTimingsMillis", timings);
        json.addProperty("retiredLevelsReachable", reachableRetiredLevels());
        json.addProperty("ignoredPoiUpdates", LevelRetirement.ignoredPoiUpdates());
        return json;
    }

    public static JsonArray describeLevels(MinecraftServer server, DimensionPolicy policy) {
        JsonArray array = new JsonArray();
        for (Map.Entry<ResourceKey<Level>, ServerLevel> entry : ((MinecraftServerAccessor) server).terrarasa$levels().entrySet()) {
            ServerLevel level = entry.getValue();
            JsonObject json = new JsonObject();
            json.addProperty("key", entry.getKey().identifier().toString());
            json.addProperty("dimension", level.dimension().identifier().toString());
            json.addProperty("class", policy.classify(entry.getKey().identifier()).name());
            json.addProperty("identity", Integer.toHexString(System.identityHashCode(level)));
            json.addProperty("seed", level.getSeed());
            Long override = ((SeedOverride.Holder) level).terrarasa$seedOverride();
            if (override != null) {
                json.addProperty("seedOverride", override);
            }
            json.addProperty("players", level.players().size());
            json.addProperty("loadedChunks", level.getChunkSource().getLoadedChunksCount());
            int[] ticking = {0};
            level.getChunkSource().chunkMap.forEachBlockTickingChunk(chunk -> ticking[0]++);
            json.addProperty("tickingChunks", ticking[0]);
            array.add(json);
        }
        return array;
    }
}
