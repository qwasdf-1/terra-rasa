package dev.terrarasa.mixin;

import java.util.Map;
import java.util.concurrent.Executor;

import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.LevelStorageSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Narrow access to the server-owned level map and generation context used by canonical activation. */
@Mixin(MinecraftServer.class)
public interface MinecraftServerAccessor {
    @Accessor("levels")
    Map<ResourceKey<Level>, ServerLevel> terrarasa$levels();

    @Accessor("storageSource")
    LevelStorageSource.LevelStorageAccess terrarasa$storageSource();

    @Accessor("executor")
    Executor terrarasa$executor();

    @Invoker("updateEffectiveRespawnData")
    void terrarasa$updateEffectiveRespawnData();
}
