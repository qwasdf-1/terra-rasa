package dev.terrarasa.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.terrarasa.LivePregeneration;
import dev.terrarasa.StartupRecovery;
import dev.terrarasa.WorldPreparationService;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.players.PlayerList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MinecraftServer.class)
abstract class MinecraftServerMixin {
    /**
     * Reconciles an interrupted activation before vanilla opens any dimension storage, so no canonical
     * directory is ever opened in a half-promoted state.
     */
    @Inject(method = "createLevels", at = @At("HEAD"))
    private void terrarasa$recoverInterruptedActivation(CallbackInfo ci) {
        StartupRecovery.recover((MinecraftServer) (Object) this);
    }

    /**
     * An empty server keeps ticking while a run is being prepared or pregenerated (see
     * {@link WorldPreparationService#keepsServerAwake} and {@link LivePregeneration#keepsServerAwake}).
     */
    @WrapOperation(method = "tickServer", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/players/PlayerList;getPlayerCount()I"))
    private int terrarasa$stayAwakeWhilePreparing(PlayerList players, Operation<Integer> original) {
        int count = original.call(players);
        boolean working = WorldPreparationService.keepsServerAwake() || LivePregeneration.keepsServerAwake();
        return count == 0 && working ? 1 : count;
    }
}
