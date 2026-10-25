package dev.terrarasa;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Terra Rasa: replaces the canonical run dimensions of a running dedicated server with freshly
 * seeded, pre-generated ones, without restarting the server or reconnecting players.
 *
 * <p>The library knows nothing about the game played in those worlds. A host mod drives it:
 * {@link WorldPreparationService} prepares the next worlds in the background,
 * {@link CanonicalActivationService} swaps them in once the host has moved every player out of the
 * resettable dimensions, and {@link dev.terrarasa.session.SessionReset} returns server-owned and
 * per-player state to the baseline a new world would have. Interrupted swaps are reconciled from the
 * journal at the next startup ({@link StartupRecovery}). Mods that cache levels follow swaps through
 * {@link CanonicalLevelEvents}. The Fabric entrypoint is {@link TerraRasaInitializer}.
 */
public final class TerraRasa {
    public static final String MOD_ID = "terrarasa";
    public static final Logger LOGGER = LoggerFactory.getLogger("Terra Rasa");

    private TerraRasa() {
    }
}
