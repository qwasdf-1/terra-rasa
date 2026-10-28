package dev.terrarasa;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PregenerationProgressTest {
    @TempDir
    Path temp;

    @Test
    void invalidPersistedAreasAreIgnoredBeforeTheyCanReachChunkGeneration() throws Exception {
        Path file = this.temp.resolve("pregeneration.json");
        List<PregenerationTask> saved = List.of(
                new PregenerationTask("minecraft:overworld", 0, 0, 1, 0),
                new PregenerationTask("minecraft:overworld", 0, 0, Integer.MAX_VALUE, 0),
                new PregenerationTask("terrarasa:stage/not-a-run", 0, 0, 1, 0));
        Files.writeString(file, Json.GSON.toJson(saved));

        List<PregenerationTask> restored = new PregenerationProgress(file).load();

        assertEquals(1, restored.size());
        assertEquals("minecraft:overworld", restored.getFirst().dimension);
    }
}
