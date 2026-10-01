package com.example.pro.runtime;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Starts and tracks every external child process the plugin spawns.
 *
 * <p>Every {@link Process} object (with its PID) is kept in a registry so
 * {@link #shutdown(long)} can terminate the child when the plugin stops. Processes run as
 * ordinary, visible children — nothing is detached, renamed or hidden from the OS process
 * manager. The plugin prefers plain Java APIs; external processes are only used when a native
 * tool (e.g. openssl) is genuinely required.
 */
public final class ProcessManager {

    private static final long FORCE_KILL_WAIT_MS = 5_000;

    private final Log log;
    private final Map<Process, String> tracked = new ConcurrentHashMap<>();

    public ProcessManager(Log log) {
        this.log = log;
    }

    /** Starts a child process and registers it under a human-readable name. */
    public Process start(ProcessBuilder builder, String name) throws IOException {
        Process process = builder.start();
        track(name, process);
        return process;
    }

    /** Registers an externally created process (e.g. from legacy static helpers) for tracking. */
    public void track(String name, Process process) {
        tracked.put(process, name);
        log.info("[process] '" + name + "' started (pid " + process.pid() + ")");
    }

    /** Removes a process from the registry once it has exited. */
    public void untrack(Process process) {
        String name = tracked.remove(process);
        if (name != null) {
            log.info("[process] '" + name + "' exited (pid " + process.pid() + ")");
        }
    }

    /** Number of tracked processes that are still alive. */
    public int trackedCount() {
        int count = 0;
        for (Process process : tracked.keySet()) {
            if (process.isAlive()) {
                count++;
            }
        }
        return count;
    }

    /** One line per alive tracked process, for the status/threads report. */
    public List<String> describe() {
        List<String> lines = new ArrayList<>();
        tracked.forEach((process, name) -> {
            if (process.isAlive()) {
                lines.add(name + " (pid " + process.pid() + ")");
            }
        });
        return lines;
    }

    /**
     * Destroys every tracked process, waits up to {@code timeoutMillis} for a graceful exit and
     * force-kills whatever refuses to die.
     */
    public void shutdown(long timeoutMillis) {
        List<Process> alive = new ArrayList<>();
        tracked.forEach((process, name) -> {
            if (process.isAlive()) {
                process.destroy();
                alive.add(process);
            }
        });
        long deadline = System.currentTimeMillis() + timeoutMillis;
        for (Process process : alive) {
            String name = tracked.get(process);
            try {
                long remaining = Math.max(1, deadline - System.currentTimeMillis());
                if (!process.waitFor(remaining, TimeUnit.MILLISECONDS)) {
                    log.warn("[process] '" + name + "' ignored destroy — killing forcibly");
                    process.destroyForcibly();
                    process.waitFor(FORCE_KILL_WAIT_MS, TimeUnit.MILLISECONDS);
                }
            } catch (InterruptedException e) {
                process.destroyForcibly();
                Thread.currentThread().interrupt();
                break;
            }
            if (!process.isAlive()) {
                tracked.remove(process);
                log.info("[process] '" + name + "' stopped (exit code " + process.exitValue() + ")");
            }
        }
    }

    /** Same as {@link #shutdown(long)} but never throws — used on the JVM shutdown path. */
    public void shutdownQuietly(long timeoutMillis) {
        try {
            shutdown(timeoutMillis);
        } catch (RuntimeException e) {
            log.error("[process] shutdown failed: " + e);
        }
    }
}
