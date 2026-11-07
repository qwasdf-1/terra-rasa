package dev.terrarasa.testhost;

import java.nio.file.Path;
import java.util.function.Consumer;

import com.mojang.brigadier.CommandDispatcher;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.MinecraftServer;

/** Fabric entrypoint for the test host. */
public final class TestHostFabric implements ModInitializer {
    @Override
    public void onInitialize() {
        TestHost.bootstrap(new FabricPlatform());
    }

    private record FabricPlatform() implements HostPlatform {
        @Override
        public Path configFile(String name) {
            return FabricLoader.getInstance().getConfigDir().resolve(name);
        }

        @Override
        public void registerCommands(Consumer<CommandDispatcher<CommandSourceStack>> callback) {
            CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> callback.accept(dispatcher));
        }

        @Override
        public void onServerStarted(Consumer<MinecraftServer> callback) {
            ServerLifecycleEvents.SERVER_STARTED.register(callback::accept);
        }

        @Override
        public void onServerStopped(Runnable callback) {
            ServerLifecycleEvents.SERVER_STOPPED.register(server -> callback.run());
        }

        @Override
        public void onEndServerTick(Consumer<MinecraftServer> callback) {
            ServerTickEvents.END_SERVER_TICK.register(callback::accept);
        }
    }
}
