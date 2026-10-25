package dev.terrarasa;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ActivationJournalTest {
    private static final List<String> TARGETS = List.of("overworld", "the_nether", "the_end");

    @TempDir
    Path world;
    private WorldPaths paths;
    private ActivationJournal journal;

    @BeforeEach
    void setUp() throws IOException {
        this.paths = new WorldPaths(this.world);
        this.journal = new ActivationJournal();
        this.journal.runId = "run";
        this.journal.previousRunLabel = "replaced-by-run";
        this.journal.phase = ActivationJournal.Phase.PROMOTING;
        for (String target : TARGETS) {
            Path canonical = this.world.resolve("dimensions/minecraft/" + target);
            Path stage = this.world.resolve("dimensions/terrarasa/stage/run/minecraft/" + target);
            write(canonical.resolve("data/marker"), "old");
            write(stage.resolve("data/marker"), "new");
            this.journal.moves.add(new ActivationJournal.Move("minecraft:" + target,
                    this.paths.relativize(canonical),
                    this.paths.relativize(this.world.resolve("terrarasa/quarantine/replaced-by-run/minecraft/" + target)),
                    this.paths.relativize(stage)));
        }
    }

    @Test
    void promotionInstallsStagedDataAndQuarantinesOldData() throws IOException {
        for (ActivationJournal.Move move : this.journal.moves) {
            ActivationJournal.promote(this.paths, move);
        }
        for (ActivationJournal.Move move : this.journal.moves) {
            assertEquals("new", read(this.paths.resolve(move.canonical).resolve("data/marker")));
            assertEquals("old", read(this.paths.resolve(move.quarantine).resolve("data/marker")));
            assertFalse(Files.exists(this.paths.resolve(move.stage)));
        }
        this.journal.verifyPromoted(this.paths);
    }

    @Test
    void rollbackRestoresEveryCrashPointAndIsIdempotent() throws IOException {
        // Simulate a crash after each possible rename: quarantine of move i, then its stage install.
        for (int crashAfter = 0; crashAfter <= TARGETS.size() * 2; crashAfter++) {
            setUp();
            int renames = 0;
            outer:
            for (ActivationJournal.Move move : this.journal.moves) {
                if (renames++ == crashAfter) {
                    break;
                }
                AtomicFiles.moveDirectory(this.paths.resolve(move.canonical), this.paths.resolve(move.quarantine));
                if (renames++ == crashAfter) {
                    break outer;
                }
                AtomicFiles.moveDirectory(this.paths.resolve(move.stage), this.paths.resolve(move.canonical));
            }
            this.journal.rollBackFiles(this.paths);
            this.journal.rollBackFiles(this.paths);
            for (ActivationJournal.Move move : this.journal.moves) {
                assertEquals("old", read(this.paths.resolve(move.canonical).resolve("data/marker")), "crash point " + crashAfter);
                assertEquals("new", read(this.paths.resolve(move.stage).resolve("data/marker")), "crash point " + crashAfter);
            }
            assertFalse(Files.exists(this.paths.quarantineRoot().resolve("replaced-by-run")), "quarantine pruned");
            AtomicFiles.deleteRecursively(this.world.resolve("dimensions"));
            AtomicFiles.deleteRecursively(this.world.resolve("terrarasa"));
        }
    }

    @Test
    void ambiguousStateIsRefusedRatherThanGuessed() throws IOException {
        ActivationJournal.Move move = this.journal.moves.getFirst();
        AtomicFiles.moveDirectory(this.paths.resolve(move.canonical), this.paths.resolve(move.quarantine));
        write(this.paths.resolve(move.canonical).resolve("data/marker"), "unknown");
        assertThrows(IOException.class, () -> this.journal.rollBackFiles(this.paths));
    }

    @Test
    void phaseDecidesRecoveryDirection() {
        ActivationJournal.Phase[] rollBack = {ActivationJournal.Phase.RETIRING, ActivationJournal.Phase.PROMOTING};
        ActivationJournal.Phase[] rollForward = {ActivationJournal.Phase.PROMOTED, ActivationJournal.Phase.RECONSTRUCTED};
        for (ActivationJournal.Phase phase : rollBack) {
            this.journal.phase = phase;
            assertEquals(ActivationJournal.Decision.ROLL_BACK, this.journal.recoveryDecision());
        }
        for (ActivationJournal.Phase phase : rollForward) {
            this.journal.phase = phase;
            assertEquals(ActivationJournal.Decision.ROLL_FORWARD, this.journal.recoveryDecision());
        }
        this.journal.phase = ActivationJournal.Phase.COMMITTED;
        assertEquals(ActivationJournal.Decision.NOTHING, this.journal.recoveryDecision());
    }

    @Test
    void journalRoundTripsDurably() throws IOException {
        this.journal.newSeed = 42L;
        this.journal.save(this.paths);
        ActivationJournal loaded = ActivationJournal.load(this.paths).orElseThrow();
        assertEquals(42L, loaded.newSeed);
        assertEquals(3, loaded.moves.size());
        assertTrue(Files.exists(this.paths.journal()));
        assertFalse(Files.exists(this.paths.journal().resolveSibling("active-journal.json.tmp")));
    }

    private static void write(Path file, String content) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    private static String read(Path file) throws IOException {
        return Files.readString(file);
    }
}
