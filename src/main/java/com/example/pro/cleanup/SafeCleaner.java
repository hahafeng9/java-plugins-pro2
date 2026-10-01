package com.example.pro.cleanup;

import com.example.pro.runtime.Log;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Implements the {@code /plugin cleanup} command.
 *
 * <p>Safety rules enforced here:
 * <ul>
 *   <li>only paths that a {@link CleanupRegistry} reports as plugin-created are candidates —
 *       the filesystem is never scanned and arbitrary system files are never touched;</li>
 *   <li>before every deletion the path is re-checked: any {@code ..} segment, or a resolved
 *       location outside the plugin's allowed roots, is refused with a concrete reason;</li>
 *   <li>{@code --dry-run} lists what would be deleted without touching anything;</li>
 *   <li>deletion failures are reported with the underlying cause.</li>
 * </ul>
 */
public final class SafeCleaner {

    private final List<CleanupRegistry> registries;
    private final List<Path> allowedRoots;
    private final Log log;

    public SafeCleaner(List<CleanupRegistry> registries, List<Path> allowedRoots, Log log) {
        this.registries = List.copyOf(registries);
        List<Path> roots = new ArrayList<>();
        for (Path root : allowedRoots) {
            roots.add(root.toAbsolutePath().normalize());
        }
        this.allowedRoots = List.copyOf(roots);
        this.log = log;
    }

    /** One registered cleanup candidate: the path as registered plus its normalized form. */
    record Candidate(Path registered, Path normalized, CleanupRegistry registry) {
    }

    /** Union of all registered paths, keyed by absolute normalized path (insertion order kept). */
    public Map<Path, Candidate> candidates() {
        Map<Path, Candidate> candidates = new LinkedHashMap<>();
        for (CleanupRegistry registry : registries) {
            for (Path path : registry.managedPaths()) {
                candidates.putIfAbsent(path.toAbsolutePath().normalize(),
                        new Candidate(path, path.toAbsolutePath().normalize(), registry));
            }
        }
        return candidates;
    }

    /** Runs the cleanup (or the dry-run listing) over all registered candidates. */
    public CleanReport execute(boolean dryRun) {
        List<String> planned = new ArrayList<>();
        List<String> deleted = new ArrayList<>();
        List<String> refused = new ArrayList<>();
        List<String> failed = new ArrayList<>();
        for (Candidate candidate : candidates().values()) {
            String problem = refusalReason(candidate.registered());
            if (problem != null) {
                refused.add(candidate.normalized() + " — " + problem);
                log.warn("[cleanup] Refused " + candidate.normalized() + ": " + problem);
                continue;
            }
            if (dryRun) {
                planned.add(candidate.normalized().toString());
                continue;
            }
            try {
                deleteRecursively(candidate.normalized());
                candidate.registry().unregister(candidate.normalized());
                deleted.add(candidate.normalized().toString());
                log.info("[cleanup] Deleted " + candidate.normalized());
            } catch (IOException e) {
                failed.add(candidate.normalized() + " — " + e.getMessage());
                log.warn("[cleanup] Failed to delete " + candidate.normalized() + ": " + e.getMessage());
            }
        }
        return new CleanReport(dryRun, planned, deleted, refused, failed);
    }

    /**
     * Returns {@code null} when the path may be deleted, or the reason it must not be touched:
     * either an explicit {@code ..} traversal segment or a resolved location outside every
     * plugin-managed root.
     */
    public String refusalReason(Path path) {
        for (Path segment : path) {
            if (segment.toString().equals("..")) {
                return "path traversal ('..') is not allowed";
            }
        }
        Path normalized = path.toAbsolutePath().normalize();
        for (Path root : allowedRoots) {
            if (normalized.startsWith(root)) {
                return null;
            }
        }
        return "outside the plugin-managed directories";
    }

    static void deleteRecursively(Path path) throws IOException {
        if (!Files.exists(path) || !Files.isDirectory(path) || Files.isSymbolicLink(path)) {
            Files.deleteIfExists(path);
            return;
        }
        List<Path> children;
        try (var stream = Files.walk(path)) {
            children = stream.sorted(Comparator.reverseOrder()).collect(java.util.stream.Collectors.toList());
        }
        IOException failure = null;
        for (Path child : children) {
            try {
                Files.deleteIfExists(child);
            } catch (IOException e) {
                if (failure == null) {
                    failure = new IOException("failed to delete " + child + ": " + e.getMessage(), e);
                }
            }
        }
        if (failure != null) {
            throw failure;
        }
    }
}
