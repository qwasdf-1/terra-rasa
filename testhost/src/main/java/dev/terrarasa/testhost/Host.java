package dev.terrarasa.testhost;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.terrarasa.ActivationJournal;
import dev.terrarasa.CanonicalActivationService;
import dev.terrarasa.DimensionPolicy;
import dev.terrarasa.MinecraftCompat;
import dev.terrarasa.FaultInjection;
import dev.terrarasa.LifecycleException;
import dev.terrarasa.LivePregeneration;
import dev.terrarasa.RegistryFingerprint;
import dev.terrarasa.RunManifest;
import dev.terrarasa.WorldPaths;
import dev.terrarasa.WorldPreparationService;
import dev.terrarasa.session.SessionReset;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.RandomSupport;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/** One running server's use of the library, the way a game mod would wire it. */
final class Host {
    private static final Duration DRAIN_TIMEOUT = Duration.ofSeconds(30);
    private static final Vec3 HOLDING_SPOT = new Vec3(0.5, 65.0, 0.5);

    private final MinecraftServer server;
    private final DimensionPolicy policy;
    private final WorldPaths paths;
    private final int preloadRadius;
    private final WorldPreparationService preparation;
    private final CanonicalActivationService activation;
    /** Places a bell, a POI block, in every level about to be retired, so its POI update runs after the close. */
    boolean poiChangeBeforeRetire;
    private boolean activationRequested;
    private int attempts;
    private JsonObject lastActivation = new JsonObject();

    Host(MinecraftServer server, DimensionPolicy policy, int preloadRadius, Map<Identifier, Integer> pregenerationRadii,
            Duration preparationTimeout) {
        this.server = server;
        this.policy = policy;
        this.preloadRadius = preloadRadius;
        this.paths = new WorldPaths(server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize());
        this.preparation = new WorldPreparationService(this.paths, preloadRadius, pregenerationRadii,
                preparationTimeout, DRAIN_TIMEOUT,
                this::hold);
        this.activation = new CanonicalActivationService(this.paths, DRAIN_TIMEOUT);
    }

    void beforeRetire(List<ServerLevel> retiring) {
        if (!this.poiChangeBeforeRetire) {
            return;
        }
        for (ServerLevel level : retiring) {
            level.setBlock(new BlockPos(0, level.getMinY() + 1, 0), Blocks.BELL.defaultBlockState(), Block.UPDATE_ALL);
        }
    }

    void started() {
        this.preparation.loadFromDisk(this.server, this.policy);
    }

    void tick() {
        this.preparation.tick(this.server);
        if (this.activationRequested) {
            if (this.preparation.ready().isEmpty() && this.preparation.isPreparing()) {
                // Like a game's start button: stop pregenerating and activate as soon as the run is ready.
                this.preparation.finishSoon();
                return;
            }
            this.activationRequested = false;
            activate();
        }
    }

    /** Replaces any preparation or prepared worlds with a new preparation. */
    void prepare(@Nullable Long seed, @Nullable Integer radius) throws LifecycleException {
        if (this.preparation.isPreparing()) {
            this.preparation.fail(this.server, "replaced by a new prepare request");
        }
        this.preparation.ready().ifPresent(manifest -> {
            this.preparation.takeReady();
            this.preparation.deleteStageData(manifest.runUuid());
        });
        long chosen = seed != null ? seed : RandomSupport.generateUniqueSeed();
        this.preparation.begin(this.server, chosen, this.policy, radius != null ? radius : this.preloadRadius);
    }

    /** Activation runs at the end of the next server tick, outside command execution. */
    /**
     * Activates now when a run is ready, inside the command like a game's start command, so tasks that
     * levels queue while being retired run after the swap. Otherwise activates once preparation is ready.
     */
    boolean requestActivation() {
        if (this.preparation.ready().isPresent()) {
            activate();
            return true;
        }
        this.activationRequested = true;
        return false;
    }

