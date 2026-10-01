package com.example.pro.runtime;

import java.nio.file.Path;

/**
 * Static bridge that lets legacy static code ({@code com.example.sbx.App}) hand its threads,
 * child processes and temp files to the plugin's management infrastructure.
 *
 * <p>Everything here is optional: when no plugin instance is bound (e.g. running {@code App}
 * standalone via its {@code main} method), the helpers fall back to the previous unmanaged
 * behaviour, so the class never depends on a Bukkit context.
 */
public final class ProRuntime {

    private static volatile ThreadManager threads;
    private static volatile ProcessManager processes;
    private static volatile TempFileManager tempFiles;

    private ProRuntime() {
    }

    public static void bind(ThreadManager threadManager, ProcessManager processManager,
                            TempFileManager tempFileManager) {
        threads = threadManager;
        processes = processManager;
        tempFiles = tempFileManager;
    }

    public static void unbind() {
        threads = null;
        processes = null;
        tempFiles = null;
    }

    /** Returns a new unstarted thread with a tracked, unique {@code JavaPluginsPro-Worker-} name. */
    public static Thread newWorkerThread(String purpose, Runnable task) {
        ThreadManager manager = threads;
        return manager != null
                ? manager.newThread(purpose, task)
                : new Thread(task, "JavaPluginsPro-Worker-" + RandomToken.generate(8));
    }

    public static void trackProcess(String name, Process process) {
        ProcessManager manager = processes;
        if (manager != null) {
            manager.track(name, process);
        }
    }

    public static void untrackProcess(Process process) {
        ProcessManager manager = processes;
        if (manager != null) {
            manager.untrack(process);
        }
    }

    public static void registerTempFile(Path path) {
        TempFileManager manager = tempFiles;
        if (manager != null) {
            manager.register(path);
        }
    }

    public static void unregisterTempFile(Path path) {
        TempFileManager manager = tempFiles;
        if (manager != null) {
            manager.unregister(path);
        }
    }
}
