package com.example.pro;

import com.example.pro.lifecycle.PluginStatus;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The /plugin status snapshot must contain every counter required by the lifecycle spec. */
class PluginStatusTest {

    @Test
    void linesContainAllRequiredFields() {
        PluginStatus status = new PluginStatus(
                "2.0.0", "ENABLED", "/srv/mc/plugins/JavaPluginsPro", "/tmp/JavaPluginsPro-ab12cd34ef56",
                "/srv/mc/.tmp", 2, 3, 1, 5);

        List<String> lines = status.lines();

        assertEquals(9, lines.size());
        assertTrue(lines.get(0).contains("JavaPluginsPro v2.0.0"));
        assertTrue(join(lines).contains("State:            ENABLED"));
        assertTrue(join(lines).contains("Work directory:   /srv/mc/plugins/JavaPluginsPro"));
        assertTrue(join(lines).contains("Temp directory:   /tmp/JavaPluginsPro-ab12cd34ef56"));
        assertTrue(join(lines).contains("App runtime dir:  /srv/mc/.tmp"));
        assertTrue(join(lines).contains("Tasks running:    2"));
        assertTrue(join(lines).contains("Plugin threads:   3"));
        assertTrue(join(lines).contains("Child processes:  1"));
        assertTrue(join(lines).contains("Temp files:       5"));
    }

    @Test
    void nullDirectoriesFallBackToPlaceholder() {
        PluginStatus status = new PluginStatus("2.0.0", "ENABLING", null, null, null, 0, 0, 0, 0);
        assertTrue(join(status.lines()).contains("n/a"));
    }

    private static String join(List<String> lines) {
        return String.join("\n", lines);
    }
}
