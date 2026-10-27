package dev.terrarasa;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import com.google.gson.JsonParseException;
import com.google.gson.reflect.TypeToken;

/**
 * {@code <world>/terrarasa/pregeneration.json}: the live areas still to pregenerate. Written atomically on
 * a writer thread, so the server thread only takes a snapshot; deleted once nothing is left.
 */
final class PregenerationProgress {
    private static final long MAX_FILE_BYTES = 1_048_576L;
    private static final int MAX_TASKS = 256;
    private static final ExecutorService WRITER = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "Terra Rasa pregeneration writer");
        thread.setDaemon(true);
        return thread;
    });

    private final Path file;

    PregenerationProgress(Path file) {
        this.file = file;
    }

    /** The saved areas, or none if there is no file or it cannot be read. */
    List<PregenerationTask> load() {
        List<PregenerationTask> tasks = new ArrayList<>();
        if (!Files.isRegularFile(this.file)) {
            return tasks;
        }
        try {
            if (Files.size(this.file) > MAX_FILE_BYTES) {
                throw new IOException("pregeneration progress file exceeds " + MAX_FILE_BYTES + " bytes");
            }
            List<PregenerationTask> saved = Json.GSON.fromJson(Files.readString(this.file, StandardCharsets.UTF_8),
                    new TypeToken<List<PregenerationTask>>() { }.getType());
            if (saved != null) {
                if (saved.size() > MAX_TASKS) {
                    throw new IOException("pregeneration progress contains more than " + MAX_TASKS + " areas");
                }
                for (int index = 0; index < saved.size(); index++) {
                    PregenerationTask task = saved.get(index);
                    try {
                        if (task == null) {
                            throw new IllegalArgumentException("area is null");
                        }
                        task.validate();
                        tasks.add(task);
                    } catch (IllegalArgumentException e) {
                        TerraRasa.LOGGER.warn("Ignoring invalid pregeneration area {} in {}: {}", index, this.file, e.getMessage());
                    }
                }
            }
        } catch (IOException | JsonParseException e) {
            TerraRasa.LOGGER.warn("Unreadable pregeneration progress {}; starting none", this.file, e);
        }
        return tasks;
    }

    /** Writes {@code snapshot}, or deletes the file when it is empty; {@code wait} blocks until it is on disk. */
    void save(List<PregenerationTask> snapshot, boolean wait) {
        Future<?> write = WRITER.submit(() -> {
            try {
                if (snapshot.isEmpty()) {
                    Files.deleteIfExists(this.file);
                } else {
                    AtomicFiles.writeString(this.file, Json.GSON.toJson(snapshot));
                }
            } catch (IOException e) {
                TerraRasa.LOGGER.warn("Could not save pregeneration progress", e);
            }
        });
        if (!wait) {
            return;
        }
        try {
            write.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException e) {
            TerraRasa.LOGGER.warn("Could not save pregeneration progress", e.getCause());
        }
    }
}
