package dev.terrarasa;

import java.nio.file.Path;
import java.util.function.Consumer;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLevelEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

/** Fabric entrypoint: builds the platform adapter and registers the shared runtime wiring. */
public final class TerraRasaInitializer implements ModInitializer {
    @Override
    public void onInitialize() {
        TerraRasaRuntime.register(new FabricPlatform());
    }

    private record FabricPlatform() implements ModPlatform {
        @Override
        public boolean isModLoaded(String modId) {
            return FabricLoader.getInstance().isModLoaded(modId);
        }

        @Override
        public Path configFile(String name) {
            return FabricLoader.getInstance().getConfigDir().resolve(name);
        }

        @Override
        public void onServerStarted(Consumer<MinecraftServer> callback) {
            ServerLifecycleEvents.SERVER_STARTED.register(callback::accept);
        }

        @Override
        public void onServerStopping(Runnable callback) {
            ServerLifecycleEvents.SERVER_STOPPING.register(server -> callback.run());
        }

        @Override
        public void onServerStopped(Consumer<MinecraftServer> callback) {
            ServerLifecycleEvents.SERVER_STOPPED.register(callback::accept);
        }

        @Override
        public void onEndServerTick(Consumer<MinecraftServer> callback) {
            ServerTickEvents.END_SERVER_TICK.register(callback::accept);
        }

        @Override
        public void fireLevelLoaded(MinecraftServer server, ServerLevel level) {
            ServerLevelEvents.LOAD.invoker().onLevelLoad(server, level);
        }

        @Override
        public void fireLevelUnloaded(MinecraftServer server, ServerLevel level) {
            ServerLevelEvents.UNLOAD.invoker().onLevelUnload(server, level);
        }
    }
}
