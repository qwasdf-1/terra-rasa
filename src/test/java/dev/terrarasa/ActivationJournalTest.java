package dev.terrarasa;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import net.minecraft.SharedConstants;
import net.minecraft.resources.Identifier;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ActivationJournalTest {
    private static final List<String> TARGETS = List.of("overworld", "the_nether", "the_end");
    private static final UUID RUN_ID = UUID.fromString("f89d91e4-e6b3-487c-a201-741e8230e16a");

    @TempDir
    Path world;
    private WorldPaths paths;
    private ActivationJournal journal;

    @BeforeAll
    static void bootstrapMinecraftRegistries() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @BeforeEach
    void setUp() throws IOException {
        try (var children = Files.list(this.world)) {
            for (Path child : children.toList()) {
                AtomicFiles.deleteRecursively(child);
            }
        }
        this.paths = new WorldPaths(this.world);
        this.journal = new ActivationJournal();
        this.journal.runId = RUN_ID.toString();
        this.journal.previousRunLabel = "replaced-by-" + RUN_ID;
        this.journal.phase = ActivationJournal.Phase.PROMOTING;
        for (String target : TARGETS) {
            Identifier targetId = Identifier.parse("minecraft:" + target);
            Path canonical = this.paths.dimension(targetId);
            Path stage = this.paths.dimension(StageKeys.stageId(RUN_ID, targetId));
            write(canonical.resolve("data/marker"), "old");
            write(stage.resolve("data/marker"), "new");
            this.journal.moves.add(new ActivationJournal.Move(targetId.toString(),
                    this.paths.relativize(canonical),
                    this.paths.relativize(this.paths.quarantine(this.journal.previousRunLabel, targetId)),
                    this.paths.relativize(stage)));
            ActivationJournal.Move move = this.journal.moves.getLast();
            for (String name : MinecraftCompat.rootDimensionStorageParts(targetId)) {
                move.rootParts.add(new ActivationJournal.RootStoragePart(name,
                        Files.isDirectory(canonical.resolve(name)), Files.isDirectory(stage.resolve(name))));
            }
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
            if (move.rootParts == null || move.rootParts.isEmpty()) {
                assertFalse(Files.exists(this.paths.resolve(move.stage)));
            } else {
                for (ActivationJournal.RootStoragePart part : move.rootParts) {
                    assertFalse(Files.exists(this.paths.resolve(move.stage).resolve(part.name)));
                }
            }
        }
        this.journal.verifyPromoted(this.paths);
    }

    @Test
    void rollbackRestoresEveryCrashPointAndIsIdempotent() throws IOException {
        // Simulate a crash after each possible rename, including each legacy root storage folder.
        for (int crashAfter = 0; ; crashAfter++) {
            setUp();
            List<IoAction> operations = new ArrayList<>();
            for (ActivationJournal.Move move : this.journal.moves) {
                Path canonical = this.paths.resolve(move.canonical);
                Path quarantine = this.paths.resolve(move.quarantine);
                Path stage = this.paths.resolve(move.stage);
                if (canonical.equals(this.paths.worldRoot()) && move.rootParts != null && !move.rootParts.isEmpty()) {
                    for (ActivationJournal.RootStoragePart part : move.rootParts) {
                        Path canonicalPart = canonical.resolve(part.name);
                        Path quarantinePart = quarantine.resolve(part.name);
                        Path stagePart = stage.resolve(part.name);
                        if (part.hadCanonical) {
                            operations.add(() -> AtomicFiles.moveDirectory(canonicalPart, quarantinePart));
                        }
                        if (part.hadStage) {
                            operations.add(() -> AtomicFiles.moveDirectory(stagePart, canonicalPart));
                        }
                    }
                } else {
                    operations.add(() -> AtomicFiles.moveDirectory(canonical, quarantine));
                    operations.add(() -> AtomicFiles.moveDirectory(stage, canonical));
                }
            }
            if (crashAfter > operations.size()) {
                break;
            }
            for (int i = 0; i < crashAfter; i++) {
                operations.get(i).run();
            }
            this.journal.rollBackFiles(this.paths);
            this.journal.rollBackFiles(this.paths);
            for (ActivationJournal.Move move : this.journal.moves) {
                assertEquals("old", read(this.paths.resolve(move.canonical).resolve("data/marker")), "crash point " + crashAfter);
                assertEquals("new", read(this.paths.resolve(move.stage).resolve("data/marker")), "crash point " + crashAfter);
            }
            assertFalse(Files.exists(this.paths.quarantineRoot().resolve(this.journal.previousRunLabel)), "quarantine pruned");
            AtomicFiles.deleteRecursively(this.world.resolve("dimensions"));
            AtomicFiles.deleteRecursively(this.world.resolve("terrarasa"));
        }
    }

    @FunctionalInterface
    private interface IoAction {
        void run() throws IOException;
    }

    @Test
    void ambiguousStateIsRefusedRatherThanGuessed() throws IOException {
        ActivationJournal.Move move = this.journal.moves.getFirst();
        Path canonical = this.paths.resolve(move.canonical);
        Path quarantine = this.paths.resolve(move.quarantine);
        if (canonical.equals(this.paths.worldRoot()) && move.rootParts != null && !move.rootParts.isEmpty()) {
            ActivationJournal.RootStoragePart part = move.rootParts.stream().filter(value -> value.hadCanonical)
                    .findFirst().orElseThrow();
            AtomicFiles.moveDirectory(canonical.resolve(part.name), quarantine.resolve(part.name));
            write(canonical.resolve(part.name).resolve("unknown"), "unknown");
        } else {
            AtomicFiles.moveDirectory(canonical, quarantine);
            write(canonical.resolve("data/marker"), "unknown");
        }
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

    @Test
    void rejectsJournalPathTraversalBeforeRecovery() throws IOException {
        this.journal.moves.getFirst().canonical = "../../outside";
        Files.createDirectories(this.paths.journal().getParent());
        Files.writeString(this.paths.journal(), Json.GSON.toJson(this.journal));

        assertThrows(IOException.class, () -> ActivationJournal.load(this.paths));
        assertEquals("old", read(this.paths.dimension(Identifier.parse("minecraft:overworld")).resolve("data/marker")));
    }

    @Test
    void rejectsQuarantineLabelTraversalBeforeRecovery() throws IOException {
        this.journal.previousRunLabel = "../../outside";
        Files.createDirectories(this.paths.journal().getParent());
        Files.writeString(this.paths.journal(), Json.GSON.toJson(this.journal));

        assertThrows(IOException.class, () -> ActivationJournal.load(this.paths));
        assertEquals("old", read(this.paths.dimension(Identifier.parse("minecraft:overworld")).resolve("data/marker")));
    }

    @Test
    void emptyRelativePathResolvesToLegacyWorldRoot() {
        assertEquals(this.world.toAbsolutePath().normalize(), this.paths.resolve(""));
    }

    @Test
    void atomicWriteCompletesLargePayload() throws IOException {
        String content = "soul-link-recovery-".repeat(100_000);
        Path file = this.world.resolve("large-payload.txt");
        AtomicFiles.writeString(file, content);
        assertEquals(content, read(file));
    }

    private static void write(Path file, String content) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    private static String read(Path file) throws IOException {
        return Files.readString(file);
    }
}
