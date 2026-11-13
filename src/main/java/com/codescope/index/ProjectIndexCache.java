package com.codescope.index;

import com.codescope.loader.ProjectLoader;
import com.fasterxml.jackson.core.StreamWriteConstraints;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;

/**
 * Shared LRU cache of {@link ProjectIndex} keyed by project root, plus the
 * Jackson {@link ObjectMapper} factory used by every {@code *Service} for
 * JSON envelopes. Centralized here so {@link TraceCallersService},
 * {@link FindCallSitesService}, and {@link FindSymbolsService} share a
 * single cache (a single MCP session that uses {@code trace_callers}
 * then {@code find_symbols} against the same project indexes it once,
 * not twice) and a single mapper-config code path.
 *
 * <p>The cache is bounded by {@link #MAX_CACHED_PROJECTS} entries;
 * further inserts evict the least-recently-used. Synchronized because
 * the MCP host may dispatch concurrent tool calls on different
 * threads, and the access-order LRU needs coherent writes.
 *
 * <p>{@link #loadOrRebuild} is the single entry point used by every
 * service. {@code refresh=true} evicts first so a concurrent
 * {@code computeIfAbsent} from another thread cannot return the stale
 * value while a rebuild is in progress.
 */
public final class ProjectIndexCache {

    /** Max number of projects kept in the LRU at once. */
    static final int MAX_CACHED_PROJECTS = 8;

    /**
     * Cache key. A project is indexed separately for each
     * {@code (projectRoot, includeTests)} pair so that a session which
     * first indexes without tests and then asks for the test-inclusive
     * view doesn't get the test-less entry back, and vice versa. The LRU
     * cap on entries still applies, so flipping back and forth between
     * many such pairs in a single session will eventually evict.
     */
    record IndexCacheKey(Path projectRoot, boolean includeTests) {}

