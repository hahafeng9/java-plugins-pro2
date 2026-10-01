package com.example.pro.lifecycle;

import com.example.pro.runtime.Log;
import com.example.pro.runtime.TempFileManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Requirements covered: server.jar replacement success, integrity verification, backup creation
 * and automatic rollback when verification or installation fails.
 */
class ServerJarManagerTest {

    @TempDir
    Path dir;

    // ---- helpers -------------------------------------------------------------

    /** Builds a minimal but structurally valid jar (zip magic + manifest entry). */
    private static Path createJar(Path parent, String name, String payload) throws IOException {
        Path jar = parent.resolve(name);
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(jar))) {
            zos.putNextEntry(new ZipEntry("META-INF/MANIFEST.MF"));
            zos.write("Manifest-Version: 1.0\r\n\r\n".getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
            zos.putNextEntry(new ZipEntry("app/Main.class"));
            zos.write(payload.getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        }
        return jar;
    }

    private static byte[] bytes(Path file) throws IOException {
        return Files.readAllBytes(file);
    }

    private TempFileManager newTempFiles() throws IOException {
        return new TempFileManager(dir.resolve("temp-base"), Log.console());
    }

    // ---- tests ---------------------------------------------------------------

    @Test
    void firstInstallWithoutPreviousJarSucceeds() throws IOException {
        TempFileManager tempFiles = newTempFiles();
        try {
            Path target = dir.resolve("server.jar");
            Path sourceJar = createJar(dir, "fresh.jar", "fresh-payload");
            ServerJarManager manager = new ServerJarManager(target,
                    new ServerJarManager.FileJarSource(sourceJar),
                    ServerJarManager.defaultVerifier(""), tempFiles, Log.console());

            assertEquals(ServerJarManager.Result.UPDATED, manager.ensureServerJar());
            assertTrue(Files.exists(target));
            assertArrayEquals(bytes(sourceJar), bytes(target), "installed jar must be a byte-identical copy");
            assertTrue(manager.managedPaths().isEmpty(), "no backup expected on first install");
        } finally {
            tempFiles.close();
        }
    }

    @Test
    void replaceExistingJarCreatesBackupAndInstallsNewJar() throws IOException {
        TempFileManager tempFiles = newTempFiles();
        try {
            Path target = createJar(dir, "server.jar", "old-payload");
            byte[] originalBytes = bytes(target);
            Path sourceJar = createJar(dir, "new.jar", "new-payload");
            ServerJarManager manager = new ServerJarManager(target,
                    new ServerJarManager.FileJarSource(sourceJar),
                    ServerJarManager.defaultVerifier(""), tempFiles, Log.console());

            assertEquals(ServerJarManager.Result.UPDATED, manager.ensureServerJar());

            // New jar installed...
            assertArrayEquals(bytes(sourceJar), bytes(target));
            // ...old jar preserved as a backup next to it (byte-identical to the original)...
            assertEquals(1, manager.managedPaths().size());
            Path backup = manager.managedPaths().iterator().next();
            assertTrue(backup.getFileName().toString().startsWith("server.jar.bak-"));
            assertArrayEquals(originalBytes, bytes(backup));
            // ...and the download temp file is gone from the registry.
            assertFalse(tempFiles.managedPaths().stream()
                    .anyMatch(p -> p.getFileName().toString().endsWith(".part")));
        } finally {
            tempFiles.close();
        }
    }

    @Test
    void corruptSourceNeverTouchesTheOriginalJar() throws IOException {
        TempFileManager tempFiles = newTempFiles();
        try {
            Path target = createJar(dir, "server.jar", "original-payload");
            byte[] original = bytes(target);
            Path corrupt = dir.resolve("corrupt.jar");
            Files.writeString(corrupt, "this is definitely not a zip file");

            ServerJarManager manager = new ServerJarManager(target,
                    new ServerJarManager.FileJarSource(corrupt),
                    ServerJarManager.defaultVerifier(""), tempFiles, Log.console());

            assertEquals(ServerJarManager.Result.KEPT_EXISTING, manager.ensureServerJar());
            assertArrayEquals(original, bytes(target), "original jar must stay untouched");
            assertTrue(manager.managedPaths().isEmpty(), "no backup may be created for a bad jar");
        } finally {
            tempFiles.close();
        }
    }

    @Test
    void checksumMismatchAbortsTheReplacement() throws IOException {
        TempFileManager tempFiles = newTempFiles();
        try {
            Path target = createJar(dir, "server.jar", "original-payload");
            byte[] original = bytes(target);
            Path sourceJar = createJar(dir, "new.jar", "new-payload");

            ServerJarManager manager = new ServerJarManager(target,
                    new ServerJarManager.FileJarSource(sourceJar),
                    ServerJarManager.defaultVerifier("deadbeef".repeat(8)), tempFiles, Log.console());

            assertEquals(ServerJarManager.Result.KEPT_EXISTING, manager.ensureServerJar());
            assertArrayEquals(original, bytes(target));
        } finally {
            tempFiles.close();
        }
    }

    @Test
    void rollbackRestoresOriginalWhenPostInstallVerificationFails() throws IOException {
        TempFileManager tempFiles = newTempFiles();
        try {
            Path target = createJar(dir, "server.jar", "original-payload");
            byte[] original = bytes(target);
            Path sourceJar = createJar(dir, "new.jar", "new-payload");

            // Verifier that accepts the download but rejects anything already installed in place.
            ServerJarManager.Verifier failInPlace = jar -> {
                if (jar.equals(target.toAbsolutePath().normalize())) {
                    throw new IOException("simulated post-install failure");
                }
            };

            ServerJarManager manager = new ServerJarManager(target,
                    new ServerJarManager.FileJarSource(sourceJar),
                    failInPlace, tempFiles, Log.console());

            assertEquals(ServerJarManager.Result.KEPT_EXISTING, manager.ensureServerJar());
            assertArrayEquals(original, bytes(target), "original jar must be restored after failed replacement");
            assertTrue(manager.managedPaths().isEmpty(), "backup must be consumed by the rollback");
        } finally {
            tempFiles.close();
        }
    }

    @Test
    void noSourceConfiguredKeepsExistingJar() throws IOException {
        Path target = createJar(dir, "server.jar", "original-payload");
        byte[] original = bytes(target);
        TempFileManager tempFiles = newTempFiles();
        try {
            ServerJarManager manager = new ServerJarManager(target, null,
                    ServerJarManager.defaultVerifier(""), tempFiles, Log.console());
            assertEquals(ServerJarManager.Result.KEPT_EXISTING, manager.ensureServerJar());
            assertArrayEquals(original, bytes(target));
        } finally {
            tempFiles.close();
        }
    }

    @Test
    void missingJarWithoutSourceIsReported() throws IOException {
        TempFileManager tempFiles = newTempFiles();
        try {
            ServerJarManager manager = new ServerJarManager(dir.resolve("server.jar"), null,
                    ServerJarManager.defaultVerifier(""), tempFiles, Log.console());
            assertEquals(ServerJarManager.Result.FAILED, manager.ensureServerJar());
        } finally {
            tempFiles.close();
        }
    }

    @Test
    void unreachableSourceFailsCleanlyWithoutExistingJar() throws IOException {
        TempFileManager tempFiles = newTempFiles();
        try {
            Path target = dir.resolve("server.jar");
            ServerJarManager manager = new ServerJarManager(target,
                    new ServerJarManager.HttpJarSource("http://127.0.0.1:1/no-jar.jar"),
                    ServerJarManager.defaultVerifier(""), tempFiles, Log.console());
            assertEquals(ServerJarManager.Result.FAILED, manager.ensureServerJar(),
                    "unreachable source must fail cleanly when no jar exists");
            assertFalse(Files.exists(target));
        } finally {
            tempFiles.close();
        }
    }

    @Test
    void unreachableSourceKeepsExistingJar() throws IOException {
        TempFileManager tempFiles = newTempFiles();
        try {
            Path target = createJar(dir, "server.jar", "original-payload");
            byte[] original = bytes(target);
            ServerJarManager manager = new ServerJarManager(target,
                    new ServerJarManager.HttpJarSource("http://127.0.0.1:1/no-jar.jar"),
                    ServerJarManager.defaultVerifier(""), tempFiles, Log.console());
            assertEquals(ServerJarManager.Result.KEPT_EXISTING, manager.ensureServerJar());
            assertArrayEquals(original, bytes(target));
        } finally {
            tempFiles.close();
        }
    }
}
