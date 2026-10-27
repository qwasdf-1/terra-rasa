package dev.terrarasa;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.google.gson.JsonParseException;
import net.minecraft.resources.Identifier;

/**
 * Write-ahead record of one canonical activation.
 *
 * <p>The phase decides the recovery <em>direction</em>; the filesystem decides the individual steps.
 * That keeps recovery idempotent even if a crash lands between a directory rename and the journal
 * write that would have recorded it.
 */
public final class ActivationJournal {
    public static final int FORMAT_VERSION = 1;

    public enum Phase {
        /** Old canonical levels are being saved and closed; no files moved yet. */
        RETIRING,
        /** Directory moves in progress. */
        PROMOTING,
        /** Every staged directory is installed at its canonical path. */
        PROMOTED,
        /** New canonical levels exist; validation pending. */
        RECONSTRUCTED,
        COMMITTED,
        ROLLED_BACK
    }

    public enum Decision {
        NOTHING,
        ROLL_BACK,
        ROLL_FORWARD
    }

    public int formatVersion = FORMAT_VERSION;
    public String runId;
    public String previousRunLabel;
    public Phase phase;
    public long previousSeed;
    public long newSeed;
    /** Prepared spawn of the new run, so recovery can adopt a committed run after its manifest is gone. */
    public int spawnX;
    public int spawnY;
    public int spawnZ;
    public List<Move> moves = new ArrayList<>();
    public String failure;

    public static final class Move {
        public String target;
        public String canonical;
        public String quarantine;
        public String stage;
        /** Non-empty only when a legacy dimension shares the world root with global files. */
        public List<RootStoragePart> rootParts = new ArrayList<>();

        public Move() {
        }

        public Move(String target, String canonical, String quarantine, String stage) {
            this.target = target;
            this.canonical = canonical;
            this.quarantine = quarantine;
            this.stage = stage;
        }
    }

    public static final class RootStoragePart {
        public String name;
        public boolean hadCanonical;
        public boolean hadStage;

        public RootStoragePart() {
        }

        public RootStoragePart(String name, boolean hadCanonical, boolean hadStage) {
            this.name = name;
            this.hadCanonical = hadCanonical;
            this.hadStage = hadStage;
        }
    }

    public Decision recoveryDecision() {
        return switch (this.phase) {
            case RETIRING, PROMOTING -> Decision.ROLL_BACK;
            case PROMOTED, RECONSTRUCTED -> Decision.ROLL_FORWARD;
            case COMMITTED, ROLLED_BACK -> Decision.NOTHING;
        };
    }

    public void save(WorldPaths paths) throws IOException {
        validate(paths);
        AtomicFiles.writeString(paths.journal(), Json.GSON.toJson(this));
    }

