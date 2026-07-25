package com.codescope;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Business logic for {@code trace_callers}: validates a Maven project root,
 * loads/looks up the reverse call index, and runs the BFS analyzer. The
 * JSON envelope around the {@link CallChainAnalyzer.Result} is built here too
 * so the MCP adapter layer stays thin.
 */
public final class TraceCallersService {

    private final ObjectMapper json = ProjectIndexCache.newObjectMapper();

    private final CallChainAnalyzer analyzer = new CallChainAnalyzer();

    /**
     * Shared LRU + index builder. Centralized in {@link ProjectIndexCache}
     * so a single MCP session that uses {@code trace_callers} and
     * {@code find_symbols} against the same project indexes it once.
     */
    private final ProjectIndexCache indexCache;

    public TraceCallersService() {
        this(new ProjectIndexCache());
    }

    public TraceCallersService(ProjectIndexCache indexCache) {
        this.indexCache = indexCache;
    }

    /**
     * @param refresh if true, evict the cached index for {@code projectRoot}
     *                and rebuild it. Use this after the user has edited
     *                files — the cache is process-lifetime and never
     *                invalidates on its own.
     * @param includeTests if true, index {@code src/test/java} in addition
     *                to {@code src/main/java}. Default {@code false}:
     *                test sources are excluded (test code does not
     *                participate in the call chain by default). When
     *                {@code true}, test methods appear as callers in
     *                trace_callers results.
     * @return JSON envelope containing the trace tree, status, and message
     * @throws TraceCallersException with a user-facing error message
     */
    public String traceCallersJson(String className, String methodName,
                                   Integer arity, List<String> paramTypes,
                                   Path projectRoot, boolean refresh,
                                   boolean includeTests) throws TraceCallersException {
        ProjectIndex index = ProjectIndexCache.validateAndLoad(
                indexCache, projectRoot, refresh, includeTests, TraceCallersException::new);

        // Resolve against project declarations or library call edges.
        // MethodResolver centralizes the try/catch/format/fallback path
        // that was previously duplicated here and in FindCallSitesService.
        MethodResolver.Result res;
        try {
            res = MethodResolver.resolve(index, className, methodName, arity, paramTypes);
        } catch (ProjectIndex.AmbiguousMethodException e) {
            throw new TraceCallersException(MethodResolver.overloadsHint(
                    index, className, methodName, e));
        }

        MethodKey target;
        List<MethodKey> seeds;
        MethodKey display;
        if (res instanceof MethodResolver.Result.ProjectTarget pt) {
            target = pt.target();
            seeds = List.of(target);
            display = target;
        } else {
            MethodResolver.Result.LibrarySeeds ls = (MethodResolver.Result.LibrarySeeds) res;
            target = null;
            seeds = ls.seeds();
            display = ls.display();
        }

        CallChainAnalyzer.Result r = (target != null)
                ? analyzer.traceCallers(index, target)
                : analyzer.traceCallers(index, display, seeds);

        String message = r.message();
        // Library overload union suffix only when seeds collected >1 keys.
        // (For project targets, seeds == List.of(target) so size <= 1.)
        if (seeds.size() > 1) {
            message = message + MethodResolver.overloadUnionSuffix(seeds);
        }
        message = ProjectIndexCache.withSkippedFilesSuffix(message, index);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("target", r.root().toJson());
        // Result.found is always true after the analyzer's contract change
        // (an empty chain with a "No callers" message is a valid answer,
        // not a miss). We still emit "status" as a stable, documented
        // envelope field for clients that key off it.
        out.put("status", "ok");
        out.put("message", message);
        try {
            return json.writeValueAsString(out);
        } catch (JsonProcessingException e) {
            throw new TraceCallersException("Failed to serialize result: " + e.getMessage());
        }
    }

    /** Thrown by {@link #traceCallersJson} for user-facing error conditions. */
    public static final class TraceCallersException extends Exception {
        public TraceCallersException(String message) { super(message); }
    }
}
