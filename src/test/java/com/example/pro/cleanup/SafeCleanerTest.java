package com.example.pro.cleanup;

import com.example.pro.runtime.Log;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Requirements covered: cleanup only deletes registered plugin-created files, refuses any
 * {@code ../} escape or path outside the managed roots, supports --dry-run, and reports
 * deletion failures with the concrete reason.
 */
class SafeCleanerTest {

    /** Stub registry whose contents are fully controlled by the test. */
    private static final class StubRegistry implements CleanupRegistry {
        final List<Path> paths = new ArrayList<>();

        @Override
        public List<Path> managedPaths() {
            return List.copyOf(paths);
        }

        @Override
        public boolean unregister(Path path) {
            return paths.remove(path.toAbsolutePath().normalize());
        }
    }

    private static final boolean WINDOWS =
            System.getProperty("os.name", "").toLowerCase().contains("win");

    @TempDir
    Path baseDir;

    private Path managedRoot;
    private Path outsideDir;
    private StubRegistry registry;

    private SafeCleaner newCleaner() throws IOException {
        managedRoot = Files.createDirectories(baseDir.resolve("managed-root"));
        outsideDir = Files.createDirectories(baseDir.resolve("outside"));
        registry = new StubRegistry();
        // Plugin wiring: candidates come from registries only; deletion is restricted to the
        // plugin-managed roots (here: the single managed root).
        return new SafeCleaner(List.of(registry), List.of(managedRoot), Log.console());
    }

    @Test
    void deletesOnlyRegisteredFilesInsideManagedRoots() throws IOException {
        SafeCleaner cleaner = newCleaner();
        Path registered = managedRoot.resolve("temp-" + UUID.randomUUID() + ".txt");
        Files.writeString(registered, "junk");
        registry.paths.add(registered);

        Path unregistered = managedRoot.resolve("not-ours.txt");
        Files.writeString(unregistered, "keep me");

        CleanReport report = cleaner.execute(false);

        assertEquals(List.of(registered.toString()), report.deleted());
        assertFalse(Files.exists(registered), "registered file should be deleted");
        assertTrue(Files.exists(unregistered), "unregistered file must never be touched");
        assertTrue(report.refused().isEmpty());
        assertTrue(report.failed().isEmpty());
    }

    @Test
    void directoriesAreDeletedRecursively() throws IOException {
        SafeCleaner cleaner = newCleaner();
        Path dir = managedRoot.resolve("dir-" + UUID.randomUUID());
        Files.createDirectories(dir.resolve("nested"));
        Files.writeString(dir.resolve("nested").resolve("deep.txt"), "x");
        registry.paths.add(dir);

        CleanReport report = cleaner.execute(false);

        assertEquals(List.of(dir.toString()), report.deleted());
        assertFalse(Files.exists(dir));
    }

    @Test
    void dryRunDeletesNothing() throws IOException {
        SafeCleaner cleaner = newCleaner();
        Path file = managedRoot.resolve("dry-" + UUID.randomUUID() + ".txt");
        Files.writeString(file, "data");
        registry.paths.add(file);

        CleanReport report = cleaner.execute(true);

        assertEquals(List.of(file.toString()), report.planned());
        assertTrue(report.deleted().isEmpty(), "dry-run must not delete");
        assertTrue(Files.exists(file), "dry-run must not delete anything");
        assertFalse(cleaner.candidates().isEmpty(), "candidates stay registered after a dry run");
    }

    @Test
    void refusesDotDotTraversal() throws IOException {
        SafeCleaner cleaner = newCleaner();
        Path victim = outsideDir.resolve("precious.txt");
        Files.writeString(victim, "must survive");
        // A broken/malicious registration pointing outside via ".."
        registry.paths.add(managedRoot.resolve("..").resolve("outside").resolve("precious.txt"));

        CleanReport report = cleaner.execute(false);

        assertEquals(1, report.refused().size());
        assertTrue(report.refused().get(0).contains("traversal"), "reason: " + report.refused().get(0));
        assertTrue(report.deleted().isEmpty());
        assertTrue(Files.exists(victim), "file outside the managed root must survive");
    }

    @Test
    void refusesPathsOutsideAllowedRoots() throws IOException {
        SafeCleaner cleaner = newCleaner();
        Path victim = outsideDir.resolve("absolute-outside.txt");
        Files.writeString(victim, "must survive");
        registry.paths.add(victim); // absolute path outside every allowed root

        CleanReport report = cleaner.execute(false);

        assertEquals(1, report.refused().size());
        assertTrue(report.refused().get(0).contains("outside"), "reason: " + report.refused().get(0));
        assertTrue(Files.exists(victim), "file outside the managed root must survive");
    }

    @Test
    void reportsDeletionFailureWithConcreteReason() throws IOException {
        SafeCleaner cleaner = newCleaner();
        Path undeletable = makeUndeletable();
        registry.paths.add(undeletable);

        CleanReport report = cleaner.execute(false);

        assertFalse(report.failed().isEmpty(), "deletion must fail and be reported");
        assertTrue(report.failed().get(0).contains("—"), "failure must carry a reason: " + report.failed().get(0));
        assertTrue(Files.exists(undeletable));
        releaseUndeletable(undeletable);
    }

    /** Creates a registered path that the OS refuses to delete, portably. */
    private Path makeUndeletable() throws IOException {
        if (WINDOWS) {
            Path locked = managedRoot.resolve("locked-" + UUID.randomUUID() + ".txt");
            Files.writeString(locked, "x");
            assertTrue(locked.toFile().setReadOnly(), "could not mark file read-only");
            return locked;
        }
        Path holder = managedRoot.resolve("holder-" + UUID.randomUUID());
        Files.createDirectories(holder);
        Files.writeString(holder.resolve("child.txt"), "x");
        Files.setPosixFilePermissions(holder, PosixFilePermissions.fromString("r-x------"));
        return holder;
    }

    private void releaseUndeletable(Path path) throws IOException {
        if (WINDOWS) {
            path.toFile().setWritable(true);
        } else {
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rwx------"));
        }
    }
}
