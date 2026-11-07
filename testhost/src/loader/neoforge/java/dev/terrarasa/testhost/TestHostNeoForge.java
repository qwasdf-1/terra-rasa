package dev.terrarasa.testhost;

import java.nio.file.Path;
import java.util.function.Consumer;

import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.MinecraftServer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/** NeoForge entrypoint for the test host. */
@Mod("terrarasa_testhost")
public final class TestHostNeoForge {
    public TestHostNeoForge() {
        TestHost.bootstrap(new NeoForgePlatform());
    }

    private static final class NeoForgePlatform implements HostPlatform {
        @Override
        public Path configFile(String name) {
            return FMLPaths.CONFIGDIR.get().resolve(name);
        }

        @Override
        public void registerCommands(Consumer<CommandDispatcher<CommandSourceStack>> callback) {
            NeoForge.EVENT_BUS.addListener(RegisterCommandsEvent.class, event -> callback.accept(event.getDispatcher()));
        }

        @Override
        public void onServerStarted(Consumer<MinecraftServer> callback) {
            NeoForge.EVENT_BUS.addListener(ServerStartedEvent.class, event -> callback.accept(event.getServer()));
        }

        @Override
        public void onServerStopped(Runnable callback) {
            NeoForge.EVENT_BUS.addListener(ServerStoppedEvent.class, event -> callback.run());
        }

        @Override
        public void onEndServerTick(Consumer<MinecraftServer> callback) {
            NeoForge.EVENT_BUS.addListener(ServerTickEvent.Post.class, event -> callback.accept(event.getServer()));
        }
    }
}
