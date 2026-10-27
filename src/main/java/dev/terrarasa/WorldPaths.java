package dev.terrarasa;

import java.nio.file.Path;
import java.util.Objects;
import java.util.UUID;

import net.minecraft.resources.Identifier;

/** On-disk layout of the library's lifecycle data below the world root. */
public record WorldPaths(Path worldRoot) {
    public WorldPaths {
        worldRoot = Objects.requireNonNull(worldRoot, "worldRoot").toAbsolutePath().normalize();
    }

    public Path root() {
        return this.worldRoot.resolve(TerraRasa.MOD_ID);
    }

    public Path journal() {
        return root().resolve("active-journal.json");
    }

    public Path pinnedSeed() {
        return root().resolve("pinned-seed.json");
    }

    /** Pregeneration still to do in the live worlds. */
    public Path pregeneration() {
        return root().resolve("pregeneration.json");
    }

    public Path stageManifests() {
        return root().resolve("stages");
    }

    public Path manifest(UUID runId) {
        return stageManifests().resolve(runId.toString()).resolve("manifest.json");
    }

    public Path quarantineRoot() {
        return root().resolve("quarantine");
    }

    public Path quarantine(String runLabel, Identifier dimension) {
        return quarantineRun(runLabel).resolve(dimension.getNamespace()).resolve(dimension.getPath());
    }

    /** Returns the quarantine directory for a journal's generated previous-run label. */
    public Path quarantineRun(String runLabel) {
        String prefix = "replaced-by-";
        if (runLabel == null || !runLabel.startsWith(prefix)) {
            throw new IllegalArgumentException("Invalid quarantine run label");
        }
        String runId = runLabel.substring(prefix.length());
        try {
            UUID parsed = UUID.fromString(runId);
            if (!parsed.toString().equals(runId)) {
                throw new IllegalArgumentException("Invalid quarantine run label");
            }
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid quarantine run label", e);
        }
        return quarantineRoot().resolve(runLabel);
    }

    /** Resolves the vanilla storage directory using this Minecraft version's dimension layout. */
    public Path dimension(Identifier dimension) {
        return MinecraftCompat.dimensionPath(this.worldRoot, dimension);
    }

    public Path stageDimensionsRoot(UUID runId) {
        return this.worldRoot.resolve("dimensions").resolve(StageKeys.NAMESPACE).resolve("stage").resolve(runId.toString());
    }

    public String relativize(Path path) {
        return this.worldRoot.relativize(path.toAbsolutePath().normalize()).toString().replace('\\', '/');
    }

    public Path resolve(String relative) {
        if (relative == null || relative.indexOf('\\') >= 0) {
            throw new IllegalArgumentException("Invalid world-relative path");
        }
        // The legacy 1.21.11 Overworld is stored directly at worldRoot, whose relative path is empty.
        if (relative.isEmpty()) {
            return this.worldRoot;
        }
        if (relative.isBlank()) {
            throw new IllegalArgumentException("Invalid world-relative path");
        }
        Path relativePath = Path.of(relative);
        if (relativePath.isAbsolute()) {
            throw new IllegalArgumentException("World-relative path must not be absolute");
        }
        Path resolved = this.worldRoot.resolve(relativePath).normalize();
        if (!resolved.startsWith(this.worldRoot)) {
            throw new IllegalArgumentException("World-relative path escapes the world root");
        }
        return resolved;
    }
}
