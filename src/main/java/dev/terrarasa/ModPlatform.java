package dev.terrarasa;

import java.nio.file.Path;
import java.util.function.Consumer;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

/**
 * The loader hooks Terra Rasa needs, expressed with vanilla types only.
 *
 * <p>Each loader implements this once in its entrypoint overlay
 * ({@code src/loader/<loader>/java}) and hands it to {@link TerraRasaRuntime#register}, so the
 * services, mixins, events and the public API never reference a loader type. The callbacks run on
 * the server thread.
 */
public interface ModPlatform {

    /** True when the named mod is installed, so optional adapters can decide to activate. */
    boolean isModLoaded(String modId);

    /** This mod's config file under the loader's config directory. */
    Path configFile(String name);

    /** The server started and every dimension is live: world-level work may begin. */
    void onServerStarted(Consumer<MinecraftServer> callback);

    /** The server is stopping but levels are still open. */
    void onServerStopping(Runnable callback);

    /** The server has fully stopped: shared state may be cleared. */
    void onServerStopped(Consumer<MinecraftServer> callback);

    /** End of a server tick: outside level iteration and packet handling. */
    void onEndServerTick(Consumer<MinecraftServer> callback);

    /** The loader's own level-load notification, fired for a server level that just came online. */
    void fireLevelLoaded(MinecraftServer server, ServerLevel level);

    /** The loader's own level-unload notification, fired for a server level that just closed. */
    void fireLevelUnloaded(MinecraftServer server, ServerLevel level);
}
