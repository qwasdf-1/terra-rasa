package dev.terrarasa;

import java.nio.file.Path;

import dev.terrarasa.mixin.MinecraftServerAccessor;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLevelEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.npc.wanderingtrader.WanderingTraderSpawner;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.WorldGenSettings;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.saveddata.WanderingTraderData;

/** Minecraft APIs that changed between the unobfuscated 26.x line and 1.21.11. */
public final class MinecraftCompat {
    private MinecraftCompat() {
    }

    public static long worldSeed(MinecraftServer server) {
        return server.getWorldGenSettings().options().seed();
    }

    public static boolean generateStructures(MinecraftServer server) {
        return server.getWorldGenSettings().options().generateStructures();
    }

    public static void setWorldSeed(MinecraftServer server, long seed) {
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

    public static Path dimensionPath(Path worldRoot, Identifier dimension) {
        return dimension.resolveAgainst(worldRoot.resolve("dimensions"));
    }

    public static ChunkPos chunkAt(BlockPos pos) {
        return ChunkPos.containing(pos);
    }

    public static int chunkX(ChunkPos pos) {
        return pos.x();
    }

    public static int chunkZ(ChunkPos pos) {
        return pos.z();
    }

    public static long packChunkPos(int x, int z) {
        return ChunkPos.pack(x, z);
    }

    public static void levelLoaded(MinecraftServer server, ServerLevel level) {
        ServerLevelEvents.LOAD.invoker().onLevelLoad(server, level);
    }

    public static void levelUnloaded(MinecraftServer server, ServerLevel level) {
        ServerLevelEvents.UNLOAD.invoker().onLevelUnload(server, level);
    }

    public static void resetWorldRunState(MinecraftServer server) {
        var scheduled = server.getScheduledEvents();
        for (String id : java.util.List.copyOf(scheduled.getEventsIds())) {
            scheduled.remove(id);
        }
        server.getRandomSequences().clear();
        server.setWeatherParameters(0, 0, false, false);
        for (Holder<net.minecraft.world.clock.WorldClock> clock : server.registryAccess()
                .lookupOrThrow(Registries.WORLD_CLOCK).listElements().toList()) {
            server.clockManager().setTotalTicks(clock, 0L);
        }
        server.getPlayerList().broadcastAll(server.clockManager().createFullSyncPacket());
        WanderingTraderData trader = server.getDataStorage().computeIfAbsent(WanderingTraderData.TYPE);
        trader.setSpawnDelay(WanderingTraderSpawner.DEFAULT_SPAWN_DELAY);
        trader.setSpawnChance(WanderingTraderSpawner.MIN_SPAWN_CHANCE);
    }

    public static java.util.Set<String> entityTags(net.minecraft.server.level.ServerPlayer player) {
        return player.entityTags();
    }
}
