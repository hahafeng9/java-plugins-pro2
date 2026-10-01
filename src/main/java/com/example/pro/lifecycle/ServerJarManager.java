package com.example.pro.lifecycle;

import com.example.pro.cleanup.CleanupRegistry;
import com.example.pro.runtime.Log;
import com.example.pro.runtime.RandomToken;
import com.example.pro.runtime.TempFileManager;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;

/**
 * Keeps the configured {@code server.jar} healthy.
 *
 * <p>Startup routine ({@link #ensureServerJar()}):
 * <ol>
 *   <li>check that the configured {@code server.jar} exists and log the result;</li>
 *   <li>when an update source is configured, fetch the new jar into a registered random temp
 *       file;</li>
 *   <li>verify its integrity (expected SHA-256 when configured, otherwise a structural zip/jar
 *       check) — on failure the original file is left untouched;</li>
 *   <li>back up the previous jar next to it ({@code server.jar.bak-<timestamp>-<rand>});</li>
 *   <li>move the new jar into place (atomic when the filesystem supports it);</li>
 *   <li>re-verify the installed jar — on any failure the backup is restored automatically.</li>
 * </ol>
 * Every step is logged with a clear outcome. Backups are exposed via the
 * {@link CleanupRegistry} interface so {@code /plugin cleanup} can remove them later.
 */
public final class ServerJarManager implements CleanupRegistry {

    /** Supplies a new jar. Implementations return a freshly written local file. */
    public interface JarSource {

        /** Downloads/copies the jar into {@code downloadDir} and returns the local file. */
        Path fetch(Path downloadDir) throws IOException;

        /** Human-readable description used in log lines. */
        String describe();
    }

    /** Outcome of {@link #ensureServerJar()}. */
    public enum Result {
        /** A new jar was installed and verified. */
        UPDATED,
        /** No healthy update could be installed, but the existing jar is intact and kept. */
        KEPT_EXISTING,
        /** No healthy jar is in place (nothing existed and nothing could be installed). */
        FAILED
    }

    /** Integrity check applied to a jar before it is trusted. */
    public interface Verifier {

