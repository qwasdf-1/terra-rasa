package dev.terrarasa.mixin;

import dev.terrarasa.LevelRetirement;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.entity.ai.village.poi.PoiRecord;
import net.minecraft.world.entity.ai.village.poi.PoiType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * A block change queues its POI update as a server task ({@code ServerLevel.updatePOIOnBlockStateChange}).
 * Chunks finished while a level is drained queue such tasks too, and they run only after the level is
 * closed, when reading a POI section from its closed storage fails (or, with vanilla IO, may never
 * complete). The level is discarded, so the update has nothing left to keep consistent.
 */
@Mixin(PoiManager.class)
abstract class PoiManagerMixin implements LevelRetirement.ClosedPoi {
    @Unique
    private boolean terrarasa$closed;

    @Override
    public void terrarasa$markClosed() {
        this.terrarasa$closed = true;
    }

    @Inject(method = "add", at = @At("HEAD"), cancellable = true)
    private void terrarasa$ignoreAddAfterClose(BlockPos pos, Holder<PoiType> type, CallbackInfoReturnable<PoiRecord> cir) {
        if (this.terrarasa$closed) {
            LevelRetirement.poiUpdateIgnored();
            cir.setReturnValue(null);
        }
    }

    @Inject(method = "remove", at = @At("HEAD"), cancellable = true)
    private void terrarasa$ignoreRemoveAfterClose(BlockPos pos, CallbackInfo ci) {
        if (this.terrarasa$closed) {
            LevelRetirement.poiUpdateIgnored();
            ci.cancel();
        }
    }
}
