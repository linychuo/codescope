package com.codescope.index;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * Persists a built {@link ProjectIndex} to disk and loads it back (issue #9
 * follow-up: the CLI is a one-shot process, so without a cache every
 * invocation paid the full Maven + JDT build cost).
 *
 * <p>Uses Java serialization: every index structure ({@link ProjectIndex},
 * the {@link MethodKey} keys, and the record values) is {@link
 * java.io.Serializable}, so no bespoke format is needed. The cache is keyed
 * by {@link IndexFingerprint}, which covers the source files, the mapper
 * XML, and the pom contents — a miss simply rebuilds.
 */
public final class ProjectIndexStore {

    /**
     * Upper bound on cached indexes, so a project that changes often (or a
     * test suite that builds many temp projects) does not grow the cache
     * directory without limit. The least-recently-written entries are
     * deleted once the bound is exceeded.
     */
    private static final int MAX_CACHE_ENTRIES = 200;

    private ProjectIndexStore() {}

    /**
     * Writes {@code index} to {@code file}. The parent directory is created
     * if needed. Best-effort: a failure to write leaves the build usable
     * (the in-memory index is still returned).
     */
    public static void save(ProjectIndex index, Path file) throws IOException {
        Path parent = file.getParent();
        if (parent != null) Files.createDirectories(parent);
        // Temp + atomic move: a concurrent reader (or a crash mid-write) must
        // never leave a truncated index that later deserializes as a
        // corrupt-but-present cache entry.
        Path tmp = Files.createTempFile(parent, "index-", ".tmp");
        boolean moved = false;
        try {
            try (ObjectOutputStream out = new ObjectOutputStream(
                    new BufferedOutputStream(Files.newOutputStream(tmp)))) {
                out.writeObject(index);
            }
            try {
                Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                        java.nio.file.StandardCopyOption.ATOMIC_MOVE);
                moved = true;
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                moved = true;
            }
        } finally {
            if (!moved) Files.deleteIfExists(tmp);
        }
        evictOldEntries(parent, file);
    }

    /**
     * Deletes the oldest {@code index-*.ser} entries in {@code dir} once the
     * count exceeds {@link #MAX_CACHE_ENTRIES}, keeping {@code keep}. Any
     * failure is ignored: eviction is housekeeping, not part of the result.
     */
    private static void evictOldEntries(Path dir, Path keep) {
        if (dir == null) return;
        try (Stream<Path> entries = Files.list(dir)) {
            List<Path> cached = entries
                    .filter(p -> p.getFileName().toString().startsWith("index-"))
                    .filter(p -> !p.equals(keep))
                    .sorted(Comparator.comparingLong(p -> {
                        try {
                            return Files.getLastModifiedTime(p).toMillis();
                        } catch (IOException e) {
                            return 0L;
                        }
                    }))
                    .toList();
            int excess = cached.size() + 1 - MAX_CACHE_ENTRIES;
            for (int i = 0; i < excess && i < cached.size(); i++) {
                Files.deleteIfExists(cached.get(i));
            }
        } catch (Exception ignored) {
            // Best-effort.
        }
    }

    /**
     * Reads an index previously written by {@link #save}. Returns null when
     * the file is missing or cannot be read/parsed — the caller rebuilds.
     */
    public static ProjectIndex load(Path file) {
        if (!Files.isRegularFile(file)) return null;
        try (ObjectInputStream in = new ObjectInputStream(
                new BufferedInputStream(Files.newInputStream(file)))) {
            Object o = in.readObject();
            return o instanceof ProjectIndex index ? index : null;
        } catch (IOException | ClassNotFoundException | RuntimeException e) {
            return null;
        }
    }
}
