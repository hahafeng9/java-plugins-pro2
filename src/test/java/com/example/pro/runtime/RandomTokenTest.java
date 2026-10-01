package com.example.pro.runtime;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Requirement: randomized temp names must never collide between runs and must follow safe
 * naming rules.
 */
class RandomTokenTest {

    @Test
    void matchesSafeNamingRules() {
        for (int i = 0; i < 100; i++) {
            String token = RandomToken.generate(12);
            assertTrue(token.matches("[a-z0-9]{12}"), "unsafe name: " + token);
        }
    }

    @Test
    void generatedNamesNeverCollide() {
        Set<String> seen = new HashSet<>();
        int samples = 20_000;
        for (int i = 0; i < samples; i++) {
            assertTrue(seen.add(RandomToken.generate(12)), "collision after " + i + " draws");
        }
        assertEquals(samples, seen.size());
    }

    @Test
    void respectsRequestedLength() {
        assertEquals(6, RandomToken.generate(6).length());
        assertEquals(32, RandomToken.generate(32).length());
    }

    @Test
    void rejectsInvalidLength() {
        assertThrows(IllegalArgumentException.class, () -> RandomToken.generate(0));
        assertThrows(IllegalArgumentException.class, () -> RandomToken.generate(65));
    }
}
