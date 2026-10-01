package com.example.pro.runtime;

/**
 * Minimal logging facade so the runtime components work both inside Bukkit and in plain unit
 * tests. The plugin binds a JUL-backed adapter; tests use {@link #console()}.
 */
public interface Log {

    void info(String message);

    void warn(String message);

    void error(String message);

    static Log console() {
        return new Log() {
            @Override
            public void info(String message) {
                System.out.println(message);
            }

            @Override
            public void warn(String message) {
                System.out.println("[WARN] " + message);
            }

            @Override
            public void error(String message) {
                System.err.println("[ERROR] " + message);
            }
        };
    }
}
