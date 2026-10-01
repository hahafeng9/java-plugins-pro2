package com.example.pro.lifecycle;

import java.util.List;

/** Immutable snapshot shown by {@code /plugin status}. */
public record PluginStatus(String pluginVersion, String state, String workDir, String tempDir,
                           String appRuntimeDir, int tasks, int threads, int childProcesses,
                           int tempFiles) {

    public PluginStatus {
        workDir = workDir == null ? "n/a" : workDir;
        tempDir = tempDir == null ? "n/a" : tempDir;
        appRuntimeDir = appRuntimeDir == null ? "n/a" : appRuntimeDir;
    }

    public List<String> lines() {
        return List.of(
                "JavaPluginsPro v" + pluginVersion,
                "State:            " + state,
                "Work directory:   " + workDir,
                "Temp directory:   " + tempDir,
                "App runtime dir:  " + appRuntimeDir,
                "Tasks running:    " + tasks,
                "Plugin threads:   " + threads,
                "Child processes:  " + childProcesses,
                "Temp files:       " + tempFiles
        );
    }
}
