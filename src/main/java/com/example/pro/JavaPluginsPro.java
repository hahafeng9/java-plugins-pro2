package com.example.pro;

import com.example.pro.cleanup.CleanReport;
import com.example.pro.cleanup.SafeCleaner;
import com.example.pro.command.ProCommand;
import com.example.pro.lifecycle.PluginStatus;
import com.example.pro.lifecycle.ServerJarManager;
import com.example.pro.runtime.Log;
import com.example.pro.runtime.ProcessManager;
import com.example.pro.runtime.ProRuntime;
import com.example.pro.runtime.TempFileManager;
import com.example.pro.runtime.ThreadManager;
import com.example.sbx.App;

import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * JavaPluginsPro — hardened fork of the java-plugins proxy plugin.
 *
 * <p>Lifecycle rules enforced here:
 * <ul>
 *   <li>the server jar is checked, backed up, replaced and rolled back by
 *       {@link ServerJarManager} — all off the main thread;</li>
 *   <li>every temp file lives under one randomized per-run directory owned by
 *       {@link TempFileManager} and is removed when the plugin stops;</li>
 *   <li>every thread is created through {@link ThreadManager} with a unique, identifiable name
 *       and is interrupted + joined on stop;</li>
 *   <li>every child process is tracked by {@link ProcessManager} (PID + Process) and destroyed
 *       on stop — nothing detached, renamed or hidden;</li>
 *   <li>no fake console output, no sleeping on the main server thread, no untracked background
 *       work.</li>
 * </ul>
 */
public final class JavaPluginsPro extends JavaPlugin {

    private volatile String state = "ENABLING";
    private TempFileManager tempFiles;
    private ThreadManager threads;
    private ProcessManager processes;
    private ServerJarManager jarManager;
    private SafeCleaner cleaner;
    private Thread shutdownHook;
    private long joinTimeoutMillis = 10_000;

    @Override
    public void onEnable() {
        state = "ENABLING";
        saveDefaultConfig();
        joinTimeoutMillis = Math.max(1_000, getConfig().getLong("shutdown.join-timeout-millis", 10_000));
        Log log = new BukkitLog(getLogger());

        try {
            tempFiles = new TempFileManager(tempBaseDir(), log);
        } catch (IOException e) {
            getLogger().severe("Cannot create the plugin temp directory: " + e.getMessage());
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        threads = new ThreadManager(log);
        processes = new ProcessManager(log);
        ProRuntime.bind(threads, processes, tempFiles);

        Path serverRoot = resolveServerRoot();
        Path dataDir = getDataFolder().toPath().toAbsolutePath().normalize();
        Path serverJar = serverRoot.resolve(getConfig().getString("server-jar.path", "server.jar")).normalize();
        int maxBackups = Math.max(1, getConfig().getInt("server-jar.max-backups", 3));
        jarManager = new ServerJarManager(serverJar,
                ServerJarManager.fromConfig(getConfig().getString("server-jar.source", "")),
                ServerJarManager.defaultVerifier(getConfig().getString("server-jar.expected-sha256", "")),
                tempFiles, log, maxBackups);
        cleaner = new SafeCleaner(List.of(tempFiles, jarManager),
                List.of(tempFiles.runDir(), dataDir, serverRoot), log);

        shutdownHook = new Thread(() -> {
            // JVM exit path — same teardown as onDisable, minus the Bukkit calls.
            ProRuntime.unbind();
            if (threads != null) {
                threads.shutdownQuietly(joinTimeoutMillis);
            }
            if (processes != null) {
                processes.shutdownQuietly(joinTimeoutMillis);
            }
            if (tempFiles != null) {
                tempFiles.close();
            }
        }, "JavaPluginsPro-ShutdownHook");
        Runtime.getRuntime().addShutdownHook(shutdownHook);

        PluginCommand command = getCommand("plugin");
        if (command != null) {
            ProCommand executor = new ProCommand(this);
            command.setExecutor(executor);
            command.setTabCompleter(executor);
        }

        // server.jar maintenance and the legacy App both run on tracked workers — never on the
        // main server thread.
        threads.newWorker("server-jar", () ->
                log.info("[server-jar] Startup check result: " + jarManager.ensureServerJar())).start();
        threads.newWorker("app", () -> {
            try {
                App.main(new String[0]);
            } catch (Throwable t) {
                log.error("[app] App terminated: " + t);
            }
        }).start();

        state = "ENABLED";
        getLogger().info("JavaPluginsPro enabled — commands: /plugin status | /plugin cleanup [--dry-run] | /plugin threads");
    }

    @Override
    public void onDisable() {
        state = "DISABLING";
        getLogger().info("JavaPluginsPro stopping: interrupting threads, stopping child processes, cleaning temp files...");
        getServer().getScheduler().cancelTasks(this);
        ProRuntime.unbind();
        if (threads != null) {
            threads.shutdown(joinTimeoutMillis);
        }
        if (processes != null) {
            processes.shutdown(joinTimeoutMillis);
        }
        if (tempFiles != null) {
            tempFiles.close();
        }
        if (shutdownHook != null) {
            try {
                Runtime.getRuntime().removeShutdownHook(shutdownHook);
            } catch (IllegalStateException ignored) {
                // JVM is already shutting down
            }
        }
        state = "DISABLED";
        getLogger().info("JavaPluginsPro stopped cleanly");
    }

    /** Builds the status snapshot shown by /plugin status. */
    public PluginStatus status() {
        return new PluginStatus(
                getDescription().getVersion(),
                state,
                getDataFolder().toPath().toAbsolutePath().normalize().toString(),
                tempFiles == null ? null : tempFiles.runDir().toString(),
                App.runtimeDir().toString(),
                threads == null ? 0 : threads.taskCount(),
                threads == null ? 0 : threads.activeCount(),
                processes == null ? 0 : processes.trackedCount(),
                (tempFiles == null ? 0 : tempFiles.managedCount())
                        + (jarManager == null ? 0 : jarManager.managedPaths().size()));
    }

    public List<String> statusLines() {
        return status().lines();
    }

    /** Runs the cleanup (or dry-run) and returns chat-ready report lines. */
    public List<String> cleanupReportLines(boolean dryRun) {
        if (cleaner == null) {
            return List.of("Cleanup is not available (plugin not fully enabled).");
        }
        CleanReport report = cleaner.execute(dryRun);
        return report.summaryLines(20);
    }

    /** Debug report of every plugin-created thread, for /plugin threads. */
    public List<String> threadReportLines() {
        return threads == null ? List.of("Thread manager unavailable.") : threads.describe();
    }

    private Path tempBaseDir() throws IOException {
        String configured = getConfig().getString("temp.base-dir", "");
        Path base = (configured == null || configured.isBlank())
                ? Path.of(System.getProperty("java.io.tmpdir", "tmp"))
                : Path.of(configured);
        base = base.toAbsolutePath().normalize();
        Files.createDirectories(base);
        return base;
    }

    private Path resolveServerRoot() {
        File pluginsDir = getDataFolder().getParentFile();
        File root = pluginsDir == null ? null : pluginsDir.getParentFile();
        return (root == null ? Path.of(".") : root.toPath()).toAbsolutePath().normalize();
    }

    private static final class BukkitLog implements Log {

        private final java.util.logging.Logger logger;

        BukkitLog(java.util.logging.Logger logger) {
            this.logger = logger;
        }

        @Override
        public void info(String message) {
            logger.info(message);
        }

        @Override
        public void warn(String message) {
            logger.warning(message);
        }

        @Override
        public void error(String message) {
            logger.severe(message);
        }
    }
}
