package dev.terrarasa;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import dev.terrarasa.mixin.ChunkMapAccessor;
import dev.terrarasa.mixin.MinecraftServerAccessor;
import dev.terrarasa.mixin.PrimaryLevelDataAccessor;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.PlayerRespawnLogic;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.npc.WanderingTraderSpawner;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.storage.CommandStorage;
import net.minecraft.world.level.storage.PrimaryLevelData;
import net.minecraft.world.level.storage.ServerLevelData;
import net.minecraft.world.phys.Vec3;

/** Minecraft APIs for the 1.21.1 and 1.21.4 releases. */
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

    /** The classic Overworld shares its root with global world files, so swap only these dimension stores. */
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
        data.setWanderingTraderSpawnDelay(WanderingTraderSpawner.DEFAULT_SPAWN_DELAY);
        data.setWanderingTraderSpawnChance(25);
        data.setWanderingTraderId(null);
    }

    public static Set<String> entityTags(ServerPlayer player) {
        return player.getTags();
    }

    public static ResourceIdentifier dimensionId(ResourceKey<Level> key) {
        return ResourceIdentifier.of(key.location().getNamespace(), key.location().getPath());
    }

    public static ResourceIdentifier stemId(ResourceKey<?> key) {
        return ResourceIdentifier.of(key.location().getNamespace(), key.location().getPath());
    }

    public static ResourceKey<Level> dimensionKey(ResourceIdentifier id) {
        return ResourceKey.create(Registries.DIMENSION, ResourceLocation.fromNamespaceAndPath(id.namespace(), id.path()));
    }

    public static ResourceLocation toStemIdentifier(ResourceIdentifier id) {
        return ResourceLocation.fromNamespaceAndPath(id.namespace(), id.path());
    }

    @SuppressWarnings("unchecked")
    private static net.minecraft.core.Registry<LevelStem> stemRegistry(MinecraftServer server) {
        var ra = server.registryAccess();
        for (var method : ra.getClass().getMethods()) {
            if (method.getParameterCount() == 1 && method.getParameterTypes()[0] == ResourceKey.class
                    && net.minecraft.core.Registry.class.isAssignableFrom(method.getReturnType())) {
                try {
                    method.setAccessible(true);
                    return (net.minecraft.core.Registry<LevelStem>) method.invoke(ra, Registries.LEVEL_STEM);
                } catch (Exception e) {
                    throw new RuntimeException("Failed to invoke registry access method " + method, e);
                }
            }
        }
        throw new RuntimeException("Could not find registry accessor on " + ra.getClass());
    }

    public static List<ResourceIdentifier> stemIds(MinecraftServer server) {
        return stemRegistry(server).keySet().stream()
                .map(loc -> ResourceIdentifier.of(loc.getNamespace(), loc.getPath())).toList();
    }

    public static List<Map.Entry<ResourceKey<LevelStem>, LevelStem>> levelStemEntries(MinecraftServer server) {
        return new ArrayList<>(stemRegistry(server).entrySet());
    }

    public static LevelStem getStem(MinecraftServer server, ResourceIdentifier target) {
        return getStemFromRegistry(stemRegistry(server), ResourceLocation.fromNamespaceAndPath(target.namespace(), target.path()));
    }

    public static LevelStem getStem(MinecraftServer server, ResourceKey<Level> key) {
        return getStemFromRegistry(stemRegistry(server), key.location());
    }

    @SuppressWarnings("unchecked")
    private static LevelStem getStemFromRegistry(net.minecraft.core.Registry<LevelStem> registry, ResourceLocation loc) {
        for (var method : registry.getClass().getMethods()) {
            if (method.getParameterCount() == 1 && method.getParameterTypes()[0] == ResourceLocation.class) {
                try {
                    method.setAccessible(true);
                    Object res = method.invoke(registry, loc);
                    if (res instanceof Optional<?> opt) {
                        return opt.map(h -> ((net.minecraft.core.Holder<LevelStem>) h).value()).orElse(null);
                    } else if (res instanceof LevelStem stem) {
                        return stem;
                    }
                } catch (Exception ignored) {
                }
            }
        }
        return null;
    }

    public static String gameVersionId() {
        return SharedConstants.getCurrentVersion().getId();
    }

    public static void resetCommandStorage(MinecraftServer server) {
        CommandStorage storage = server.getCommandStorage();
        for (ResourceLocation id : storage.keys().toList()) {
            storage.set(id, new CompoundTag());
        }
    }

    public static void clearPlayerEnderPearls(ServerPlayer player) {
        for (ServerLevel level : player.server.getAllLevels()) {
            for (Entity entity : level.getAllEntities()) {
                if (entity instanceof net.minecraft.world.entity.projectile.ThrownEnderpearl pearl) {
                    if (pearl.getOwner() == player) {
                        pearl.discard();
                    }
                }
            }
        }
    }

    public static void clearPlayerRespawnPosition(ServerPlayer player) {
        player.setRespawnPosition(Level.OVERWORLD, null, 0.0f, false, false);
    }

    public static BlockPos getSpawnPosInChunk(ServerLevel level, ChunkPos pos) {
        return PlayerRespawnLogic.getSpawnPosInChunk(level, pos);
    }

    public static void deactivateTicketsOnClosing(ServerChunkCache chunkSource) {
        chunkSource.removeTicketsOnClosing();
    }

    @SuppressWarnings("unchecked")
    public static void addFullTicket(ServerLevel level, TicketType type, ChunkPos pos) {
        level.getChunkSource().addRegionTicket((TicketType<ChunkPos>) (Object) type, pos, 0, pos);
    }

    @SuppressWarnings("unchecked")
    public static void removeFullTicket(ServerLevel level, TicketType type, ChunkPos pos) {
        level.getChunkSource().removeRegionTicket((TicketType<ChunkPos>) (Object) type, pos, 0, pos);
    }

    @SuppressWarnings("unchecked")
    public static void addRegionTicket(ServerLevel level, TicketType type, ChunkPos pos, int radius) {
        level.getChunkSource().addRegionTicket((TicketType<ChunkPos>) (Object) type, pos, radius, pos);
    }

    @SuppressWarnings("unchecked")
    public static void removeRegionTicket(ServerLevel level, TicketType type, ChunkPos pos, int radius) {
        level.getChunkSource().removeRegionTicket((TicketType<ChunkPos>) (Object) type, pos, radius, pos);
    }

    public static void registerTicketTypes() {
        Pregenerator.TICKET = TicketType.create("terrarasa:pregenerate", (a, b) -> 0);
        WorldPreparationService.PRELOAD_TICKET = TicketType.create("terrarasa:preload", (a, b) -> 0);
    }

    public static CompletableFuture<Vec3> findSpawn(ServerLevel overworld) {
        BlockPos pos = overworld.getChunkSource().randomState().sampler().findSpawnPosition();
        return CompletableFuture.completedFuture(pos != null ? pos.getCenter() : Vec3.ZERO);
    }

    public static void activateSavedTickets(ServerLevel level) {
        // Tickets in 1.21.1 / 1.21.4 do not use persistent TicketStorage.
    }

    public static int countTickingChunks(ServerLevel level) {
        return level.getChunkSource().getTickingGenerated();
    }

    public static int getMinY(ServerLevel level) {
        for (var method : net.minecraft.world.level.LevelHeightAccessor.class.getDeclaredMethods()) {
            if (method.getParameterCount() == 0 && method.getReturnType() == int.class
                    && java.lang.reflect.Modifier.isAbstract(method.getModifiers())) {
                try {
                    int val = (int) method.invoke(level);
                    if (val != level.getHeight()) {
                        return val;
                    }
                } catch (Exception ignored) {
                }
            }
        }
        return -64;
    }

    public static void updateMobSpawningFlags(MinecraftServer server) {
        ((MinecraftServerAccessor) server).terrarasa$updateMobSpawningFlags();
    }

    public static boolean hasPermission(net.minecraft.commands.CommandSourceStack source, int level) {
        return source.hasPermission(level);
    }

    public static String playerGameModeName(ServerPlayer player) {
        return player.gameMode.getGameModeForPlayer().getName();
    }

    public static String playerName(ServerPlayer player) {
        return player.getGameProfile().getName();
    }

    public static void runAsync(Runnable runnable) {
        net.minecraft.Util.ioPool().execute(runnable);
    }

    public static CompletableFuture<Void> flushLevelIoAsync(ServerLevel level) {
        return CompletableFuture.runAsync(() -> {
            level.save(null, false, false);
            level.getChunkSource().chunkMap.flushWorker();
            var dataStorage = level.getChunkSource().getDataStorage();
            for (var method : dataStorage.getClass().getMethods()) {
                if (method.getParameterCount() == 0 && method.getReturnType() == void.class
                        && !method.getName().equals("close") && !method.getName().equals("wait")
                        && !method.getName().equals("notify") && !method.getName().equals("notifyAll")) {
                    try {
                        method.setAccessible(true);
                        method.invoke(dataStorage);
                        break;
                    } catch (Exception ignored) {
                    }
                }
            }
        }, net.minecraft.Util.ioPool());
    }

    public static boolean isChunkStatusReached(ServerLevel level, ChunkPos pos, ChunkStatus target) {
        ChunkHolder holder = ((ChunkMapAccessor) level.getChunkSource().chunkMap)
                .terrarasa$getVisibleChunkIfPresent(packChunkPos(chunkX(pos), chunkZ(pos)));
        ChunkStatus reached = holder != null ? holder.getLatestStatus() : null;
        return reached != null && reached.isOrAfter(target);
    }

    public static void teleport(ServerPlayer player, ServerLevel level, Vec3 pos, float yRot, float xRot) {
        try {
            for (var method : ServerPlayer.class.getMethods()) {
                if (method.getParameterCount() == 1 && Entity.class.isAssignableFrom(method.getReturnType())) {
                    Class<?> paramType = method.getParameterTypes()[0];
                    for (var ctor : paramType.getConstructors()) {
                        Class<?>[] pTypes = ctor.getParameterTypes();
                        if (pTypes.length == 6
                                && pTypes[0] == ServerLevel.class
                                && pTypes[1] == Vec3.class
                                && pTypes[2] == Vec3.class
                                && pTypes[3] == float.class
                                && pTypes[4] == float.class) {
                            Class<?> postClass = pTypes[5];
                            Object post = null;
                            for (var field : paramType.getFields()) {
                                if (java.lang.reflect.Modifier.isStatic(field.getModifiers()) && postClass.isAssignableFrom(field.getType())) {
                                    post = field.get(null);
                                    break;
                                }
                            }
                            if (post == null && postClass.isInterface()) {
                                post = java.lang.reflect.Proxy.newProxyInstance(paramType.getClassLoader(), new Class<?>[]{postClass}, (proxy, m, args) -> null);
                            }
                            Object transition = ctor.newInstance(level, pos, Vec3.ZERO, yRot, xRot, post);
                            method.invoke(player, transition);
                            return;
                        }
                    }
                }
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to teleport player", e);
        }
    }
}
