package dev.terrarasa.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.terrarasa.PinnedSeed;
import dev.terrarasa.SeedOverride;
import net.minecraft.server.MinecraftServer;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.WorldGenSettings;
import net.minecraft.world.level.levelgen.WorldOptions;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Per-level seeds. Staged levels carry their run's seed as an override; dimensions that runs never reset
 * keep the world's pinned original seed. Canonical levels carry neither: they read the server-global
 * seed, which activation rebinds to the run seed.
 */
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
        // During construction (ChunkMap/RandomState creation) the fields are not assigned yet.
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

    /** Structure checks, structure manager options and the dragon-fight gateway shuffle read the constructor-local seed. */
    @WrapOperation(
            method = "<init>",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/server/MinecraftServer;getWorldGenSettings()Lnet/minecraft/world/level/levelgen/WorldGenSettings;"))
    private WorldGenSettings terrarasa$overrideConstructorOptions(MinecraftServer server, Operation<WorldGenSettings> original) {
        WorldGenSettings settings = original.call(server);
        Long seed = terrarasa$resolveOwnSeed();
        if (seed == null) {
            return settings;
        }
        WorldOptions options = settings.options();
        return new WorldGenSettings(
                new WorldOptions(seed, options.generateStructures(), options.generateBonusChest()),
                settings.dimensions());
    }

    @Override
    public @Nullable Long terrarasa$seedOverride() {
        return this.terrarasa$seedOverride;
    }
}
