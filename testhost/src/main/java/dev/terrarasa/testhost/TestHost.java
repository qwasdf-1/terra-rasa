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
import dev.terrarasa.ResourceIdentifier;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.MinecraftServer;
import org.jspecify.annotations.Nullable;

/**
 * Test-only host mod for Terra Rasa. It does what a game mod does (choose a policy, prepare worlds, move
 * the players out, activate, reset session state) and exposes each step as a {@code /terrarasa} command, so
 * the library's integration suite runs without any particular game on top.
 *
 * <p>The loader entrypoint overlays build a {@link HostPlatform} and call {@link #bootstrap}; everything
 * below that boundary is loader-neutral.
 */
public final class TestHost {
    /** Where players wait while the canonical worlds are replaced; never reset. */
    static final ResourceIdentifier HOLDING = ResourceIdentifier.fromNamespaceAndPath("terrarasa_testhost", "holding");

    private static @Nullable Host host;

    /** Registers every hook the host needs. Call exactly once per mod load. */
    static void bootstrap(HostPlatform platform) {
        Config config = Config.load(platform.configFile("terrarasa-testhost.json"));
        DimensionPolicy policy = config.policy();
        PinnedSeed.setPolicy(policy);

        platform.registerCommands(HostCommands::register);
        platform.onServerStarted(server -> {
            host = new Host(server, policy, config.preloadRadius, config.pregenerationRadii(),
                    Duration.ofSeconds(config.preparationTimeoutSeconds));
            host.started();
        });
        platform.onServerStopped(() -> host = null);
        CanonicalLevelEvents.registerBeforeRetire((server, retiring) -> {
            if (host != null) {
                host.beforeRetire(retiring);
            }
        });
        // Library work runs at the end of a server tick, as in a real host.
        platform.onEndServerTick(server -> {
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
                throw new IllegalStateException("unreadable " + file);
            }
        }

        Map<ResourceIdentifier, Integer> pregenerationRadii() {
            Map<ResourceIdentifier, Integer> radii = new LinkedHashMap<>();
            this.pregeneration.forEach((dimension, radius) -> radii.put(ResourceIdentifier.parse(dimension), radius));
            return radii;
        }

        DimensionPolicy policy() {
            return new DimensionPolicy(this.resettableDimensions.stream().map(ResourceIdentifier::parse).toList(), Set.of(HOLDING));
        }
    }
}
