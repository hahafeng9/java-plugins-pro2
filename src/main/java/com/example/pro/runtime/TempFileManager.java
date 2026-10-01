package com.example.pro.runtime;

import com.example.pro.cleanup.CleanupRegistry;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Creates and tracks every temp file and temp directory the plugin produces.
 *
 * <p>Each instance owns one unique run directory ({@code <base>/JavaPluginsPro-<random>}) created
 * with a {@link SecureRandom}-backed name, so two plugin instances — or two starts of the same
 * plugin — can never collide. Random names are used only to avoid conflicts between runs; they
 * carry no data. All created or registered paths are kept in a thread-safe registry and
 * {@link #close()} deletes exactly those paths (plus the run directory itself) — never anything
 * else on disk.
 */
public final class TempFileManager implements CleanupRegistry, AutoCloseable {

    private static final int NAME_ATTEMPTS = 100;

    private final Path baseDir;
    private final Path runDir;
    private final Log log;
    private final Set<Path> managed = ConcurrentHashMap.newKeySet();
    private volatile boolean closed;

    public TempFileManager(Path baseDir, Log log) throws IOException {
        this.log = log;
        this.baseDir = baseDir.toAbsolutePath().normalize();
        this.runDir = Files.createDirectories(this.baseDir.resolve("JavaPluginsPro-" + RandomToken.generate(12)));
        log.info("[temp] Run directory: " + runDir);
    }

    public Path runDir() {
        return runDir;
    }

    /** Creates a uniquely named, registered temp file inside the run directory. */
    public Path newTempFile(String prefix, String suffix) throws IOException {
        return create(sanitize(prefix), sanitize(suffix), false);
    }

    /** Creates a uniquely named, registered temp directory inside the run directory. */
    public Path newTempDirectory(String prefix) throws IOException {
        return create(sanitize(prefix), "", true);
    }

    private Path create(String prefix, String suffix, boolean directory) throws IOException {
        ensureOpen();
        for (int attempt = 0; attempt < NAME_ATTEMPTS; attempt++) {
            Path candidate = runDir.resolve(prefix + RandomToken.generate(12) + suffix);
            try {
                if (directory) {
                    Files.createDirectory(candidate);
                } else {
                    Files.createFile(candidate);
                }
            } catch (FileAlreadyExistsException e) {
                continue; // SecureRandom collision — draw a new name
            }
            managed.add(candidate);
            return candidate;
        }
        throw new IOException("unable to allocate a unique temp name under " + runDir);
    }

    /** Registers an existing path (e.g. an in-flight download) so it is cleaned up on close. */
    public void register(Path path) {
        ensureOpen();
        managed.add(path.toAbsolutePath().normalize());
    }

    @Override
    public boolean unregister(Path path) {
        return managed.remove(path.toAbsolutePath().normalize());
    }

    @Override
    public Collection<Path> managedPaths() {
        return List.copyOf(managed);
    }

    public int managedCount() {
        return managed.size();
    }

    /** Deletes every registered path (deepest first) and then the run directory. Idempotent. */
    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        List<Path> all = new ArrayList<>(managed);
        all.sort(Comparator.comparingInt(Path::getNameCount).reversed());
        int removed = 0;
        for (Path path : all) {
            try {
                deleteRecursively(path);
                removed++;
            } catch (IOException e) {
                log.warn("[temp] Failed to delete " + path + ": " + e.getMessage());
            }
            managed.remove(path);
        }
        try {
            deleteRecursively(runDir);
        } catch (IOException e) {
            log.warn("[temp] Failed to delete run directory " + runDir + ": " + e.getMessage());
        }
        log.info("[temp] Cleaned up " + removed + " managed path(s) and the run directory");
    }

    private static void deleteRecursively(Path path) throws IOException {
        if (!Files.exists(path)) {
            return;
        }
        if (!Files.isDirectory(path) || Files.isSymbolicLink(path)) {
            Files.deleteIfExists(path);
            return;
        }
        List<Path> children;
        try (var stream = Files.walk(path)) {
            children = stream.sorted(Comparator.reverseOrder()).collect(Collectors.toList());
        }
        IOException failure = null;
        for (Path child : children) {
            try {
                Files.deleteIfExists(child);
            } catch (IOException e) {
                if (failure == null) {
                    failure = new IOException("failed to delete " + child + ": " + e.getMessage(), e);
                }
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("TempFileManager is already closed");
        }
    }

    private static String sanitize(String value) {
        String cleaned = (value == null ? "" : value).replaceAll("[^A-Za-z0-9._-]", "_");
        return cleaned.length() > 32 ? cleaned.substring(0, 32) : cleaned;
    }
}
