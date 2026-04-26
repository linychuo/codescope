package com.codescope;

import java.nio.file.Path;
import java.util.Map;

/**
 * Interface for AST cache management.
 */
public interface CacheStrategy {

    /**
     * Saves file timestamps to cache.
     */
    void save(Map<Path, Long> timestamps) throws java.io.IOException;

    /**
     * Loads file timestamps from cache.
     */
    Map<Path, Long> load();

    /**
     * Checks if cache is valid for given files.
     */
    boolean isValid(Map<Path, Long> currentTimestamps);
}
