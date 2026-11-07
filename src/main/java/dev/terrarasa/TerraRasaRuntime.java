package dev.terrarasa;

import dev.terrarasa.compat.ChunkyCompat;

/**
 * Loader-neutral wiring, shared by every loader entrypoint.
 *
 * <p>A loader's entrypoint builds a {@link ModPlatform} and calls {@link #register} once, which
 * registers the ticket types, the lifecycle hooks and the adapters for installed mods. Nothing
 * here differs between Fabric and NeoForge; the difference lives entirely in the overlay.
 */
public final class TerraRasaRuntime {
    private TerraRasaRuntime() {
    }

    /** Registers lifecycle hooks and optional adapters. Call exactly once per mod load. */
    public static void register(ModPlatform platform) {
        TerraRasa.setPlatform(platform);
        // Touching the ticket types registers them while the built-in registries accept additions.
        TerraRasa.LOGGER.debug("Ticket types: {}, {}", WorldPreparationService.PRELOAD_TICKET, Pregenerator.TICKET);

        platform.onServerStarted(LivePregeneration::onServerStarted);
        platform.onEndServerTick(LivePregeneration::tick);
        platform.onServerStopping(() -> LivePregeneration.onServerStopping());
        platform.onServerStopped(server -> GenerationBudget.clear());

        // Adapters for mods known to cache levels; each is inert unless its mod is installed.
        if (platform.isModLoaded(ChunkyCompat.MOD_ID)) {
            ChunkyCompat.register();
        }
    }
}
