package dev.terrarasa.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.terrarasa.PinnedSeed;
import dev.terrarasa.SeedOverride;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.storage.WorldData;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** 1.21.11 counterpart: generation options come from WorldData rather than a saved-data settings object. */
@Mixin(ServerLevel.class)
abstract class ServerLevelMixin implements SeedOverride.Holder {
    @Unique
    private @Nullable Long terrarasa$seedOverride;
    @Unique
    private @Nullable Long terrarasa$ownSeed;
    @Unique
    private boolean terrarasa$constructed;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void terrarasa$captureSeedOverride(CallbackInfo ci) {
        this.terrarasa$seedOverride = SeedOverride.pendingFor(((ServerLevel) (Object) this).dimension());
        this.terrarasa$ownSeed = terrarasa$resolveOwnSeed();
        this.terrarasa$constructed = true;
    }

    @Inject(method = "getSeed", at = @At("HEAD"), cancellable = true)
    private void terrarasa$overrideSeed(CallbackInfoReturnable<Long> cir) {
        Long seed = this.terrarasa$constructed ? this.terrarasa$ownSeed : terrarasa$resolveOwnSeed();
        if (seed != null) {
            cir.setReturnValue(seed);
        }
    }

    @Unique
    private @Nullable Long terrarasa$resolveOwnSeed() {
        ResourceKey<Level> dimension = ((ServerLevel) (Object) this).dimension();
        Long override = SeedOverride.pendingFor(dimension);
        return override != null ? override : PinnedSeed.forDimension(dimension);
    }

    @WrapOperation(method = "<init>", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/storage/WorldData;worldGenOptions()Lnet/minecraft/world/level/levelgen/WorldOptions;"))
    private WorldOptions terrarasa$overrideConstructorOptions(WorldData worldData, Operation<WorldOptions> original) {
        WorldOptions options = original.call(worldData);
        Long seed = terrarasa$resolveOwnSeed();
        if (seed == null) {
            return options;
        }
        return new WorldOptions(seed, options.generateStructures(), options.generateBonusChest());
    }

    @Override
    public @Nullable Long terrarasa$seedOverride() {
        return this.terrarasa$seedOverride;
    }
}
