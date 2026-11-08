package dev.terrarasa.testhost;

import java.nio.file.Path;
import java.util.function.Consumer;

import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.MinecraftServer;

/**
 * The loader hooks the test host needs, with vanilla types only. Each loader's entrypoint overlay
 * ({@code src/loader/<loader>/java}) builds one and calls {@link TestHost#bootstrap}.
 */
public interface HostPlatform {
    /** This mod's config file under the loader's config directory. */
    Path configFile(String name);

    /** Command registration: call the callback with the dispatcher whenever commands are built. */
    void registerCommands(Consumer<CommandDispatcher<CommandSourceStack>> callback);

    void onServerStarted(Consumer<MinecraftServer> callback);

    void onServerStopped(Runnable callback);

    void onEndServerTick(Consumer<MinecraftServer> callback);
}
