package com.codescope;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
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

    private static final int MAX_CACHED_PROJECTS = 8;

    private final ObjectMapper json = new ObjectMapper();
    private final JdtIndexer indexer = new JdtIndexer();
    private final CallChainAnalyzer analyzer = new CallChainAnalyzer();

    /**
     * Bounded LRU: bounds memory for long-running MCP sessions that may be
     * pointed at many different project roots over time. Synchronized because
     * the MCP host may dispatch concurrent tool calls on different threads.
     */
    private final Map<Path, ProjectIndex> indexCache = Collections.synchronizedMap(
            new LinkedHashMap<>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<Path, ProjectIndex> e) {
                    return size() > MAX_CACHED_PROJECTS;
                }
            });

    public TraceCallersService() {}

    /**
     * @return JSON envelope containing the trace tree, status, and message
     * @throws TraceCallersException with a user-facing error message
     */
    public String traceCallersJson(String className, String methodName,
                                   Integer arity, List<String> paramTypes,
                                   Path projectRoot) throws TraceCallersException {
        if (!Files.isDirectory(projectRoot)) {
            throw new TraceCallersException("Project root is not a directory: " + projectRoot);
        }
        if (!Files.isRegularFile(projectRoot.resolve("pom.xml"))) {
            throw new TraceCallersException("No pom.xml at " + projectRoot
                    + " — only Maven projects are supported in this version.");
        }

        ProjectIndex index;
        try {
            index = indexCache.computeIfAbsent(projectRoot, this::buildIndex);
        } catch (UncheckedIOException e) {
            throw new TraceCallersException(e.getCause().getMessage());
        }

        MethodKey target;
        try {
            target = index.resolveTarget(className, methodName, arity, paramTypes);
        } catch (ProjectIndex.AmbiguousMethodException e) {
            throw new TraceCallersException(e.getMessage() + " Available overloads: "
                    + index.findOverloads(className, methodName).stream()
                            .map(MethodKey::toString)
                            .toList());
        }
        if (target == null) {
            throw new TraceCallersException("Could not find a method '" + methodName
                    + "' declared in '" + className
                    + (arity != null ? "' with arity " + arity : "")
                    + "'. Check that the project sources are on the analyzed source roots.");
        }

        CallChainAnalyzer.Result r = analyzer.traceCallers(index, target);

        String message = r.message();
        List<String> skipped = index.skippedFiles();
        if (!skipped.isEmpty()) {
            // Note: the file list itself is omitted from the wire response —
            // just count, so the message stays compact. Callers can re-run
            // with a debug build to see the per-file reasons.
            message = message + " (skipped " + skipped.size() + " unparseable file(s))";
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("target", r.root().toJson());
        out.put("status", r.found() ? "ok" : "not_found");
        out.put("message", message);
        try {
            return json.writeValueAsString(out);
        } catch (JsonProcessingException e) {
            throw new TraceCallersException("Failed to serialize result: " + e.getMessage());
        }
    }

    private ProjectIndex buildIndex(Path projectRoot) {
        try {
            ProjectLoader.LoadResult load = new ProjectLoader().load(projectRoot);
            return indexer.build(load.sources(), load.classpath(), load.sourcepath(), projectRoot);
        } catch (IOException e) {
            // Wrapped so the computeIfAbsent lambda can throw it. Failure is
            // not cached, so a future call (e.g. after fixing the project)
            // will retry.
            throw new UncheckedIOException("Failed to load project at " + projectRoot, e);
        }
    }

    /** Thrown by {@link #traceCallersJson} for user-facing error conditions. */
    public static final class TraceCallersException extends Exception {
        public TraceCallersException(String message) { super(message); }
    }
}
