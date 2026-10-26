package dev.terrarasa;

import java.nio.file.Path;
import java.util.UUID;

import net.minecraft.resources.Identifier;

/** On-disk layout of the library's lifecycle data below the world root. */
public record WorldPaths(Path worldRoot) {
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
        return quarantineRoot().resolve(runLabel).resolve(dimension.getNamespace()).resolve(dimension.getPath());
    }

    /** Resolves the vanilla storage directory using this Minecraft version's dimension layout. */
    public Path dimension(Identifier dimension) {
        return MinecraftCompat.dimensionPath(this.worldRoot, dimension);
    }

    public Path stageDimensionsRoot(UUID runId) {
        return this.worldRoot.resolve("dimensions").resolve(StageKeys.NAMESPACE).resolve("stage").resolve(runId.toString());
    }

    public String relativize(Path path) {
        return this.worldRoot.relativize(path).toString().replace('\\', '/');
    }

    public Path resolve(String relative) {
        return this.worldRoot.resolve(relative);
    }
}
