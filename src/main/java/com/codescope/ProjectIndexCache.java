package com.codescope;

import com.fasterxml.jackson.core.StreamWriteConstraints;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

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

    private final JdtIndexer indexer;
    private final Map<Path, ProjectIndex> entries = Collections.synchronizedMap(
            new LinkedHashMap<>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<Path, ProjectIndex> e) {
                    return size() > MAX_CACHED_PROJECTS;
                }
            });

    public ProjectIndexCache() {
        this(new JdtIndexer());
    }

    ProjectIndexCache(JdtIndexer indexer) {
        this.indexer = indexer;
    }

    /**
     * Return the cached index for {@code projectRoot}, or build it if
     * absent. On {@code refresh=true} the cached entry is evicted first
     * so a concurrent caller cannot return a stale value mid-rebuild.
     *
     * <p>IO failures during build are wrapped in
     * {@link UncheckedIOException} so the {@code computeIfAbsent}
     * lambda can throw; the cache leaves the entry unset so a future
     * call (e.g. after the user fixes the project) will retry rather
     * than serve a poisoned entry.
     */
    public ProjectIndex loadOrRebuild(Path projectRoot, boolean refresh) {
        if (refresh) {
            entries.remove(projectRoot);
        }
        return entries.computeIfAbsent(projectRoot, this::buildIndex);
    }

    private ProjectIndex buildIndex(Path projectRoot) {
        try {
            ProjectLoader.LoadResult load = new ProjectLoader().load(projectRoot);
            return indexer.build(load.sources(), load.classpath(), load.sourcepath(), projectRoot);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to load project at " + projectRoot, e);
        }
    }

    /**
     * Shared Jackson {@link ObjectMapper} for every {@code *Service}.
     * Default {@code StreamWriteConstraints} cap nesting at 1000; we
     * raise it to {@value #MAX_NESTING_DEPTH} so deep call chains
     * emitted by {@link CallChainAnalyzer} serialize cleanly. The
     * raised cap matches {@link CallChainAnalyzer#MAX_NODES} so the
     * mapper itself is never the choke point.
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
}
