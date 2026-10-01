package com.example.pro;

import com.example.pro.runtime.Log;
import com.example.pro.runtime.ProcessManager;
import com.example.pro.runtime.ProRuntime;
import com.example.pro.runtime.TempFileManager;
import com.example.pro.runtime.ThreadManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Requirement: repeated start/stop cycles of the plugin must not leave behind any of the
 * plugin's own temp resources; threads and child processes must be gone after each stop.
 *
 * <p>This simulates the plugin enable/disable sequence at component level (no Bukkit server
 * required): TempFileManager + ThreadManager + ProcessManager + ProRuntime binding.
 */
class LifecycleIntegrationTest {

    @TempDir
    Path base;

    private static ProcessBuilder sleeper(String millis) {
        String javaBin = Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name", "").toLowerCase().contains("win") ? "java.exe" : "java").toString();
        return new ProcessBuilder(javaBin, "-cp", System.getProperty("java.class.path"),
                "com.example.pro.runtime.Sleeper", millis);
    }

    @Test
    void repeatedStartStopCyclesLeaveNoResidue() throws Exception {
        Set<String> runDirNames = new HashSet<>();
        for (int cycle = 1; cycle <= 2; cycle++) {
            Log log = Log.console();
            TempFileManager tempFiles = new TempFileManager(base, log);
            ThreadManager threads = new ThreadManager(log);
            ProcessManager processes = new ProcessManager(log);
            runDirNames.add(tempFiles.runDir().getFileName().toString());

            // "onEnable": bind the runtime, create one temp file, one worker and one child process.
            ProRuntime.bind(threads, processes, tempFiles);
            Path tempFile = tempFiles.newTempFile("cycle-", ".tmp");
            Files.writeString(tempFile, "cycle " + cycle);
            final CountDownLatchLike started = new CountDownLatchLike();
            Thread worker = threads.newWorker("busy-worker", () -> {
                started.countDown();
                try {
                    Thread.sleep(120_000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            worker.start();
            assertTrue(started.await(5_000));
            Process child = processes.start(sleeper("120000"), "sleeper-" + cycle);

            assertTrue(child.isAlive());
            assertEquals(1, threads.activeCount());
            assertEquals(1, processes.trackedCount());
            assertTrue(tempFiles.managedCount() >= 1);

            // "onDisable": unbind, stop threads, stop processes, delete temp files.
            ProRuntime.unbind();
            threads.shutdown(10_000);
            processes.shutdown(10_000);
            tempFiles.close();

            assertFalse(worker.isAlive(), "worker must be dead after cycle " + cycle);
            assertFalse(child.isAlive(), "child process must be dead after cycle " + cycle);
            assertEquals(0, threads.activeCount());
            assertEquals(0, processes.trackedCount());
            assertEquals(0, tempFiles.managedCount());
            assertFalse(Files.exists(tempFile));
        }

        assertEquals(2, runDirNames.size(), "each cycle must get its own unique run directory");
        try (var stream = Files.list(base)) {
            assertTrue(stream.noneMatch(p -> p.getFileName().toString().startsWith("JavaPluginsPro-")),
                    "no plugin temp directories may remain after stop");
        }
    }

    @Test
    void runDirectoriesOfConcurrentManagersNeverCollide() throws IOException {
        try (TempFileManager first = new TempFileManager(base, Log.console());
             TempFileManager second = new TempFileManager(base, Log.console())) {
            assertNotEquals(first.runDir(), second.runDir());
            assertNotEquals(first.runDir().getFileName(), second.runDir().getFileName());
        }
    }

    /** Tiny local latch helper (avoids importing two CountDownLatch flavors in the test). */
    private static final class CountDownLatchLike {
        private final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);

        void countDown() {
            latch.countDown();
        }

        boolean await(long millis) throws InterruptedException {
            return latch.await(millis, java.util.concurrent.TimeUnit.MILLISECONDS);
        }
    }
}
