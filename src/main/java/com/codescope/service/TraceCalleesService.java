package com.codescope.service;

import com.codescope.analysis.CalleeChainAnalyzer;
import com.codescope.analysis.MethodResolver;
import com.codescope.index.ProjectIndex;
import com.codescope.index.ProjectIndexCache;
import com.codescope.index.SourceSnippet;
import com.codescope.model.MethodKey;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Business logic for {@code trace_callees}: the downward companion to
 * {@link TraceCallersService}. Given a method, returns the nested tree of
 * the methods it invokes, transitively (issue #9 item 2).
 *
 * <p>Target resolution, project-root validation, the LRU {@code indexCache}
 * and the {@code refresh} / {@code includeTests} contracts are shared with
 * the other services via {@link MethodResolver} and {@link ProjectIndexCache}.
 */
public final class TraceCalleesService {

    /** Hard ceiling on the callee-depth cap; see {@link CalleeChainAnalyzer#DEFAULT_MAX_DEPTH}. */
    public static final int MAX_DEPTH_LIMIT = TraceCallersService.MAX_DEPTH_LIMIT;

    private final ObjectMapper json = ProjectIndexCache.newObjectMapper();

    /**
     * Shared LRU + index builder. Centralized in {@link ProjectIndexCache}
     * so a single MCP session that uses {@code trace_callees} and the other
     * tools against the same project indexes it once.
     */
    private final ProjectIndexCache indexCache;

    public TraceCalleesService() {
        this(new ProjectIndexCache());
    }

    public TraceCalleesService(ProjectIndexCache indexCache) {
        this.indexCache = indexCache;
    }

    /**
     * @param refresh if true, evict the cached index for {@code projectRoot}
     *                and rebuild it
     * @param includeTests if true, index {@code src/test/java} in addition
     *                to {@code src/main/java}
     * @param maxDepth maximum number of callee levels to expand: 0 returns
     *                 only the target, 1 adds direct callees, and so on
     * @return JSON envelope containing the callee tree, status, and message
     * @throws TraceCalleesException with a user-facing error message
     */
    public String traceCalleesJson(String className, String methodName,
                                   Integer arity, List<String> paramTypes,
                                   Path projectRoot, boolean refresh,
                                   boolean includeTests) throws TraceCalleesException {
        return traceCalleesJson(className, methodName, arity, paramTypes, projectRoot,
                refresh, includeTests, CalleeChainAnalyzer.DEFAULT_MAX_DEPTH);
    }

    /**
     * As {@link #traceCalleesJson(String, String, Integer, List, Path, boolean, boolean)},
     * with an explicit callee-depth cap.
     *
     * @param maxDepth see {@link CalleeChainAnalyzer#CalleeChainAnalyzer(int, int)}
     * @throws TraceCalleesException with a user-facing error message
     */
    public String traceCalleesJson(String className, String methodName,
                                   Integer arity, List<String> paramTypes,
                                   Path projectRoot, boolean refresh,
                                   boolean includeTests, int maxDepth)
            throws TraceCalleesException {
        if (maxDepth < 0 || maxDepth > MAX_DEPTH_LIMIT) {
            throw new TraceCalleesException("maxDepth must be between 0 and " + MAX_DEPTH_LIMIT
                    + "; got " + maxDepth + ".");
        }
        ProjectIndex index = ProjectIndexCache.validateAndLoad(
                indexCache, projectRoot, refresh, includeTests, TraceCalleesException::new);

        MethodResolver.Result res;
        try {
            res = MethodResolver.resolve(index, className, methodName, arity, paramTypes);
        } catch (ProjectIndex.AmbiguousMethodException e) {
            throw new TraceCalleesException(MethodResolver.overloadsHint(
                    index, className, methodName, e));
        }

        MethodKey display;
        List<MethodKey> seeds;
        if (res instanceof MethodResolver.Result.ProjectTarget pt) {
            display = pt.target();
            seeds = List.of(display);
        } else {
            MethodResolver.Result.LibrarySeeds ls = (MethodResolver.Result.LibrarySeeds) res;
            display = ls.display();
            seeds = ls.seeds();
        }

        CalleeChainAnalyzer analyzer = new CalleeChainAnalyzer(
                CalleeChainAnalyzer.DEFAULT_MAX_NODES, maxDepth);
        CalleeChainAnalyzer.Result r = analyzer.traceCallees(index, display, seeds);

        String message = ProjectIndexCache.withSkippedFilesSuffix(r.message(), index);

        Map<String, Object> out = new LinkedHashMap<>();
        SourceSnippet snippets = new SourceSnippet(projectRoot);
        out.put("target", r.root().toJson(snippets::line, "callees"));
        out.put("status", "ok");
        out.put("message", message);
        try {
            return json.writeValueAsString(out);
        } catch (JsonProcessingException e) {
            throw new TraceCalleesException("Failed to serialize result: " + e.getMessage());
        }
    }

    /** Thrown by {@link #traceCalleesJson} for user-facing error conditions. */
    public static final class TraceCalleesException extends Exception {
        public TraceCalleesException(String message) { super(message); }
    }
}
