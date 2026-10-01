package com.example.pro.runtime;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Factory and registry for every thread the plugin creates.
 *
 * <p>Threads get unique, identifiable names of the form {@code JavaPluginsPro-Worker-<random id>}
 * and are tracked in a registry, so {@link #shutdown(long)} can interrupt and join them when the
 * plugin stops. No untracked background threads are spawned: long-running work must be started
 * through this manager (or via {@link ProRuntime#newWorkerThread(String, Runnable)} from legacy
 * static code).
 */
public final class ThreadManager {

    private record ThreadInfo(String purpose, boolean countsAsTask) {
    }

    private static final String NAME_PREFIX = "JavaPluginsPro-Worker-";

    private final Log log;
    private final Map<Thread, ThreadInfo> tracked = new ConcurrentHashMap<>();

    public ThreadManager(Log log) {
        this.log = log;
    }

    /** Creates (unstarted) a tracked task worker, e.g. the app runner or the jar updater. */
    public Thread newWorker(String purpose, Runnable task) {
        return create(purpose, true, task);
    }

    /** Creates (unstarted) a tracked helper thread, e.g. a native service runner. */
    public Thread newThread(String purpose, Runnable task) {
        return create(purpose, false, task);
    }

    private Thread create(String purpose, boolean countsAsTask, Runnable task) {
        Thread thread = new Thread(task, NAME_PREFIX + RandomToken.generate(8));
        thread.setDaemon(true);
        thread.setUncaughtExceptionHandler((t, e) ->
                log.error("[thread] " + t.getName() + " (" + purpose + ") died with: " + e));
        tracked.put(thread, new ThreadInfo(purpose, countsAsTask));
        return thread;
    }

    /** Number of tracked threads that are still alive. */
    public int activeCount() {
        int count = 0;
        for (Thread thread : tracked.keySet()) {
            if (thread.isAlive()) {
                count++;
            }
        }
        return count;
    }

    /** Number of alive tracked threads that run plugin tasks (as opposed to helper threads). */
    public int taskCount() {
        int count = 0;
        for (Map.Entry<Thread, ThreadInfo> entry : tracked.entrySet()) {
            if (entry.getKey().isAlive() && entry.getValue().countsAsTask()) {
                count++;
            }
        }
        return count;
    }

    /** One line per alive tracked thread, for the /plugin threads debug command. */
    public List<String> describe() {
        List<String> lines = new ArrayList<>();
        tracked.forEach((thread, info) -> {
            if (thread.isAlive()) {
                lines.add(thread.getName() + " [" + info.purpose() + "] " + thread.getState());
            }
        });
        return lines;
    }

    /**
     * Interrupts every tracked thread (except the caller), waits up to {@code timeoutMillis} for
     * them to finish and reports the ones that refuse to die.
     */
    public void shutdown(long timeoutMillis) {
        List<Thread> targets = new ArrayList<>();
        for (Thread thread : tracked.keySet()) {
            if (thread.isAlive() && thread != Thread.currentThread()) {
                targets.add(thread);
            }
        }
        for (Thread thread : targets) {
            thread.interrupt();
        }
        long deadline = System.currentTimeMillis() + timeoutMillis;
        for (Thread thread : targets) {
            long remaining = deadline - System.currentTimeMillis();
            if (remaining > 0) {
                try {
                    thread.join(remaining);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            if (thread.isAlive()) {
                log.warn("[thread] " + thread.getName() + " did not stop within the join timeout");
            } else {
                tracked.remove(thread);
            }
        }
    }

    /** Same as {@link #shutdown(long)} but never throws — used on the JVM shutdown path. */
    public void shutdownQuietly(long timeoutMillis) {
        try {
            shutdown(timeoutMillis);
        } catch (RuntimeException e) {
            log.error("[thread] shutdown failed: " + e);
        }
    }
}
