package com.example.pro.lifecycle;

import com.example.pro.runtime.Log;
import com.example.pro.runtime.TempFileManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
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

    @Test
    void backupRetentionIsEnforcedAutomatically() throws IOException {
        TempFileManager tempFiles = newTempFiles();
        try {
            Path target = createJar(dir, "server.jar", "payload-0");
            byte[] firstJarBytes = bytes(target);
            final int[] round = {0};
            ServerJarManager.JarSource cyclingSource = new ServerJarManager.JarSource() {
                @Override
                public Path fetch(Path downloadDir) throws IOException {
                    round[0]++;
                    Path source = createJar(dir, "src" + round[0] + ".jar", "payload-" + round[0]);
                    Path staged = downloadDir.resolve("cycled-" + round[0] + ".jar");
                    Files.copy(source, staged, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    return staged;
                }

                @Override
                public String describe() {
                    return "cycling-test-source";
                }
            };
            ServerJarManager manager = new ServerJarManager(target, cyclingSource,
                    ServerJarManager.defaultVerifier(""), tempFiles, Log.console(), 2);

            for (int cycle = 1; cycle <= 5; cycle++) {
                assertEquals(ServerJarManager.Result.UPDATED, manager.ensureServerJar());
                assertTrue(manager.managedPaths().size() <= 2,
                        "retention of 2 must never be exceeded, cycle " + cycle);
            }
            assertEquals(2, manager.managedPaths().size(), "exactly the newest 2 backups must remain");

            // The newest backup (created during the last cycle, holding the previous jar) must
            // still be present.
            byte[] previousJar = bytes(dir.resolve("src4.jar"));
            assertTrue(manager.managedPaths().stream().anyMatch(p -> {
                try {
                    return Arrays.equals(previousJar, bytes(p));
                } catch (IOException e) {
                    return false;
                }
            }), "the newest backup must survive retention trimming");
            // And the replaced jar itself is the freshly installed one.
            assertArrayEquals(bytes(dir.resolve("src5.jar")), bytes(target));
        } finally {
            tempFiles.close();
        }
    }
}
