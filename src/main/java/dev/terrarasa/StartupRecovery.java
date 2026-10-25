package dev.terrarasa;

import java.io.IOException;
import java.util.Optional;

import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

/**
 * Reconciles an interrupted activation at startup, before {@code createLevels()} opens any dimension
 * storage. The journal also re-asserts the server-global seed, because the seed is persisted
 * lazily with global saved data and may lag the canonical directories after a crash.
 *
 * <p>Any inconsistency that cannot be resolved aborts startup: refusing to start is safe, opening a
 * half-promoted world is not.
 */
public final class StartupRecovery {
    private StartupRecovery() {
    }

    public static void recover(MinecraftServer server) {
        WorldPaths paths = new WorldPaths(server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize());
        Optional<ActivationJournal> loaded;
        try {
            // Before the journal re-asserts a run seed: on first start this is the world's own seed.
            PinnedSeed.load(paths, server.getWorldGenSettings().options().seed());
            loaded = ActivationJournal.load(paths);
        } catch (IOException e) {
            throw new IllegalStateException("Terra Rasa pinned seed or activation journal is unreadable; refusing to open the world", e);
        }
        if (loaded.isEmpty()) {
            return;
        }
        ActivationJournal journal = loaded.get();
        try {
            switch (journal.recoveryDecision()) {
                case ROLL_BACK -> {
                    TerraRasa.LOGGER.warn("Recovering interrupted activation of run {} (phase {}): rolling back", journal.runId, journal.phase);
                    journal.rollBackFiles(paths);
                    journal.phase = ActivationJournal.Phase.ROLLED_BACK;
                    journal.failure = "interrupted; rolled back at startup";
                    journal.save(paths);
                }
                case ROLL_FORWARD -> {
                    TerraRasa.LOGGER.warn("Recovering interrupted activation of run {} (phase {}): rolling forward", journal.runId, journal.phase);
                    journal.verifyPromoted(paths);
                    journal.phase = ActivationJournal.Phase.COMMITTED;
                    journal.failure = "interrupted; committed at startup";
                    journal.save(paths);
                }
                case NOTHING -> {
                }
            }
            long seed = journal.phase == ActivationJournal.Phase.COMMITTED ? journal.newSeed : journal.previousSeed;
            if (server.getWorldGenSettings().options().seed() != seed) {
                TerraRasa.LOGGER.warn("Re-asserting journaled seed {} (saved settings had {})", seed,
                        server.getWorldGenSettings().options().seed());
            }
            CanonicalActivationService.setGlobalSeed(server, seed);
            if (journal.phase == ActivationJournal.Phase.COMMITTED) {
                AtomicFiles.deleteRecursively(paths.quarantineRoot().resolve(journal.previousRunLabel));
            }
        } catch (IOException e) {
            throw new IllegalStateException("Terra Rasa could not recover activation of run " + journal.runId
                    + "; inspect " + paths.journal(), e);
        }
    }
}