        void verify(Path jar) throws IOException;
    }

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    private static final DateTimeFormatter BACKUP_STAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneId.systemDefault());

    private final Path serverJar;
    private final JarSource source;
    private final Verifier verifier;
    private final TempFileManager tempFiles;
    private final Log log;
    private final List<Path> backups = new CopyOnWriteArrayList<>();

    /**
     * @param source    update source, or {@code null} when none is configured (replacement is
     *                  then disabled and the manager only checks/validates the existing file)
     * @param verifier  integrity check for downloaded and installed jars
     */
    public ServerJarManager(Path serverJar, JarSource source, Verifier verifier,
                            TempFileManager tempFiles, Log log) {
        this.serverJar = serverJar.toAbsolutePath().normalize();
        this.source = source;
        this.verifier = verifier;
        this.tempFiles = tempFiles;
        this.log = log;
    }

    public boolean hasSource() {
        return source != null;
    }

    public Path serverJarPath() {
        return serverJar;
    }

    /**
     * Runs the full startup routine described in the class javadoc.
     *
     * @return {@link Result#UPDATED} when a new jar was installed and verified,
     *         {@link Result#KEPT_EXISTING} when the previous jar was kept (update failed but the
     *         original is intact, or no source is configured),
     *         {@link Result#FAILED} when no healthy jar is in place
     */
    public Result ensureServerJar() {
        try {
            boolean existed = Files.exists(serverJar);
            if (existed) {
                log.info("[server-jar] server.jar found: " + serverJar + " (" + Files.size(serverJar) + " bytes)");
            } else {
                log.warn("[server-jar] server.jar not found: " + serverJar);
            }
            if (source == null) {
                if (!existed) {
                    log.error("[server-jar] No jar present and no source configured — cannot provide server.jar");
                    return Result.FAILED;
                }
                log.info("[server-jar] No update source configured — keeping the existing jar");
                return Result.KEPT_EXISTING;
            }

            log.info("[server-jar] Fetching new jar from " + source.describe());
            Path download = source.fetch(tempFiles.runDir());
            tempFiles.register(download);
            log.info("[server-jar] Staged new jar at " + download + " (" + Files.size(download) + " bytes)");
            try {
                try {
                    verifier.verify(download);
                    log.info("[server-jar] New jar passed the integrity check");
                } catch (IOException e) {
                    log.error("[server-jar] New jar failed the integrity check (" + e.getMessage()
                            + ") — original file untouched");
                    return existed ? Result.KEPT_EXISTING : Result.FAILED;
                }

                Path backup = existed ? createBackup() : null;
                try {
                    moveIntoPlace(download);
                } catch (IOException e) {
                    log.error("[server-jar] Failed to install the new jar: " + e.getMessage());
                    if (backup != null) {
                        restoreBackup(backup, "install failed");
                    }
                    return existed ? Result.KEPT_EXISTING : Result.FAILED;
                }

                try {
                    verifier.verify(serverJar);
                    log.info("[server-jar] Installed jar verified in place");
                } catch (IOException e) {
                    log.error("[server-jar] Installed jar failed post-install verification (" + e.getMessage() + ")");
                    if (backup != null) {
                        restoreBackup(backup, "verification failed");
                        return Result.KEPT_EXISTING;
                    }
                    log.error("[server-jar] No previous jar to roll back to — leaving " + serverJar + " in place");
                    return Result.FAILED;
                }

                log.info("[server-jar] server.jar updated successfully"
                        + (backup != null ? " — backup kept at " + backup : " (first install, no backup)"));
                return Result.UPDATED;
            } finally {
                tempFiles.unregister(download);
                try {
                    Files.deleteIfExists(download);
                } catch (IOException e) {
                    log.warn("[server-jar] Failed to remove temp download " + download + ": " + e.getMessage());
                }
            }
        } catch (IOException e) {
            log.error("[server-jar] Update failed: " + e.getMessage());
            return Files.exists(serverJar) ? Result.KEPT_EXISTING : Result.FAILED;
        }
    }

    private void moveIntoPlace(Path download) throws IOException {
        try {
            Files.move(download, serverJar, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(download, serverJar, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private Path createBackup() throws IOException {
        String stamp = BACKUP_STAMP.format(Instant.now());
        Path backup = serverJar.resolveSibling(
                serverJar.getFileName() + ".bak-" + stamp + "-" + RandomToken.generate(6));
        Files.copy(serverJar, backup, StandardCopyOption.REPLACE_EXISTING);
        backups.add(backup);
        log.info("[server-jar] Backup of the previous jar saved: " + backup
                + " (" + Files.size(backup) + " bytes)");
        return backup;
    }

    private void restoreBackup(Path backup, String reason) {
        try {
            Files.move(backup, serverJar, StandardCopyOption.REPLACE_EXISTING);
            backups.remove(backup);
            log.warn("[server-jar] Original server.jar restored from backup (" + reason + ")");
        } catch (IOException e) {
            log.error("[server-jar] Automatic restore failed (" + reason + "): " + e.getMessage()
                    + " — the backup is still available at " + backup);
        }
    }

    @Override
    public Collection<Path> managedPaths() {
        return List.copyOf(backups);
    }

    @Override
    public boolean unregister(Path path) {
        return backups.remove(path.toAbsolutePath().normalize());
    }

    /** Builds a {@link JarSource} from a config string: http(s) URL or local file path. */
    public static JarSource fromConfig(String spec) {
        if (spec == null || spec.isBlank()) {
            return null;
        }
        String trimmed = spec.trim();
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            return new HttpJarSource(trimmed);
        }
        return new FileJarSource(Path.of(trimmed));
    }

    /** Verifier enforcing the expected SHA-256 when configured, else the structural jar check. */
    public static Verifier defaultVerifier(String expectedSha256) {
        String expected = expectedSha256 == null ? "" : expectedSha256.trim().toLowerCase(Locale.ROOT);
        return jar -> {
            if (!expected.isEmpty()) {
                String actual = sha256(jar);
                if (!actual.equals(expected)) {
                    throw new IOException("SHA-256 mismatch: expected " + expected + ", got " + actual);
                }
            } else {
                verifyJarStructure(jar);
            }
        };
    }

    public static String sha256(Path file) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("SHA-256 algorithm unavailable", e);
        }
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
        }
        StringBuilder hex = new StringBuilder();
        for (byte b : digest.digest()) {
            hex.append(String.format("%02x", b));
        }
        return hex.toString();
    }

    /** Cheap structural check: zip magic bytes + readable central directory + a manifest entry. */
    public static void verifyJarStructure(Path jar) throws IOException {
        if (Files.size(jar) == 0) {
            throw new IOException("file is empty");
        }
        try (InputStream in = Files.newInputStream(jar)) {
            byte[] magic = in.readNBytes(4);
            if (magic.length < 4 || (magic[0] & 0xFF) != 0x50 || (magic[1] & 0xFF) != 0x4B) {
                throw new IOException("not a zip/jar file (bad magic bytes)");
            }
        }
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            ZipEntry manifest = zip.getEntry("META-INF/MANIFEST.MF");
            if (manifest == null) {
                throw new IOException("no META-INF/MANIFEST.MF entry — not a runnable jar");
            }
        } catch (ZipException e) {
            throw new IOException("corrupt jar: " + e.getMessage(), e);
        }
    }

    /** Downloads a jar over http(s) into a uniquely named temp file. */
    public static final class HttpJarSource implements JarSource {

        private final String url;

        public HttpJarSource(String url) {
            this.url = url;
        }

        @Override
        public Path fetch(Path downloadDir) throws IOException {
            Path target = downloadDir.resolve("server-new-" + RandomToken.generate(12) + ".jar.part");
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofMinutes(5))
                    .GET()
                    .build();
            HttpResponse<byte[]> response;
            try {
                response = HTTP.send(request, HttpResponse.BodyHandlers.ofByteArray());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("download interrupted", e);
            } catch (IOException e) {
                throw new IOException("download failed: " + e, e);
            }
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IOException("source returned HTTP " + response.statusCode());
            }
            Files.write(target, response.body());
            return target;
        }

        @Override
        public String describe() {
            return url;
        }
    }

    /** Copies a jar from a local file into a uniquely named temp file. */
    public static final class FileJarSource implements JarSource {

        private final Path file;

        public FileJarSource(Path file) {
            this.file = file.toAbsolutePath().normalize();
        }

        @Override
        public Path fetch(Path downloadDir) throws IOException {
            if (!Files.isRegularFile(file)) {
                throw new IOException("source file does not exist: " + file);
            }
            Path target = downloadDir.resolve("server-new-" + RandomToken.generate(12) + ".jar.part");
            Files.copy(file, target, StandardCopyOption.REPLACE_EXISTING);
            return target;
        }

        @Override
        public String describe() {
            return file.toString();
        }
    }
}
