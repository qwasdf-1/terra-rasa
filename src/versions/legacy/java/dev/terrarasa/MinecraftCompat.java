package dev.terrarasa;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import dev.terrarasa.mixin.ChunkMapAccessor;
import dev.terrarasa.mixin.EntityStorageAccessor;
import dev.terrarasa.mixin.PersistentEntitySectionManagerAccessor;
import dev.terrarasa.mixin.PrimaryLevelDataAccessor;
import dev.terrarasa.mixin.SectionStorageAccessor;
import dev.terrarasa.mixin.ServerLevelAccessor;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ChunkLevel;
import net.minecraft.server.level.FullChunkStatus;
import net.minecraft.server.level.PlayerSpawnFinder;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.Ticket;
import net.minecraft.server.level.TicketType;
import net.minecraft.util.Util;
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownEnderpearl;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.TicketStorage;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.chunk.storage.EntityStorage;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.level.storage.CommandStorage;
import net.minecraft.world.level.storage.PrimaryLevelData;
import net.minecraft.world.level.storage.ServerLevelData;
import net.minecraft.world.phys.Vec3;

/** Minecraft APIs that changed between the obfuscated 1.21.11 line and 26.x. */
public final class MinecraftCompat {
    private static final ResourceIdentifier OVERWORLD = ResourceIdentifier.fromNamespaceAndPath("minecraft", "overworld");
    private static final ResourceIdentifier NETHER = ResourceIdentifier.fromNamespaceAndPath("minecraft", "the_nether");
    private static final ResourceIdentifier END = ResourceIdentifier.fromNamespaceAndPath("minecraft", "the_end");

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
    public static Path dimensionPath(Path worldRoot, ResourceIdentifier dimension) {
        if (OVERWORLD.equals(dimension)) {
            return worldRoot;
        }
        if (NETHER.equals(dimension)) {
            return worldRoot.resolve("DIM-1");
        }
        if (END.equals(dimension)) {
            return worldRoot.resolve("DIM1");
        }
        return worldRoot.resolve("dimensions").resolve(dimension.namespace()).resolve(dimension.path());
    }