    private final JdtIndexer indexer;
    private final Map<IndexCacheKey, ProjectIndex> entries = Collections.synchronizedMap(
            new LinkedHashMap<>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<IndexCacheKey, ProjectIndex> e) {
                    return size() > MAX_CACHED_PROJECTS;
                }
            });
    /**
     * Fixed-size lock strip for per-key build serialization. Bounded so a
     * long-lived server that indexes many distinct projects does not retain
     * one lock per project forever; distinct keys that hash to the same
     * stripe serialize, which is safe (the cache holds at most
     * {@link #MAX_CACHED_PROJECTS} entries, so stripe contention is rare).
     * The {@code entries} map stays globally locked for its short LRU
     * operations; only the slow build happens outside it.
     */
    private static final int LOCK_STRIPES = 64;
    private final ReentrantLock[] keyLocks = new ReentrantLock[LOCK_STRIPES];

    public ProjectIndexCache() {
        this(new JdtIndexer());
    }

    ProjectIndexCache(JdtIndexer indexer) {
        this.indexer = indexer;
        for (int i = 0; i < LOCK_STRIPES; i++) keyLocks[i] = new ReentrantLock();
    }

    private ReentrantLock lockFor(IndexCacheKey key) {
        return keyLocks[Math.floorMod(key.hashCode(), LOCK_STRIPES)];
    }

    /**
     * Return the cached index for {@code projectRoot}, or build it if
     * absent. On {@code refresh=true} the cached entry is evicted first
     * so a concurrent caller cannot return a stale value mid-rebuild.
     *
     * <p>The build runs under a per-key lock but outside the cache mutex,
     * so different projects build concurrently while the LRU map stays
     * correct. IO failures during build are wrapped in
     * {@link UncheckedIOException}; the cache leaves the entry unset so a
     * future call (e.g. after the user fixes the project) will retry
     * rather than serve a poisoned entry.
     */
    public ProjectIndex loadOrRebuild(Path projectRoot, boolean refresh, boolean includeTests) {
        IndexCacheKey key = new IndexCacheKey(projectRoot, includeTests);
        ReentrantLock lock = lockFor(key);
        lock.lock();
        try {
            synchronized (entries) {
                if (!refresh) {
                    ProjectIndex cached = entries.get(key);
                    if (cached != null) return cached;
                } else {
                    entries.remove(key);
                }
            }
            ProjectIndex built = buildIndex(key);
            synchronized (entries) {
                ProjectIndex raced = entries.get(key);
                if (raced != null) return raced;
                entries.put(key, built);
                return built;
            }
        } finally {
            lock.unlock();
        }
    }

    private ProjectIndex buildIndex(IndexCacheKey key) {
        try {
            ProjectLoader.LoadResult load = new ProjectLoader().load(key.projectRoot(), key.includeTests());
            // Relativize against the effective root (the aggregator when the
            // user pointed at a sub-module), not the sub-module itself —
            // otherwise sibling-module files get ".." segments.
            return indexer.build(load.sources(), load.classpath(), load.sourcepath(), load.root(),
                    key.includeTests());
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to load project at " + key.projectRoot(), e);
        }
    }

    /**
     * Shared Jackson {@link ObjectMapper} for every {@code *Service}.
     * Default {@code StreamWriteConstraints} cap nesting at 1000; we
     * raise it to {@value #MAX_NESTING_DEPTH} so deep call chains
     * emitted by {@link CallChainAnalyzer} serialize cleanly. A tree is
     * at most {@link CallChainAnalyzer}'s {@code MAX_DEPTH} (500) frames
     * deep, so the mapper's nesting limit is never the choke point.
     *
     * <p>Package-private so tests can exercise the same mapper the
     * services use, without going through the full Maven-project
     * loading path (which would take seconds to set up just to test
     * Jackson's nesting cap).
     */
    static final int MAX_NESTING_DEPTH = 50_000;

    public static ObjectMapper newObjectMapper() {
        ObjectMapper m = new ObjectMapper();
        m.getFactory().setStreamWriteConstraints(
                StreamWriteConstraints.builder()
                        .maxNestingDepth(MAX_NESTING_DEPTH)
                        .build());
        return m;
    }

    /**
     * Validate {@code projectRoot} as a Maven project root and load
     * (or rebuild) its index. Validation and IO failures are surfaced
     * through the caller's exception type via {@code exceptionFactory},
     * so each service can keep its own {@code XxxException} without
     * the helper knowing about every concrete error type.
     *
     * <p>Used by {@link TraceCallersService}, {@link FindCallSitesService},
     * and {@link FindSymbolsService} to consolidate the directory +
     * pom.xml + load-and-wrap dance that was previously duplicated in
     * each service.
     */
    public static <E extends Exception> ProjectIndex validateAndLoad(
            ProjectIndexCache cache, Path projectRoot, boolean refresh,
            boolean includeTests,
            Function<String, E> exceptionFactory) throws E {
        if (!Files.isDirectory(projectRoot)) {
            throw exceptionFactory.apply("Project root is not a directory: " + projectRoot);
        }
        if (!Files.isRegularFile(projectRoot.resolve("pom.xml"))) {
            throw exceptionFactory.apply("No pom.xml at " + projectRoot
                    + " — only Maven projects are supported in this version.");
        }
        try {
            return cache.loadOrRebuild(projectRoot, refresh, includeTests);
        } catch (UncheckedIOException e) {
            throw exceptionFactory.apply(e.getCause().getMessage());
        }
    }

    /**
     * Append the {@code "(skipped N unparseable file(s))"} suffix to a
     * user-facing message when the index has any skipped files.
     * Returns {@code message} unchanged otherwise. Centralized so the
     * wording stays consistent across services.
     */
    public static String withSkippedFilesSuffix(String message, ProjectIndex index) {
        List<String> skipped = index.skippedFiles();
        if (skipped.isEmpty()) return message;
        return message + " (skipped " + skipped.size() + " unparseable file(s))";
    }
}
