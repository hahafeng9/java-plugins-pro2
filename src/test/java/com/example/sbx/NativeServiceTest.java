package com.example.sbx;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

/**
 * Regression test for the production failure where a CDN build of a native wrapper library
 * (agent.so) shipped without its JNA exports: looking up the start function used to throw an
 * {@code UnsatisfiedLinkError} that killed the whole App. Now a missing/unusable library only
 * skips that single service.
 */
class NativeServiceTest {

    @Test
    void startSkipsGracefullyWhenLibraryFileIsMissing() {
        App.NativeService service = new App.NativeService(
                "test-svc", Path.of("Z-no-such-library.so"),
                new String[]{"StartTestSvc"}, new String[]{"StopTestSvc"}, "{}");

        assertDoesNotThrow(service::start, "a missing library must be skipped, not crash the app");
        assertDoesNotThrow(service::stop, "stop() must stay a safe no-op for a skipped service");
    }

    @Test
    void startSkipsGracefullyWhenLibraryIsNotNativeCode(@TempDir Path dir) throws Exception {
        Path bogus = dir.resolve("bogus.so");
        Files.writeString(bogus, "this is not an ELF shared object");

        App.NativeService service = new App.NativeService(
                "bogus-svc", bogus,
                new String[]{"StartBogus", "StartBogusAlt"}, new String[]{"StopBogus"}, "{}");

        assertDoesNotThrow(service::start, "an unloadable library must be skipped, not crash the app");
        assertDoesNotThrow(service::stop);
    }
}
