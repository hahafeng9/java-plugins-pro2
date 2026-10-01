package com.example.pro.runtime;

/**
 * Test helper: a plain Java process that sleeps, used by {@code ProcessManagerTest} and the
 * lifecycle integration test to prove that plugin-managed child processes really die on stop.
 */
public final class Sleeper {

    public static void main(String[] args) throws Exception {
        Thread.sleep(Long.parseLong(args[0]));
    }

    private Sleeper() {
    }
}
