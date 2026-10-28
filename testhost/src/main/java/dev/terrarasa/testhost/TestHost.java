package dev.terrarasa.testhost;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import dev.terrarasa.CanonicalLevelEvents;
import dev.terrarasa.DimensionPolicy;
import dev.terrarasa.Json;
import dev.terrarasa.PinnedSeed;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;

/**
 * Test-only host mod for Terra Rasa. It does what a game mod does (choose a policy, prepare worlds, move
 * players out, activate, reset session state) and exposes each step as a {@code /terrarasa} command, so
 * the library's integration suite runs without any particular game on top.
 */
public final class TestHost implements ModInitializer {
    /** Where players wait while the canonical worlds are replaced; never reset. */
    static final Identifier HOLDING = Identifier.fromNamespaceAndPath("terrarasa_testhost", "holding");

    private static @Nullable Host host;

    @Override
    public void onInitialize() {
        Config config = Config.load(FabricLoader.getInstance().getConfigDir().resolve("terrarasa-testhost.json"));
        DimensionPolicy policy = config.policy();
        PinnedSeed.setPolicy(policy);
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                HostCommands.register(dispatcher));
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            host = new Host(server, policy, config.preloadRadius, config.pregenerationRadii(),
                    Duration.ofSeconds(config.preparationTimeoutSeconds));
            host.started();
        });
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> host = null);
        CanonicalLevelEvents.BEFORE_RETIRE.register((server, retiring) -> {
            if (host != null) {
                host.beforeRetire(retiring);
            }
        });
        // Library work runs at the end of a server tick, as in a real host.
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (host != null) {
                host.tick();
            }
        });
    }

    static Host host() {
        Host current = host;
        if (current == null) {
            throw new IllegalStateException("the server has not started");
        }
        return current;
    }

    /** {@code config/terrarasa-testhost.json}, written by the test harness. */
    static final class Config {
        int preloadRadius = 2;
        int preparationTimeoutSeconds = 600;
        /** Blocks to pregenerate per dimension; none by default. */
        Map<String, Integer> pregeneration = Map.of();
        List<String> resettableDimensions = List.of("minecraft:overworld", "minecraft:the_nether", "minecraft:the_end");

        static Config load(Path file) {
            if (!Files.isRegularFile(file)) {
                return new Config();
            }
            try {
                Config config = Json.GSON.fromJson(Files.readString(file), Config.class);
                return config != null ? config : new Config();
            } catch (IOException e) {
                throw new IllegalStateException("unreadable " + file, e);
            }
        }

        Map<Identifier, Integer> pregenerationRadii() {
            Map<Identifier, Integer> radii = new LinkedHashMap<>();
            this.pregeneration.forEach((dimension, radius) -> radii.put(Identifier.parse(dimension), radius));
            return radii;
        }

        DimensionPolicy policy() {
            return new DimensionPolicy(this.resettableDimensions.stream().map(Identifier::parse).toList(), Set.of(HOLDING));
        }
    }
}
