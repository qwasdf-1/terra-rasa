package dev.terrarasa;

import java.nio.file.Path;
import java.util.function.Consumer;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/** NeoForge entrypoint: builds the platform adapter and registers the shared runtime wiring. */
@Mod(TerraRasa.MOD_ID)
public final class TerraRasaNeoForge {
    public TerraRasaNeoForge() {
        // Runs while the built-in registries still accept the ticket types registered below.
        TerraRasaRuntime.register(new NeoForgePlatform());
    }

    private static final class NeoForgePlatform implements ModPlatform {
        @Override
        public boolean isModLoaded(String modId) {
            return ModList.get().isLoaded(modId);
        }

        @Override
        public Path configFile(String name) {
            return FMLPaths.CONFIGDIR.get().resolve(name);
        }

        @Override
        public void onServerStarted(Consumer<MinecraftServer> callback) {
            NeoForge.EVENT_BUS.addListener(ServerStartedEvent.class, event -> callback.accept(event.getServer()));
        }

        @Override
        public void onServerStopping(Runnable callback) {
            NeoForge.EVENT_BUS.addListener(ServerStoppingEvent.class, event -> callback.run());
        }

        @Override
        public void onServerStopped(Consumer<MinecraftServer> callback) {
            NeoForge.EVENT_BUS.addListener(ServerStoppedEvent.class, event -> callback.accept(event.getServer()));
        }

        @Override
        public void onEndServerTick(Consumer<MinecraftServer> callback) {
            NeoForge.EVENT_BUS.addListener(ServerTickEvent.Post.class, event -> callback.accept(event.getServer()));
        }

        @Override
        public void fireLevelLoaded(MinecraftServer server, ServerLevel level) {
            NeoForge.EVENT_BUS.post(new LevelEvent.Load(level));
        }

        @Override
        public void fireLevelUnloaded(MinecraftServer server, ServerLevel level) {
            NeoForge.EVENT_BUS.post(new LevelEvent.Unload(level));
        }
    }
}
