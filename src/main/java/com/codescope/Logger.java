package com.codescope;

import java.util.function.Supplier;

/**
 * Simple structured logger using System.err for output.
 * Can be replaced with java.util.logging.Logger or other logging frameworks.
 */
public final class Logger {
    private final String component;

    private Logger(String component) {
        this.component = component;
    }

    public static Logger getLogger(String component) {
        return new Logger(component);
    }

    public void warning(String message) {
        System.err.println("[WARN][" + component + "] " + message);
    }

    public void warning(String message, Throwable t) {
        System.err.println("[WARN][" + component + "] " + message + ": " + t.getMessage());
    }

    public void severe(String message) {
        System.err.println("[ERROR][" + component + "] " + message);
    }

    public void severe(String message, Throwable t) {
        System.err.println("[ERROR][" + component + "] " + message + ": " + t.getMessage());
    }

    public void info(String message) {
        System.err.println("[INFO][" + component + "] " + message);
    }

    public void debug(String message) {
        if (Boolean.getBoolean("codescope.debug")) {
            System.err.println("[DEBUG][" + component + "] " + message);
        }
    }

    public void debug(Supplier<String> messageSupplier) {
        if (Boolean.getBoolean("codescope.debug")) {
            System.err.println("[DEBUG][" + component + "] " + messageSupplier.get());
        }
    }
}
