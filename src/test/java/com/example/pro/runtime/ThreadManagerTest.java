package com.example.pro.runtime;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Requirement: plugin threads use unique identifiable names, and after the plugin stops its
 * threads are interrupted and finish.
 */
class ThreadManagerTest {

    @Test
    void workerNamesAreUniqueAndIdentifiable() {
        ThreadManager manager = new ThreadManager(Log.console());
        for (int i = 0; i < 100; i++) {
            Thread thread = manager.newWorker("test", () -> {
            });
            assertTrue(thread.getName().matches("JavaPluginsPro-Worker-[a-z0-9]{8}"),
                    "unidentifiable name: " + thread.getName());
        }
    }

    @Test
    void shutdownInterruptsThreadsAndTheyFinish() throws Exception {
        ThreadManager manager = new ThreadManager(Log.console());
        AtomicBoolean exitedCleanly = new AtomicBoolean(false);
        CountDownLatch started = new CountDownLatch(1);
        Thread worker = manager.newWorker("sleeping-worker", () -> {
            started.countDown();
            try {
                Thread.sleep(60_000);
            } catch (InterruptedException e) {
                exitedCleanly.set(true);
                Thread.currentThread().interrupt();
            }
        });
        worker.start();
        assertTrue(started.await(5, java.util.concurrent.TimeUnit.SECONDS));
        assertTrue(worker.isAlive());
        assertEquals(1, manager.activeCount());
        assertEquals(1, manager.taskCount());

        manager.shutdown(10_000);

        assertFalse(worker.isAlive(), "worker thread must be dead after shutdown");
        assertTrue(exitedCleanly.get(), "worker must have been interrupted");
        assertEquals(0, manager.activeCount());
        assertEquals(0, manager.taskCount());
    }

    @Test
    void reportsThreadsThatRefuseToDie() throws Exception {
        ThreadManager manager = new ThreadManager(Log.console());
        CountDownLatch started = new CountDownLatch(1);
        AtomicBoolean stop = new AtomicBoolean(false);
        Thread stubborn = manager.newWorker("stubborn", () -> {
            started.countDown();
            // Ignores interrupts on purpose, but exits on the flag so the test stays clean.
            while (!stop.get()) {
                Thread.onSpinWait();
            }
        });
        stubborn.start();
        assertTrue(started.await(5, java.util.concurrent.TimeUnit.SECONDS));

        manager.shutdown(300); // too short on purpose

        assertTrue(stubborn.isAlive(), "stubborn thread should outlive the short timeout");
        assertTrue(manager.describe().stream().anyMatch(line -> line.contains("stubborn")));

        stop.set(true);
        stubborn.join(5_000);
        assertFalse(stubborn.isAlive());
    }

    @Test
    void describeListsActiveWorkers() throws Exception {
        ThreadManager manager = new ThreadManager(Log.console());
        CountDownLatch running = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Thread helper = manager.newThread("service-runner", () -> {
            running.countDown();
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        helper.start();
        assertTrue(running.await(5, java.util.concurrent.TimeUnit.SECONDS));

        List<String> lines = manager.describe();
        assertEquals(1, lines.size());
        assertTrue(lines.get(0).startsWith("JavaPluginsPro-Worker-"));
        assertTrue(lines.get(0).contains("service-runner"));

        release.countDown();
        helper.join(5_000);
    }
}
