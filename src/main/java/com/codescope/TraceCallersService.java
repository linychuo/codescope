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

    /**
     * Jackson's default StreamWriteConstraints cap nesting at 1000. Our
     * {@link CallChainAnalyzer} caps BFS depth at 500, which is well within
     * that limit, so this raised cap is belt-and-suspenders: if the BFS
     * depth cap is ever loosened (or a future bug widens the tree), the
     * mapper itself won't be the choke point. 50_000 matches the BFS node
     * cap.
     *
     * <p>Package-private factory so tests can exercise the same mapper
     * the service uses, without going through the full Maven-project
     * loading path (which would take seconds to set up just to test
     * Jackson's nesting cap).
     */
    static ObjectMapper newObjectMapper() {
        ObjectMapper m = new ObjectMapper();
        m.getFactory().setStreamWriteConstraints(
                com.fasterxml.jackson.core.StreamWriteConstraints.builder()
                        .maxNestingDepth(50_000)
                        .build());
        return m;
    }

    private final ObjectMapper json = newObjectMapper();

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
     * @param refresh if true, evict the cached index for {@code projectRoot}
     *                and rebuild it. Use this after the user has edited
     *                files — the cache is process-lifetime and never
     *                invalidates on its own.
     * @return JSON envelope containing the trace tree, status, and message
     * @throws TraceCallersException with a user-facing error message
     */
    public String traceCallersJson(String className, String methodName,
                                   Integer arity, List<String> paramTypes,
                                   Path projectRoot, boolean refresh) throws TraceCallersException {
        if (!Files.isDirectory(projectRoot)) {
            throw new TraceCallersException("Project root is not a directory: " + projectRoot);
        }
        if (!Files.isRegularFile(projectRoot.resolve("pom.xml"))) {
            throw new TraceCallersException("No pom.xml at " + projectRoot
                    + " — only Maven projects are supported in this version.");
        }

        ProjectIndex index;
        try {
            if (refresh) {
                // Evict first so concurrent computeIfAbsent from another
                // thread can't return the stale value while we're rebuilding.
                indexCache.remove(projectRoot);
            }
            index = indexCache.computeIfAbsent(projectRoot, this::buildIndex);
        } catch (UncheckedIOException e) {
            throw new TraceCallersException(e.getCause().getMessage());
        }

        // Resolve against project declarations. This is the right path for
        // project methods: it gives a precise MethodKey (with parameter
        // types from the declaration, if the user didn't supply any) and
        // surfaces ambiguity for project-only overloads.
        MethodKey target;
        try {
            target = index.resolveTarget(className, methodName, arity, paramTypes);
        } catch (ProjectIndex.AmbiguousMethodException e) {
            // findOverloads only sees project declarations. For library
            // methods this list is always empty, so we say so explicitly
            // and point the user at the paramTypes path.
            List<MethodKey> projectOverloads = index.findOverloads(className, methodName);
            String overloadsHint = projectOverloads.isEmpty()
                    ? "no overloads are visible in this project's sources (the class is likely from a library); "
                            + "pass `paramTypes` with the FQN types to pick one"
                    : "available overloads: " + projectOverloads.stream()
                            .map(MethodKey::toString)
                            .toList();
            throw new TraceCallersException(e.getMessage() + " " + overloadsHint + ".");
        }

        // Library targets: the class is not declared in project sources
        // (e.g. java.io.PrintStream). JdtIndexer still recorded every
        // call edge with the resolved binding signature (arity, FQN
        // param types), so we look up the call-edge map by the same
        // selector. Any matches are seeded into the analyzer's BFS as
        // a single logical target — a method that calls println(String)
        // AND println(int) appears once under the synthesized root.
        List<MethodKey> seeds = List.of();
        CallChainAnalyzer.Result r;
        if (target == null) {
            seeds = index.findInvokedKeys(className, methodName, arity, paramTypes);
            // The displayed root uses the user's selector (arity defaults
            // to 0 if not provided). It's only the BFS *seeds* that need
            // to match the recorded keys.
            MethodKey display = new MethodKey(className, methodName,
                    arity == null ? 0 : arity,
                    paramTypes == null ? List.of() : paramTypes);
            if (seeds.isEmpty()) {
                // Synthesize the display key as the single seed so the
                // analyzer still produces a coherent "no callers"
                // message instead of a degenerate result.
                r = analyzer.traceCallers(index, display, List.of(display));
            } else {
                r = analyzer.traceCallers(index, display, seeds);
            }
        } else {
            r = analyzer.traceCallers(index, target);
        }

        String message = r.message();
        // For library targets where multiple overloads matched, surface
        // which signatures we unioned so the user knows what was bundled
        // into the displayed root.
        if (target == null && seeds.size() > 1) {
            String overloads = seeds.stream()
                    .map(MethodKey::fullSignature)
                    .sorted()
                    .toList()
                    .toString();
            message = message + " (combined callers across "
                    + seeds.size() + " library overloads: " + overloads + ")";
        }
        List<String> skipped = index.skippedFiles();
        if (!skipped.isEmpty()) {
            // Note: the file list itself is omitted from the wire response —
            // just count, so the message stays compact. Callers can re-run
            // with a debug build to see the per-file reasons.
            message = message + " (skipped " + skipped.size() + " unparseable file(s))";
        }

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
