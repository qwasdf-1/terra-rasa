package dev.terrarasa;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.google.gson.JsonParseException;
import net.minecraft.resources.Identifier;

/**
 * Durable descriptor of one prepared run. A run is adoptable only once its manifest
 * says {@link PreparationState#CLOSED_READY}, i.e. every staged level was saved and fully closed.
 */
public final class RunManifest {
    public static final int FORMAT_VERSION = 1;

    public int formatVersion = FORMAT_VERSION;
    public String runId;
    public long seed;
    public String minecraftVersion;
    public String fingerprint;
    /** Stable FIFO ordering for a persisted ready-run pool. */
    public long createdAtMillis;
    public PreparationState state = PreparationState.CREATING;
    public List<Dimension> dimensions = new ArrayList<>();
    public Spawn spawn;
    public int preloadRadius;
    /** Areas to pregenerate and their progress; what is left when the run starts continues live. */
    public List<PregenerationTask> pregeneration = new ArrayList<>();
    public String failure;
    /** Phase durations in milliseconds, for latency tracking. */
    public Map<String, Long> timingsMillis = new LinkedHashMap<>();

    public static RunManifest create(UUID runId, long seed, String minecraftVersion, String fingerprint) {
        RunManifest manifest = new RunManifest();
        manifest.runId = runId.toString();
        manifest.seed = seed;
        manifest.minecraftVersion = minecraftVersion;
        manifest.fingerprint = fingerprint;
        manifest.createdAtMillis = System.currentTimeMillis();
        return manifest;
    }

    public UUID runUuid() {
        return UUID.fromString(this.runId);
    }

    public void save(Path file) throws IOException {
        AtomicFiles.writeString(file, toJson());
    }

    public String toJson() {
        return Json.GSON.toJson(this);
    }

    public static RunManifest load(Path file, UUID expectedRunId) throws IOException {
        try {
            RunManifest manifest = Json.GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), RunManifest.class);
            if (manifest == null || manifest.runId == null || manifest.formatVersion != FORMAT_VERSION) {
                throw new IOException("Unsupported or empty run manifest " + file);
            }
            manifest.validate(file, expectedRunId);
            return manifest;
        } catch (JsonParseException | IllegalArgumentException | NullPointerException e) {
            throw new IOException("Corrupt run manifest " + file, e);
        }
    }

    private void validate(Path file, UUID expectedRunId) throws IOException {
        UUID parsedRunId;
        try {
            parsedRunId = UUID.fromString(this.runId);
        } catch (IllegalArgumentException e) {
            throw new IOException("Invalid run id in manifest " + file, e);
        }
        if (!parsedRunId.toString().equals(this.runId) || !parsedRunId.equals(expectedRunId)) {
            throw new IOException("Run id does not match manifest location " + file);
        }
        if (this.minecraftVersion == null || this.minecraftVersion.isBlank()
                || this.fingerprint == null || this.fingerprint.isBlank() || this.state == null) {
            throw new IOException("Missing required run metadata in manifest " + file);
        }
        if (this.preloadRadius < 0 || this.preloadRadius > 32) {
            throw new IOException("Invalid preload radius in manifest " + file);
        }
        if (this.dimensions == null || this.dimensions.isEmpty()) {
            throw new IOException("No dimensions in run manifest " + file);
        }
        Set<String> targets = new HashSet<>();
        Set<String> stages = new HashSet<>();
        for (Dimension dimension : this.dimensions) {
            if (dimension == null || dimension.target == null || dimension.stage == null) {
                throw new IOException("Incomplete dimension entry in manifest " + file);
            }
            Identifier target = parseIdentifier(dimension.target, file);
            Identifier stage = parseIdentifier(dimension.stage, file);
            if (!target.toString().equals(dimension.target) || !stage.toString().equals(dimension.stage)
                    || !stage.equals(StageKeys.stageId(parsedRunId, target))) {
                throw new IOException("Invalid staged dimension mapping in manifest " + file);
            }
            if (!targets.add(dimension.target) || !stages.add(dimension.stage)) {
                throw new IOException("Duplicate dimension entry in manifest " + file);
            }
        }
        if (this.state == PreparationState.CLOSED_READY && this.spawn == null) {
            throw new IOException("Ready run has no spawn in manifest " + file);
        }

        // These fields were added with format 1 and can be absent in early format-1 manifests.
        if (this.pregeneration == null) {
            this.pregeneration = new ArrayList<>();
        }
        if (this.timingsMillis == null) {
            this.timingsMillis = new LinkedHashMap<>();
        }
        for (PregenerationTask task : this.pregeneration) {
            if (task == null) {
                throw new IOException("Incomplete pregeneration entry in manifest " + file);
            }
            try {
                task.validate();
            } catch (IllegalArgumentException e) {
                throw new IOException("Invalid pregeneration entry in manifest " + file, e);
            }
            if (!targets.contains(task.dimension)) {
                throw new IOException("Invalid pregeneration entry in manifest " + file);
            }
        }
        for (Map.Entry<String, Long> timing : this.timingsMillis.entrySet()) {
            if (timing.getKey() == null || timing.getKey().isBlank() || timing.getValue() == null || timing.getValue() < 0) {
                throw new IOException("Invalid timing entry in manifest " + file);
            }
        }
    }

    private static Identifier parseIdentifier(String value, Path file) throws IOException {
        try {
            return Identifier.parse(value);
        } catch (IllegalArgumentException e) {
            throw new IOException("Invalid dimension identifier in manifest " + file, e);
        }
    }

    public static final class Dimension {
        public String target;
        public String stage;

        public Dimension() {
        }

        public Dimension(String target, String stage) {
            this.target = target;
            this.stage = stage;
        }
    }

    public static final class Spawn {
        public int x;
        public int y;
        public int z;

        public Spawn() {
        }

        public Spawn(int x, int y, int z) {
            this.x = x;
            this.y = y;
            this.z = z;
        }
    }
}
