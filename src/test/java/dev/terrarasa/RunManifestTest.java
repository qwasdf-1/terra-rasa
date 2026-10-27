package dev.terrarasa;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RunManifestTest {
    private static final UUID RUN_ID = UUID.fromString("de01a8ed-a44b-4b73-a67e-1fc64ac2df19");

    @TempDir
    Path temp;

    @Test
    void validReadyManifestLoads() throws IOException {
        Path file = writeManifest(validManifest());

        assertDoesNotThrow(() -> RunManifest.load(file, RUN_ID));
    }

    @Test
    void manifestMustMatchItsDirectoryRunId() throws IOException {
        Path file = writeManifest(validManifest());

        assertThrows(IOException.class, () -> RunManifest.load(file, UUID.randomUUID()));
    }

    @Test
    void stagedDimensionMustBelongToItsRunAndTarget() throws IOException {
        RunManifest manifest = validManifest();
        manifest.dimensions.getFirst().stage = "terrarasa:stage/../outside";
        Path file = writeManifest(manifest);

        assertThrows(IOException.class, () -> RunManifest.load(file, RUN_ID));
    }

    @Test
    void invalidPregenerationProgressIsRejectedBeforeResume() throws IOException {
        RunManifest manifest = validManifest();
        manifest.pregeneration.getFirst().radiusChunks = Integer.MAX_VALUE;
        Path file = writeManifest(manifest);

        assertThrows(IOException.class, () -> RunManifest.load(file, RUN_ID));
    }

    private Path writeManifest(RunManifest manifest) throws IOException {
        Path file = this.temp.resolve("run.json");
        Files.writeString(file, manifest.toJson());
        return file;
    }

    private static RunManifest validManifest() {
        RunManifest manifest = RunManifest.create(RUN_ID, 7L, "26.2", "fingerprint");
        Identifier overworld = Identifier.withDefaultNamespace("overworld");
        manifest.dimensions.add(new RunManifest.Dimension(overworld.toString(), StageKeys.stageId(RUN_ID, overworld).toString()));
        manifest.state = PreparationState.CLOSED_READY;
        manifest.spawn = new RunManifest.Spawn(0, 80, 0);
        manifest.pregeneration.add(new PregenerationTask(overworld.toString(), 0, 0, 1, 0));
        return manifest;
    }
}
