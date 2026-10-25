package dev.terrarasa;

import dev.terrarasa.compat.ChunkyCompat;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.loader.api.FabricLoader;

/** Fabric entrypoint: registers ticket types, live pregeneration and the adapters for installed mods. */
public final class TerraRasaInitializer implements ModInitializer {
    @Override
    public void onInitialize() {
        // Registers the ticket types while built-in registries are still open.
        TerraRasa.LOGGER.debug("Ticket types: {}, {}", WorldPreparationService.PRELOAD_TICKET, Pregenerator.TICKET);
        ServerLifecycleEvents.SERVER_STARTED.register(LivePregeneration::onServerStarted);
        ServerTickEvents.END_SERVER_TICK.register(LivePregeneration::tick);
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> LivePregeneration.onServerStopping());
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> GenerationBudget.clear());
        // Adapters for mods known to cache levels; each is inert unless its mod is installed.
        if (FabricLoader.getInstance().isModLoaded(ChunkyCompat.MOD_ID)) {
            ChunkyCompat.register();
        }
    }
}
