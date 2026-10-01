package com.example.pro.cleanup;

import java.nio.file.Path;
import java.util.Collection;

/**
 * A component that owns files the plugin itself created and therefore can clean them up safely.
 *
 * {@link SafeCleaner} deletes only paths reported by registered {@code CleanupRegistry}
 * instances — nothing is ever scanned from disk.
 */
public interface CleanupRegistry {

    /** Snapshot of the paths (files or directories) currently managed by this component. */
    Collection<Path> managedPaths();

    /** Removes a path from management after it has been deleted. Returns false if unknown. */
    boolean unregister(Path path);
}
