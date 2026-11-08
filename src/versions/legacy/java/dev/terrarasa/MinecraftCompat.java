package dev.terrarasa;

import java.nio.file.Path;
import java.util.OptionalLong;
import java.util.Set;
import java.util.List;

import dev.terrarasa.mixin.PrimaryLevelDataAccessor;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.storage.PrimaryLevelData;
import net.minecraft.world.level.storage.ServerLevelData;

/** Minecraft APIs that changed between the obfuscated 1.21.11 line and 26.x. */
public final class MinecraftCompat {
    private static final Identifier OVERWORLD = Identifier.fromNamespaceAndPath("minecraft", "overworld");
    private static final Identifier NETHER = Identifier.fromNamespaceAndPath("minecraft", "the_nether");
    private static final Identifier END = Identifier.fromNamespaceAndPath("minecraft", "the_end");

    private MinecraftCompat() {
    }

    public static long worldSeed(MinecraftServer server) {
        return server.getWorldData().worldGenOptions().seed();
    }

    public static boolean generateStructures(MinecraftServer server) {
        return server.getWorldData().worldGenOptions().generateStructures();
    }

    public static void setWorldSeed(MinecraftServer server, long seed) {
        WorldOptions current = server.getWorldData().worldGenOptions();
        if (current.seed() != seed) {
            if (!(server.getWorldData() instanceof PrimaryLevelData primary)) {
                throw new IllegalStateException("Unsupported world data implementation: " + server.getWorldData().getClass());
            }
            ((PrimaryLevelDataAccessor) primary).terrarasa$setWorldOptions(current.withSeed(OptionalLong.of(seed)));
        }
    }

    /** Vanilla 1.21 keeps the three built-in dimensions at the world root and custom dimensions below dimensions/. */
    public static Path dimensionPath(Path worldRoot, Identifier dimension) {
        // The vanilla dimension ids are stable strings. Naming them directly keeps this path free of
        // the vanilla registry, which unit tests otherwise cannot touch without a bootstrapped game.
        if (OVERWORLD.equals(dimension)) {
            return worldRoot;
        }
        if (NETHER.equals(dimension)) {
            return worldRoot.resolve("DIM-1");
        }
        if (END.equals(dimension)) {
            return worldRoot.resolve("DIM1");
        }
        return worldRoot.resolve("dimensions").resolve(dimension.getNamespace()).resolve(dimension.getPath());
    }

    /** The legacy Overworld shares its root with global world files, so swap only these dimension stores. */
    public static List<String> rootDimensionStorageParts(Identifier dimension) {
        return OVERWORLD.equals(dimension) ? List.of("region", "entities", "poi", "data") : List.of();
    }

    public static ChunkPos chunkAt(BlockPos pos) {
        return new ChunkPos(pos);
    }

    public static int chunkX(ChunkPos pos) {
        return pos.x;
    }

    public static int chunkZ(ChunkPos pos) {
        return pos.z;
    }

    public static long packChunkPos(int x, int z) {
        return ChunkPos.asLong(x, z);
    }

    public static void levelLoaded(MinecraftServer server, ServerLevel level) {
        TerraRasa.platform().fireLevelLoaded(server, level);
    }

    public static void levelUnloaded(MinecraftServer server, ServerLevel level) {
        TerraRasa.platform().fireLevelUnloaded(server, level);
    }

    public static void resetWorldRunState(MinecraftServer server) {
        ServerLevelData data = server.getWorldData().overworldData();
        var scheduled = data.getScheduledEvents();
        for (String id : java.util.List.copyOf(scheduled.getEventsIds())) {
            scheduled.remove(id);
        }
        server.overworld().getRandomSequences().clear();
        server.overworld().setWeatherParameters(0, 0, false, false);
        data.setWanderingTraderSpawnDelay(net.minecraft.world.entity.npc.wanderingtrader.WanderingTraderSpawner.DEFAULT_SPAWN_DELAY);
        data.setWanderingTraderSpawnChance(25);
        data.setWanderingTraderId(null);
    }

    public static Set<String> entityTags(ServerPlayer player) {
        return player.getTags();
    }
}
