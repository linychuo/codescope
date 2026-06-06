package com.codescope;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP tool: given a (class, method) pair, returns a nested tree of every
 * method that calls it, recursively, until no more callers are found.
 *
 * Caches the reverse call index per project root, so repeated queries on
 * the same project re-use the parsed ASTs.
 */
public final class TraceCallersTool implements Tool {

    private final ObjectMapper json = new ObjectMapper();
    private final JdtIndexer indexer = new JdtIndexer();
    private final CallChainAnalyzer analyzer = new CallChainAnalyzer();

    private final Map<Path, ProjectIndex> indexCache = new LinkedHashMap<>();

    @Override public String name() { return "trace_callers"; }

    @Override
    public String description() {
        return "Find every method that calls the given method, transitively, in the project. "
                + "Returns a nested tree (root = target method, children = direct callers, "
                + "each child expanded with their own callers). Stops when no more callers exist.";
    }

    @Override
    public Map<String, Object> inputSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("required", List.of("class", "method"));
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("class", Map.of(
                "type", "string",
                "description", "Fully qualified class name, e.g. com.example.Foo"));
        props.put("method", Map.of(
                "type", "string",
                "description", "Method name (overloads are not disambiguated; first match wins)"));
        props.put("project", Map.of(
                "type", "string",
                "description", "Path to the Maven/Gradle project root. Defaults to current working directory."));
        schema.put("properties", props);
        return schema;
    }

    @Override
    public ToolResult invoke(Map<String, Object> args) throws Exception {
        String className = requiredString(args, "class");
        String methodName = requiredString(args, "method");
        Path projectRoot = resolveProjectRoot(args);

        if (!Files.isDirectory(projectRoot)) {
            return ToolResult.err("Project root is not a directory: " + projectRoot);
        }
        if (!Files.isRegularFile(projectRoot.resolve("pom.xml"))) {
            return ToolResult.err("No pom.xml at " + projectRoot
                    + " — only Maven projects are supported in this version.");
        }

        ProjectIndex index = indexCache.computeIfAbsent(projectRoot, this::buildIndex);

        MethodKey target = index.resolveTarget(className, methodName);
        if (target == null) {
            return ToolResult.err("Could not find a method '" + methodName + "' declared in '"
                    + className + "'. Check that the project sources are on the analyzed source roots.");
        }

        CallChainAnalyzer.Result r = analyzer.traceCallers(index, target);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("target", r.root().toJson());
        out.put("status", r.found() ? "ok" : "not_found");
        out.put("message", r.message());
        return ToolResult.ok(json.writeValueAsString(out));
    }

    private ProjectIndex buildIndex(Path projectRoot) {
        try {
            ProjectLoader.LoadResult load = new ProjectLoader().load(projectRoot);
            return indexer.build(load.sources(), load.classpath(), load.sourcepath(), projectRoot);
        } catch (IOException e) {
            throw new RuntimeException("Failed to load project at " + projectRoot + ": " + e.getMessage(), e);
        }
    }

    private static String requiredString(Map<String, Object> args, String key) {
        Object v = args.get(key);
        if (v == null || !(v instanceof String s) || s.isBlank()) {
            throw new IllegalArgumentException("Missing or non-string required argument: " + key);
        }
        return s;
    }

    private static Path resolveProjectRoot(Map<String, Object> args) {
        Object p = args.get("project");
        if (p == null) return Paths.get(System.getProperty("user.dir")).toAbsolutePath();
        return Paths.get(p.toString()).toAbsolutePath();
    }
}
