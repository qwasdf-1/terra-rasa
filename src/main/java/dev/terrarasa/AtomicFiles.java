package dev.terrarasa;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;

/** Crash-safe file primitives used by manifests, the activation journal and directory promotion. */
public final class AtomicFiles {
    private AtomicFiles() {
    }

    /** Writes via a synced temporary file and an atomic rename, so readers see old or new content only. */
    public static void writeString(Path target, String content) throws IOException {
        Files.createDirectories(target.getParent());
        Path temporary = target.resolveSibling(target.getFileName() + ".tmp");
        try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
            channel.write(java.nio.ByteBuffer.wrap(content.getBytes(StandardCharsets.UTF_8)));
            channel.force(true);
        }
        Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        syncDirectory(target.getParent());
    }

    /**
     * Renames a directory atomically. Promotion never falls back to copying: a non-atomic copy could leave
     * both a partial source and a partial target after a crash, which the journal cannot disambiguate.
     */
    public static void moveDirectory(Path source, Path target) throws IOException {
        if (Files.exists(target)) {
            throw new IOException("Refusing to overwrite existing directory " + target);
        }
        Files.createDirectories(target.getParent());
        Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        syncDirectory(source.getParent());
        syncDirectory(target.getParent());
    }

    public static void deleteRecursively(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                if (exc != null) {
                    throw exc;
                }
                Files.delete(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    /** Removes now-empty parent directories up to, but excluding, {@code stop}. */
    public static void pruneEmptyParents(Path start, Path stop) {
        Path current = start;
        while (current != null && !current.equals(stop) && current.startsWith(stop)) {
            try (var entries = Files.list(current)) {
                if (entries.findAny().isPresent()) {
                    return;
                }
                Files.delete(current);
            } catch (IOException e) {
                return;
            }
            current = current.getParent();
        }
    }

    private static void syncDirectory(Path directory) {
        // Directory fsync makes renames durable on POSIX; unsupported platforms simply skip it.
        try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) {
            channel.force(true);
        } catch (IOException | UnsupportedOperationException ignored) {
        }
    }
}
