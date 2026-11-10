package com.codescope.index;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads source lines to populate the {@code snippet} fields added to tool
 * results (issue #9: results used to carry only {@code file} + {@code line},
 * with no code, so a consumer had to re-open the file to see what was
 * actually there).
 *
 * <p>The index stores file paths relative to the project root (see
 * {@link JdtIndexer#relativize}), so {@link #line(String, int)} resolves a
 * relative path against the root it was constructed with. Absolute paths
 * are used as-is, which also covers sources outside the root that were
 * relativized with a {@code ..} prefix.
 *
 * <p>Lines are cached per file for the lifetime of one request — a trace
 * tree references the same handful of files from many nodes, and the
 * symbol / call-site result sets are bounded by their {@code limit}. A
 * file that cannot be read (missing, unreadable, non-UTF-8) is cached as
 * absent so a large result does not retry it per row.
 */
public final class SourceSnippet {

    /** Cached sentinel for a file that could not be read. */
    private static final List<String> MISSING = List.of();

    private final Path root;
    private final Map<String, List<String>> cache = new HashMap<>();

    /**
     * @param root project root used to resolve the index's relative file
     *             paths; may be null when the caller only ever passes
     *             absolute paths (relative paths then resolve against the
     *             process working directory, which is the best available
     *             guess).
     */
    public SourceSnippet(Path root) {
        this.root = root;
    }

    /**
     * Returns the text of the 1-based {@code line} in {@code file}, with
     * trailing whitespace stripped (indentation is preserved), or
     * {@code null} when the file or line is unavailable. {@code line <= 0}
     * (the "unknown" value the index uses for undeclared methods) yields
     * null.
     */
    public String line(String file, int line) {
        if (file == null || line <= 0) return null;
        List<String> lines = lines(file);
        if (line > lines.size()) return null;
        return lines.get(line - 1).stripTrailing();
    }

    private List<String> lines(String file) {
        List<String> cached = cache.get(file);
        if (cached != null) return cached;
        List<String> read = readLines(file);
        cache.put(file, read);
        return read;
    }

    private List<String> readLines(String file) {
        Path path;
        try {
            path = Paths.get(file);
            if (!path.isAbsolute() && root != null) path = root.resolve(file);
        } catch (RuntimeException e) {
            // InvalidPathException (e.g. a NUL byte in the stored path)
            // must not escape as a runtime exception from a result-formatting
            // path — the snippet is best-effort.
            return MISSING;
        }
        try {
            if (!Files.isRegularFile(path)) return MISSING;
            return Files.readAllLines(path, StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException e) {
            return MISSING;
        }
    }
}
