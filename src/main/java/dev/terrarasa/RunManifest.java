package dev.terrarasa;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.google.gson.JsonParseException;

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

    public static RunManifest load(Path file) throws IOException {
        try {
            RunManifest manifest = Json.GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), RunManifest.class);
            if (manifest == null || manifest.runId == null || manifest.formatVersion != FORMAT_VERSION) {
                throw new IOException("Unsupported or empty run manifest " + file);
            }
            return manifest;
        } catch (JsonParseException e) {
            throw new IOException("Corrupt run manifest " + file, e);
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
