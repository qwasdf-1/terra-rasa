package dev.terrarasa;

import java.nio.file.Path;
import java.util.OptionalLong;
import java.util.Set;

import dev.terrarasa.mixin.PrimaryLevelDataAccessor;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerWorldEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.storage.PrimaryLevelData;
import net.minecraft.world.level.storage.ServerLevelData;

/** Minecraft APIs that changed between the obfuscated 1.21.11 line and 26.x. */
public final class MinecraftCompat {
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
        if (Level.OVERWORLD.identifier().equals(dimension)) {
            return worldRoot;
        }
        if (Level.NETHER.identifier().equals(dimension)) {
            return worldRoot.resolve("DIM-1");
        }
        if (Level.END.identifier().equals(dimension)) {
            return worldRoot.resolve("DIM1");
        }
        return worldRoot.resolve("dimensions").resolve(dimension.getNamespace()).resolve(dimension.getPath());
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
        ServerWorldEvents.LOAD.invoker().onWorldLoad(server, level);
    }

    public static void levelUnloaded(MinecraftServer server, ServerLevel level) {
        ServerWorldEvents.UNLOAD.invoker().onWorldUnload(server, level);
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
