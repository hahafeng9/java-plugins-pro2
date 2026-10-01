package com.example.pro.cleanup;

import java.util.ArrayList;
import java.util.List;

/**
 * Result of a cleanup run: what was (or would be) deleted, refused and failed — each entry with
 * its reason.
 */
public record CleanReport(boolean dryRun, List<String> planned, List<String> deleted,
                          List<String> refused, List<String> failed) {

    public CleanReport {
        planned = List.copyOf(planned);
        deleted = List.copyOf(deleted);
        refused = List.copyOf(refused);
        failed = List.copyOf(failed);
    }

    /** Builds a compact, chat-friendly summary, showing at most {@code maxEntries} per section. */
    public List<String> summaryLines(int maxEntries) {
        List<String> lines = new ArrayList<>();
        if (dryRun) {
            lines.add("Cleanup dry-run — would be deleted: " + planned.size()
                    + ", refused: " + refused.size() + ", failed: " + failed.size());
            append(lines, planned, maxEntries, "  - ");
        } else {
            lines.add("Cleanup done — deleted: " + deleted.size()
                    + ", refused: " + refused.size() + ", failed: " + failed.size());
            append(lines, deleted, maxEntries, "  - ");
        }
        append(lines, refused, maxEntries, "  [refused] ");
        append(lines, failed, maxEntries, "  [failed] ");
        return lines;
    }

    private static void append(List<String> out, List<String> entries, int max, String prefix) {
        entries.stream().limit(max).forEach(entry -> out.add(prefix + entry));
        if (entries.size() > max) {
            out.add("  ... and " + (entries.size() - max) + " more");
        }
    }
}