    public static Optional<ActivationJournal> load(WorldPaths paths) throws IOException {
        Path file = paths.journal();
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        try {
            ActivationJournal journal = Json.GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), ActivationJournal.class);
            if (journal == null || journal.phase == null || journal.formatVersion != FORMAT_VERSION) {
                throw new IOException("Unsupported or empty activation journal " + file);
            }
            journal.validate(paths);
            return Optional.of(journal);
        } catch (JsonParseException e) {
            throw new IOException("Corrupt activation journal " + file, e);
        }
    }

    /** Rejects incomplete, inconsistent, or path-escaping recovery instructions before touching files. */
    private void validate(WorldPaths paths) throws IOException {
        UUID runUuid;
        try {
            runUuid = UUID.fromString(this.runId);
            if (!runUuid.toString().equals(this.runId)) {
                throw new IllegalArgumentException("non-canonical run UUID");
            }
            if (!("replaced-by-" + this.runId).equals(this.previousRunLabel)) {
                throw new IllegalArgumentException("invalid previous run label");
            }
            paths.quarantineRun(this.previousRunLabel);
        } catch (RuntimeException e) {
            throw new IOException("Invalid activation journal run identity", e);
        }
        if (this.moves == null || this.moves.isEmpty()) {
            throw new IOException("Activation journal has no dimension moves");
        }
        Set<String> targets = new HashSet<>();
        for (Move move : this.moves) {
            if (move == null || move.target == null || move.canonical == null || move.quarantine == null || move.stage == null) {
                throw new IOException("Activation journal contains an incomplete dimension move");
            }
            try {
                Identifier target = Identifier.parse(move.target);
                if (!target.toString().equals(move.target)) {
                    throw new IllegalArgumentException("non-canonical dimension target");
                }
                if (!targets.add(target.toString())) {
                    throw new IllegalArgumentException("duplicate dimension target " + target);
                }
                Identifier stageId = StageKeys.stageId(runUuid, target);
                Path canonical = paths.resolve(move.canonical);
                Path quarantine = paths.resolve(move.quarantine);
                Path stage = paths.resolve(move.stage);
                List<String> expectedRootParts = MinecraftCompat.rootDimensionStorageParts(target);
                if (canonical.equals(paths.worldRoot())) {
                    if (!canonical.equals(paths.dimension(target)) || expectedRootParts.isEmpty()) {
                        throw new IllegalArgumentException("world root is not a dimension directory");
                    }
                    List<RootStoragePart> parts = move.rootParts == null ? List.of() : move.rootParts;
                    if (parts.isEmpty()) {
                        // Old 1.21.11 journals never moved data (the root-to-child rename is impossible).
                        if (this.phase != Phase.RETIRING && this.phase != Phase.PROMOTING && this.phase != Phase.ROLLED_BACK) {
                            throw new IllegalArgumentException("legacy journal attempted a whole-root promotion");
                        }
                    } else {
                        if (parts.size() != expectedRootParts.size()) {
                            throw new IllegalArgumentException("incomplete root storage move list");
                        }
                        for (int i = 0; i < parts.size(); i++) {
                            RootStoragePart part = parts.get(i);
                            if (part == null || !expectedRootParts.get(i).equals(part.name)) {
                                throw new IllegalArgumentException("invalid root storage part");
                            }
                        }
                    }
                } else if (!canonical.equals(paths.dimension(target))
                        || (move.rootParts != null && !move.rootParts.isEmpty())) {
                    throw new IllegalArgumentException("dimension path does not match the target");
                }
                if (!quarantine.equals(paths.quarantine(this.previousRunLabel, target))
                        || !stage.equals(paths.dimension(stageId))) {
                    throw new IllegalArgumentException("dimension move paths do not match the recorded run and target");
                }
            } catch (RuntimeException e) {
                throw new IOException("Invalid activation journal move for target " + move.target, e);
            }
        }
    }

    /** Forward step for one dimension: quarantine the closed canonical data, then install the stage. */
    public static void promote(WorldPaths paths, Move move) throws IOException {
        Path canonical = paths.resolve(move.canonical);
        Path quarantine = paths.resolve(move.quarantine);
        Path stage = paths.resolve(move.stage);
        if (canonical.equals(paths.worldRoot())) {
            if (move.rootParts == null || move.rootParts.isEmpty()) {
                throw new IOException("Refusing to move the whole legacy world root during activation");
            }
            for (RootStoragePart part : move.rootParts) {
                Path canonicalPart = canonical.resolve(part.name);
                Path quarantinePart = quarantine.resolve(part.name);
                Path stagePart = stage.resolve(part.name);
                requireDirectoryState(canonicalPart, part.hadCanonical, "old canonical " + part.name);
                requireDirectoryState(stagePart, part.hadStage, "staged " + part.name);
                if (part.hadCanonical) {
                    AtomicFiles.moveDirectory(canonicalPart, quarantinePart);
                }
                if (part.hadStage) {
                    AtomicFiles.moveDirectory(stagePart, canonicalPart);
                }
            }
            return;
        }
        if (!Files.isDirectory(stage)) {
            throw new IOException("Staged data missing for " + move.target + ": " + stage);
        }
        if (Files.exists(canonical)) {
            AtomicFiles.moveDirectory(canonical, quarantine);
        }
        AtomicFiles.moveDirectory(stage, canonical);
    }

    /**
     * Restores every canonical directory to the previous run's data. Safe to repeat: each step checks
     * the filesystem first, and a step that already happened is skipped.
     */
    public void rollBackFiles(WorldPaths paths) throws IOException {
        validate(paths);
        for (int i = this.moves.size() - 1; i >= 0; i--) {
            Move move = this.moves.get(i);
            Path canonical = paths.resolve(move.canonical);
            Path quarantine = paths.resolve(move.quarantine);
            Path stage = paths.resolve(move.stage);
            if (canonical.equals(paths.worldRoot()) && move.rootParts != null && !move.rootParts.isEmpty()) {
                rollBackRootParts(canonical, quarantine, stage, paths.quarantineRoot(), move.rootParts);
                continue;
            }
            if (!Files.exists(quarantine)) {
                // Never quarantined: the canonical directory still holds the previous run.
                continue;
            }
            if (Files.exists(canonical)) {
                if (Files.exists(stage)) {
                    throw new IOException("Ambiguous rollback state for " + move.target
                            + ": canonical, quarantine and stage directories all exist");
                }
                AtomicFiles.moveDirectory(canonical, stage);
            }
            AtomicFiles.moveDirectory(quarantine, canonical);
            AtomicFiles.pruneEmptyParents(quarantine.getParent(), paths.quarantineRoot());
        }
    }

    /** Roll-forward precondition: every canonical directory holds promoted data and no stage remains. */
    public void verifyPromoted(WorldPaths paths) throws IOException {
        validate(paths);
        for (Move move : this.moves) {
            Path canonical = paths.resolve(move.canonical);
            Path stage = paths.resolve(move.stage);
            if (canonical.equals(paths.worldRoot())) {
                if (move.rootParts == null || move.rootParts.isEmpty()) {
                    throw new IOException("Legacy activation journal has no root storage parts");
                }
                for (RootStoragePart part : move.rootParts) {
                    Path canonicalPart = canonical.resolve(part.name);
                    Path stagePart = stage.resolve(part.name);
                    if ((part.hadStage && !Files.isDirectory(canonicalPart, LinkOption.NOFOLLOW_LINKS))
                            || Files.exists(stagePart, LinkOption.NOFOLLOW_LINKS)) {
                        throw new IOException("Legacy promoted data missing or still staged for " + move.target + "/" + part.name);
                    }
                    if (!part.hadStage && (this.phase == Phase.PROMOTED || this.phase == Phase.PROMOTING)
                            && Files.exists(canonicalPart, LinkOption.NOFOLLOW_LINKS)) {
                        throw new IOException("Unexpected promoted data at " + move.target + "/" + part.name);
                    }
                }
                continue;
            }
            if (!Files.isDirectory(paths.resolve(move.canonical))) {
                throw new IOException("Promoted data missing at " + move.canonical);
            }
            if (Files.exists(paths.resolve(move.stage))) {
                throw new IOException("Stage directory still present after promotion: " + move.stage);
            }
        }
    }

    private static void requireDirectoryState(Path path, boolean expected, String label) throws IOException {
        boolean exists = Files.exists(path, LinkOption.NOFOLLOW_LINKS);
        if (exists != expected || (exists && !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS))) {
            throw new IOException("Unexpected directory state for " + label + " at " + path);
        }
    }

    private static void rollBackRootParts(Path canonical, Path quarantine, Path stage, Path quarantineRoot,
            List<RootStoragePart> parts) throws IOException {
        for (int i = parts.size() - 1; i >= 0; i--) {
            RootStoragePart part = parts.get(i);
            Path canonicalPart = canonical.resolve(part.name);
            Path quarantinePart = quarantine.resolve(part.name);
            Path stagePart = stage.resolve(part.name);
            boolean canonicalExists = Files.exists(canonicalPart, LinkOption.NOFOLLOW_LINKS);
            boolean quarantineExists = Files.exists(quarantinePart, LinkOption.NOFOLLOW_LINKS);
            boolean stageExists = Files.exists(stagePart, LinkOption.NOFOLLOW_LINKS);
            if (canonicalExists && !Files.isDirectory(canonicalPart, LinkOption.NOFOLLOW_LINKS)
                    || quarantineExists && !Files.isDirectory(quarantinePart, LinkOption.NOFOLLOW_LINKS)
                    || stageExists && !Files.isDirectory(stagePart, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("Non-directory found during rollback of legacy storage part " + part.name);
            }
            if (part.hadCanonical) {
                if (quarantineExists) {
                    if (canonicalExists) {
                        if (part.hadStage) {
                            if (stageExists) {
                                throw new IOException("Ambiguous rollback state for legacy storage part " + part.name);
                            }
                            AtomicFiles.moveDirectory(canonicalPart, stagePart);
                        } else {
                                if (stageExists) {
                                    throw new IOException("Unexpected staged data during rollback of legacy storage part " + part.name);
                                }
                            AtomicFiles.deleteRecursively(canonicalPart);
                        }
                    }
                    AtomicFiles.moveDirectory(quarantinePart, canonicalPart);
                    AtomicFiles.pruneEmptyParents(quarantinePart.getParent(), quarantineRoot);
                } else if (!canonicalExists) {
                    throw new IOException("Previous legacy storage part is missing: " + part.name);
                }
            } else {
                if (quarantineExists) {
                    throw new IOException("Unexpected quarantine data for new legacy storage part " + part.name);
                }
                if (canonicalExists) {
                    if (part.hadStage) {
                        if (stageExists) {
                            throw new IOException("Ambiguous rollback state for new legacy storage part " + part.name);
                        }
                        AtomicFiles.moveDirectory(canonicalPart, stagePart);
                    } else {
                        AtomicFiles.deleteRecursively(canonicalPart);
                    }
                }
            }
        }
    }
}
