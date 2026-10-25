package dev.terrarasa;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.google.gson.JsonParseException;

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

        public Move() {
        }

        public Move(String target, String canonical, String quarantine, String stage) {
            this.target = target;
            this.canonical = canonical;
            this.quarantine = quarantine;
            this.stage = stage;
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
            return Optional.of(journal);
        } catch (JsonParseException e) {
            throw new IOException("Corrupt activation journal " + file, e);
        }
    }

    /** Forward step for one dimension: quarantine the closed canonical data, then install the stage. */
    public static void promote(WorldPaths paths, Move move) throws IOException {
        Path canonical = paths.resolve(move.canonical);
        Path quarantine = paths.resolve(move.quarantine);
        Path stage = paths.resolve(move.stage);
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
        for (int i = this.moves.size() - 1; i >= 0; i--) {
            Move move = this.moves.get(i);
            Path canonical = paths.resolve(move.canonical);
            Path quarantine = paths.resolve(move.quarantine);
            Path stage = paths.resolve(move.stage);
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
        for (Move move : this.moves) {
            if (!Files.isDirectory(paths.resolve(move.canonical))) {
                throw new IOException("Promoted data missing at " + move.canonical);
            }
            if (Files.exists(paths.resolve(move.stage))) {
                throw new IOException("Stage directory still present after promotion: " + move.stage);
            }
        }
    }
}
