package dev.terrarasa;

import java.util.List;

import com.google.common.collect.ImmutableList;
import dev.terrarasa.mixin.MinecraftServerAccessor;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.RandomSequences;
import net.minecraft.world.entity.ai.village.VillageSiege;
import net.minecraft.world.entity.npc.CatSpawner;
import net.minecraft.world.entity.npc.wanderingtrader.WanderingTraderSpawner;
import net.minecraft.world.level.CustomSpawner;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.levelgen.PatrolSpawner;
import net.minecraft.world.level.levelgen.PhantomSpawner;
import net.minecraft.world.level.storage.DerivedLevelData;
import net.minecraft.world.level.storage.ServerLevelData;
import net.minecraft.world.level.storage.WorldData;

/** Constructs vanilla ServerLevels using the 1.21.11 constructor and per-world random sequences. */
public final class LevelFactory {
    private LevelFactory() {
    }

    public static ServerLevel createCanonical(MinecraftServer server, ResourceKey<Level> dimension, LevelStem stem) {
        MinecraftServerAccessor access = (MinecraftServerAccessor) server;
        WorldData worldData = server.getWorldData();
        boolean overworld = dimension.equals(Level.OVERWORLD);
        long seed = MinecraftCompat.worldSeed(server);
        ServerLevelData levelData = overworld
                ? worldData.overworldData()
                : new DerivedLevelData(worldData, worldData.overworldData());
        List<CustomSpawner> spawners = overworld
                ? ImmutableList.of(new PhantomSpawner(), new PatrolSpawner(), new CatSpawner(), new VillageSiege(),
                        new WanderingTraderSpawner(worldData.overworldData()))
                : ImmutableList.of();
        RandomSequences sequences = overworld ? null : server.overworld().getRandomSequences();
        ServerLevel level = new ServerLevel(server, access.terrarasa$executor(), access.terrarasa$storageSource(), levelData,
                dimension, stem, worldData.isDebugWorld(), BiomeManager.obfuscateSeed(seed), spawners, overworld, sequences);
        level.getWorldBorder().setAbsoluteMaxSize(server.getAbsoluteMaxWorldSize());
        server.getPlayerList().addWorldborderListener(level);
        return level;
    }

    public static ServerLevel createStaged(MinecraftServer server, ResourceKey<Level> stageKey, LevelStem stem, long seed) {
        MinecraftServerAccessor access = (MinecraftServerAccessor) server;
        WorldData worldData = server.getWorldData();
        ServerLevelData levelData = new DerivedLevelData(worldData, worldData.overworldData());
        return SeedOverride.construct(stageKey, seed, () -> new ServerLevel(server, access.terrarasa$executor(),
                access.terrarasa$storageSource(), levelData, stageKey, stem, worldData.isDebugWorld(),
                BiomeManager.obfuscateSeed(seed), ImmutableList.of(), false, null));
    }
}
