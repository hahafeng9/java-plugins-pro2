package com.example.pro.runtime;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Requirement: child processes spawned by the plugin must be tracked (PID/Process) and must
 * actually terminate when the plugin stops them.
 */
class ProcessManagerTest {

    private static ProcessBuilder sleeper(String millis) {
        String javaBin = Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name", "").toLowerCase().contains("win") ? "java.exe" : "java").toString();
        return new ProcessBuilder(javaBin, "-cp", System.getProperty("java.class.path"),
                "com.example.pro.runtime.Sleeper", millis);
    }

    @Test
    void tracksProcessesAndKillsThemOnShutdown() throws Exception {
        ProcessManager manager = new ProcessManager(Log.console());
        Process process = manager.start(sleeper("120000"), "test-sleeper");

        assertTrue(process.isAlive());
        assertTrue(process.pid() > 0, "PID must be available for tracking");
        assertEquals(1, manager.trackedCount());
        assertEquals(1, manager.describe().size());

        manager.shutdown(10_000);

        assertFalse(process.isAlive(), "child process must be dead after shutdown");
        assertEquals(0, manager.trackedCount());
    }

    @Test
    void untrackRemovesExitedProcesses() throws Exception {
        ProcessManager manager = new ProcessManager(Log.console());
        Process process = manager.start(sleeper("0"), "short-lived");
        process.waitFor(10, TimeUnit.SECONDS);
        manager.untrack(process);
        assertEquals(0, manager.trackedCount());
    }
}