    private void activate() {
        JsonObject result = new JsonObject();
        result.addProperty("attempt", ++this.attempts);
        if (this.preparation.ready().isEmpty()) {
            result.addProperty("ok", false);
            result.addProperty("error", "nothing is prepared");
            this.lastActivation = result;
            return;
        }
        // Every player out of the worlds being replaced, back to baseline while their levels are live.
        for (ServerPlayer player : List.copyOf(this.server.getPlayerList().getPlayers())) {
            SessionReset.resetPlayer(this.server, player, GameType.ADVENTURE);
            hold(player);
        }
        RunManifest manifest = this.preparation.takeReady();
        try {
            CanonicalActivationService.Activation activated = this.activation.activate(this.server, manifest, this.policy);
            SessionReset.resetServerState(this.server);
            ServerLevel overworld = this.server.overworld();
            for (ServerPlayer player : List.copyOf(this.server.getPlayerList().getPlayers())) {
                SessionReset.resetPlayer(this.server, player, GameType.SURVIVAL);
                // Vanilla's placement for a new player: a collision-free spot near the world spawn.
                Vec3 arrival = Vec3.atBottomCenterOf(player.adjustSpawnLocation(overworld, activated.spawn()));
                player.teleport(new TeleportTransition(overworld, arrival, Vec3.ZERO, 0.0F, 0.0F, TeleportTransition.DO_NOTHING));
            }
            result.addProperty("ok", true);
            result.addProperty("runId", activated.runId().toString());
            result.addProperty("seed", activated.seed());
            result.add("spawn", position(activated.spawn()));
            JsonObject timings = new JsonObject();
            activated.timingsMillis().forEach(timings::addProperty);
            result.add("timingsMillis", timings);
        } catch (LifecycleException e) {
            if (RegistryFingerprint.compute(this.server, this.policy).equals(manifest.fingerprint)) {
                this.preparation.restoreReady(manifest);
            } else {
                this.preparation.deleteStageData(manifest.runUuid());
            }
            result.addProperty("ok", false);
            result.addProperty("error", e.getMessage());
        }
        this.lastActivation = result;
    }

    private void hold(ServerPlayer player) {
        ServerLevel holding = this.server.getLevel(CanonicalActivationService.dimensionKey(TestHost.HOLDING));
        if (holding != null && player.level() != holding) {
            player.teleport(new TeleportTransition(holding, HOLDING_SPOT, Vec3.ZERO, 0.0F, 0.0F, TeleportTransition.DO_NOTHING));
        }
    }

    JsonObject status() {
        JsonObject json = new JsonObject();
        json.addProperty("globalSeed", MinecraftCompat.worldSeed(this.server));
        json.add("levels", CanonicalActivationService.describeLevels(this.server, this.policy));
        json.add("preparation", this.preparation.describe());
        json.add("pregeneration", LivePregeneration.describe());
        json.add("activation", this.activation.describe());
        json.add("lastActivation", this.lastActivation);
        json.addProperty("activationPending", this.activationRequested);
        try {
            ActivationJournal.load(this.paths).ifPresent(journal -> {
                JsonObject described = new JsonObject();
                described.addProperty("runId", journal.runId);
                described.addProperty("phase", journal.phase.name());
                if (journal.failure != null) {
                    described.addProperty("failure", journal.failure);
                }
                json.add("journal", described);
            });
        } catch (IOException e) {
            json.addProperty("journalError", e.toString());
        }
        String armed = FaultInjection.armed();
        if (armed != null) {
            json.addProperty("faultArmed", armed);
        }
        return json;
    }

    int reachableRetiredLevels() {
        return this.activation.reachableRetiredLevels();
    }

    static JsonObject describePlayer(ServerPlayer player) {
        JsonObject json = new JsonObject();
        json.addProperty("name", player.getGameProfile().name());
        json.addProperty("dimension", player.level().dimension().identifier().toString());
        json.addProperty("x", player.getX());
        json.addProperty("y", player.getY());
        json.addProperty("z", player.getZ());
        json.addProperty("gameMode", player.gameMode().getName());
        json.addProperty("health", player.getHealth());
        return json;
    }

    private static JsonArray position(BlockPos pos) {
        JsonArray array = new JsonArray();
        array.add(pos.getX());
        array.add(pos.getY());
        array.add(pos.getZ());
        return array;
    }
}
