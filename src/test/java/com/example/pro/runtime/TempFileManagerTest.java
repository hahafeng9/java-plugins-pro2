package com.example.pro.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Requirements covered: every run gets a unique temp working directory, temp file names are
 * unique, and closing the manager deletes exactly the files it created.
 */
class TempFileManagerTest {

    @TempDir
    Path base;

    @Test
    void runDirectoryIsUniqueAndFollowsSafeNaming() throws IOException {
        Set<String> names = new HashSet<>();
        for (int i = 0; i < 50; i++) {
            try (TempFileManager manager = new TempFileManager(base, Log.console())) {
                Path runDir = manager.runDir();
                assertTrue(runDir.startsWith(base));
                assertTrue(runDir.getFileName().toString().matches("JavaPluginsPro-[a-z0-9]{12}"));
                assertTrue(names.add(runDir.getFileName().toString()), "run dir name collision");
                assertTrue(Files.isDirectory(runDir));
            }
        }
        assertEquals(50, names.size());
    }

    @Test
    void tempFileNamesAreUnique() throws IOException {
        try (TempFileManager manager = new TempFileManager(base, Log.console())) {
            Set<Path> files = new HashSet<>();
            for (int i = 0; i < 500; i++) {
                Path file = manager.newTempFile("prefix-", ".tmp");
                assertTrue(Files.isRegularFile(file));
                assertTrue(file.startsWith(manager.runDir()));
                assertTrue(files.add(file), "temp file name collision: " + file);
            }
            assertEquals(500, manager.managedCount());
        }
    }

    @Test
    void closeDeletesEverythingItCreated() throws IOException {
        Path runDirName;
        try (TempFileManager manager = new TempFileManager(base, Log.console())) {
            runDirName = manager.runDir().getFileName();
            Path file = manager.newTempFile("a", ".txt");
            Files.writeString(file, "data");
            Path dir = manager.newTempDirectory("d");
            Files.writeString(dir.resolve("nested.txt"), "nested");
            manager.register(manager.runDir().resolve("manually-registered.tmp"));
            Files.createFile(manager.runDir().resolve("manually-registered.tmp"));
            assertEquals(3, manager.managedCount());
        }
        assertFalse(Files.exists(base.resolve(runDirName)), "run directory should be gone");
        try (var stream = Files.list(base)) {
            assertTrue(stream.noneMatch(p -> p.getFileName().toString().startsWith("JavaPluginsPro-")),
                    "no plugin temp residue may remain in the base directory");
        }
    }

    @Test
    void closeIsIdempotent() throws IOException {
        TempFileManager manager = new TempFileManager(base, Log.console());
        manager.newTempFile("x", ".bin");
        manager.close();
        manager.close(); // must not throw
        assertEquals(0, manager.managedCount());
    }

    @Test
    void refusesWorkAfterClose() throws IOException {
        TempFileManager manager = new TempFileManager(base, Log.console());
        manager.close();
        assertThrows(IllegalStateException.class, () -> manager.newTempFile("x", ".tmp"));
    }
}