    /** The legacy Overworld shares its root with global world files, so swap only these dimension stores. */
    public static List<String> rootDimensionStorageParts(ResourceIdentifier dimension) {
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

    public static ResourceIdentifier dimensionId(ResourceKey<Level> key) {
        return ResourceIdentifier.of(key.identifier().getNamespace(), key.identifier().getPath());
    }

    public static ResourceIdentifier stemId(ResourceKey<?> key) {
        return ResourceIdentifier.of(key.identifier().getNamespace(), key.identifier().getPath());
    }

    public static ResourceKey<Level> dimensionKey(ResourceIdentifier id) {
        return ResourceKey.create(Registries.DIMENSION, Identifier.fromNamespaceAndPath(id.namespace(), id.path()));
    }

    public static Identifier toStemIdentifier(ResourceIdentifier id) {
        return Identifier.fromNamespaceAndPath(id.namespace(), id.path());
    }

    public static List<ResourceIdentifier> stemIds(MinecraftServer server) {
        return server.registryAccess().lookupOrThrow(Registries.LEVEL_STEM).keySet().stream()
                .map(loc -> ResourceIdentifier.of(loc.getNamespace(), loc.getPath())).toList();
    }

    public static List<Map.Entry<ResourceKey<LevelStem>, LevelStem>> levelStemEntries(MinecraftServer server) {
        return new ArrayList<>(server.registryAccess().lookupOrThrow(Registries.LEVEL_STEM).entrySet());
    }

    public static LevelStem getStem(MinecraftServer server, ResourceIdentifier target) {
        return server.registryAccess().lookupOrThrow(Registries.LEVEL_STEM)
                .getValue(Identifier.fromNamespaceAndPath(target.namespace(), target.path()));
    }

    public static LevelStem getStem(MinecraftServer server, ResourceKey<Level> key) {
        return server.registryAccess().lookupOrThrow(Registries.LEVEL_STEM)
                .getValue(key.identifier());
    }

    public static String gameVersionId() {
        return SharedConstants.getCurrentVersion().id();
    }

    public static void resetCommandStorage(MinecraftServer server) {
        CommandStorage storage = server.getCommandStorage();
        for (Identifier id : storage.keys().toList()) {
            storage.set(id, new CompoundTag());
        }
    }

    public static void clearPlayerEnderPearls(ServerPlayer player) {
        for (ThrownEnderpearl pearl : new ArrayList<>(player.getEnderPearls())) {
            pearl.discard();
        }
        player.getEnderPearls().clear();
    }

    public static void clearPlayerRespawnPosition(ServerPlayer player) {
        player.setRespawnPosition(null, false);
    }

    public static BlockPos getSpawnPosInChunk(ServerLevel level, ChunkPos pos) {
        return PlayerSpawnFinder.getSpawnPosInChunk(level, pos);
    }

    public static void deactivateTicketsOnClosing(ServerChunkCache chunkSource) {
        chunkSource.deactivateTicketsOnClosing();
    }

    public static void addFullTicket(ServerLevel level, TicketType type, ChunkPos pos) {
        level.getChunkSource().getDataStorage().computeIfAbsent(TicketStorage.TYPE)
                .addTicket(new Ticket(type, ChunkLevel.byStatus(FullChunkStatus.FULL)), pos);
    }

    public static void removeFullTicket(ServerLevel level, TicketType type, ChunkPos pos) {
        level.getChunkSource().getDataStorage().computeIfAbsent(TicketStorage.TYPE)
                .removeTicket(new Ticket(type, ChunkLevel.byStatus(FullChunkStatus.FULL)), pos);
    }

    public static void addRegionTicket(ServerLevel level, TicketType type, ChunkPos pos, int radius) {
        level.getChunkSource().getDataStorage().computeIfAbsent(TicketStorage.TYPE)
                .addTicket(new Ticket(type, ChunkLevel.byStatus(ChunkStatus.FULL) - radius), pos);
    }

    public static void removeRegionTicket(ServerLevel level, TicketType type, ChunkPos pos, int radius) {
        level.getChunkSource().getDataStorage().computeIfAbsent(TicketStorage.TYPE)
                .removeTicket(new Ticket(type, ChunkLevel.byStatus(ChunkStatus.FULL) - radius), pos);
    }

    public static void registerTicketTypes() {
        Pregenerator.TICKET = Registry.register(BuiltInRegistries.TICKET_TYPE,
                Identifier.fromNamespaceAndPath(TerraRasa.MOD_ID, "pregenerate"), new TicketType(0L, TicketType.FLAG_LOADING));
        WorldPreparationService.PRELOAD_TICKET = Registry.register(BuiltInRegistries.TICKET_TYPE,
                Identifier.fromNamespaceAndPath(TerraRasa.MOD_ID, "preload"), new TicketType(0L, TicketType.FLAG_LOADING));
    }

    public static CompletableFuture<Vec3> findSpawn(ServerLevel overworld) {
        return PlayerSpawnFinder.findSpawn(overworld, BlockPos.ZERO);
    }

    public static void activateSavedTickets(ServerLevel level) {
        TicketStorage tickets = level.getDataStorage().get(TicketStorage.TYPE);
        if (tickets != null) {
            tickets.activateAllDeactivatedTickets();
        }
    }

    public static int countTickingChunks(ServerLevel level) {
        int[] ticking = {0};
        ((ChunkMapAccessor) level.getChunkSource().chunkMap)
                .terrarasa$forEachBlockTickingChunk(chunk -> ticking[0]++);
        return ticking[0];
    }

    public static int getMinY(ServerLevel level) {
        return level.getMinY();
    }

    public static void updateMobSpawningFlags(MinecraftServer server) {
        server.updateMobSpawningFlags();
    }

    public static boolean hasPermission(net.minecraft.commands.CommandSourceStack source, int level) {
        return net.minecraft.commands.Commands.hasPermission(net.minecraft.commands.Commands.LEVEL_GAMEMASTERS).test(source);
    }

    public static String playerGameModeName(ServerPlayer player) {
        return player.gameMode().getName();
    }

    public static String playerName(ServerPlayer player) {
        return player.getGameProfile().name();
    }

    public static void runAsync(Runnable runnable) {
        Util.ioPool().execute(runnable);
    }

    public static CompletableFuture<Void> flushLevelIoAsync(ServerLevel level) {
        level.save(null, false, false);
        CompletableFuture<?> chunks = level.getChunkSource().chunkMap.synchronize(true);
        CompletableFuture<?> poi = ((SectionStorageAccessor) level.getPoiManager()).terrarasa$regionStorage().synchronize(true);
        CompletableFuture<?> entities = CompletableFuture.completedFuture(null);
        Object storage = ((PersistentEntitySectionManagerAccessor) ((ServerLevelAccessor) level).terrarasa$entityManager())
                .terrarasa$permanentStorage();
        if (storage instanceof EntityStorage entityStorage) {
            entities = ((EntityStorageAccessor) entityStorage).terrarasa$regionStorage().synchronize(true);
        }
        CompletableFuture<?> data = level.getChunkSource().getDataStorage().scheduleSave();
        return CompletableFuture.allOf(chunks, poi, entities, data);
    }

    public static boolean isChunkStatusReached(ServerLevel level, ChunkPos pos, ChunkStatus target) {
        ChunkStatus reached = level.getChunkSource().chunkMap.getLatestStatus(packChunkPos(chunkX(pos), chunkZ(pos)));
        return reached != null && reached.isOrAfter(target);
    }

    public static void teleport(ServerPlayer player, ServerLevel level, Vec3 pos, float yRot, float xRot) {
        player.teleport(new TeleportTransition(level, pos, Vec3.ZERO, yRot, xRot, TeleportTransition.DO_NOTHING));
    }
}
