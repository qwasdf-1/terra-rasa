package dev.terrarasa.mixin;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.DistanceManager;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Processes queued chunk updates from a stable batch while callbacks enqueue more work. */
@Mixin(DistanceManager.class)
public abstract class DistanceManagerMixin {
    @Shadow @Final @Mutable protected Set<ChunkHolder> chunksToUpdateFutures;

    @Unique private List<ChunkHolder> terrarasa$currentBatch = List.of();

    @Inject(method = "<init>", at = @At("TAIL"))
    private void terrarasa$useSynchronizedIdentitySet(CallbackInfo ci) {
        this.chunksToUpdateFutures = Collections.synchronizedSet(Collections.newSetFromMap(new IdentityHashMap<>()));
    }

    @Inject(method = "runAllUpdates", at = @At("HEAD"))
    private void terrarasa$captureUpdateBatch(CallbackInfoReturnable<Boolean> cir) {
        this.terrarasa$currentBatch = List.of();
    }

    @Redirect(method = "runAllUpdates", at = @At(value = "INVOKE", target = "Ljava/util/Set;iterator()Ljava/util/Iterator;"))
    private Iterator<ChunkHolder> terrarasa$iterateUpdateBatch(Set<ChunkHolder> pending) {
        synchronized (pending) {
            this.terrarasa$currentBatch = new ArrayList<>(pending);
        }
        return this.terrarasa$currentBatch.iterator();
    }

    @Redirect(method = "runAllUpdates", at = @At(value = "INVOKE", target = "Ljava/util/Set;clear()V"))
    private void terrarasa$removeProcessedBatch(Set<ChunkHolder> pending) {
        synchronized (pending) {
            // Remove only entries present when the second pass began. Updates created during that pass
            // stay queued for the next call instead of being lost to Set.clear().
            for (ChunkHolder holder : this.terrarasa$currentBatch) {
                pending.remove(holder);
            }
        }
    }

    @Inject(method = "runAllUpdates", at = @At("RETURN"))
    private void terrarasa$discardUpdateBatch(CallbackInfoReturnable<Boolean> cir) {
        this.terrarasa$currentBatch = List.of();
    }
}
