package com.codescope;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
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

    /** Supplied by the host via MCP `roots`; used when the tool call omits `project`. */
    private volatile String hostDefaultProject;

    public void setHostDefaultProject(String path) { this.hostDefaultProject = path; }

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
                "description", "Method name. If there are multiple overloads, also pass `arity` and `paramTypes`."));
        props.put("arity", Map.of(
                "type", "integer",
                "minimum", 0,
                "description", "Number of declared parameters. Use to disambiguate overloads."));
        props.put("paramTypes", Map.of(
                "type", "array",
                "items", Map.of("type", "string"),
                "description", "Fully qualified parameter type names, e.g. [\"int\", \"java.lang.String\"]. "
                        + "Use together with `arity` to pick a specific overload."));
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
        Integer arity = optionalInt(args, "arity");
        List<String> paramTypes = optionalStringList(args, "paramTypes");
        Path projectRoot = resolveProjectRoot(args);

        if (!Files.isDirectory(projectRoot)) {
            return ToolResult.err("Project root is not a directory: " + projectRoot);
        }
        if (!Files.isRegularFile(projectRoot.resolve("pom.xml"))) {
            return ToolResult.err("No pom.xml at " + projectRoot
                    + " — only Maven projects are supported in this version.");
        }

        ProjectIndex index = indexCache.computeIfAbsent(projectRoot, this::buildIndex);

        MethodKey target;
        try {
            target = index.resolveTarget(className, methodName, arity, paramTypes);
        } catch (ProjectIndex.AmbiguousMethodException e) {
            return ToolResult.err(e.getMessage() + " Available overloads: "
                    + index.findOverloads(className, methodName).stream()
                            .map(MethodKey::toString)
                            .toList());
        }
        if (target == null) {
            return ToolResult.err("Could not find a method '" + methodName + "' declared in '"
                    + className + (arity != null ? "' with arity " + arity : "")
                    + "'. Check that the project sources are on the analyzed source roots.");
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

    private static Integer optionalInt(Map<String, Object> args, String key) {
        Object v = args.get(key);
        if (v == null) return null;
        if (v instanceof Number n) return n.intValue();
        if (v instanceof String s) {
            try { return Integer.parseInt(s.trim()); }
            catch (NumberFormatException e) { /* fall through */ }
        }
        throw new IllegalArgumentException("Optional argument '" + key + "' must be an integer; got: " + v);
    }

    @SuppressWarnings("unchecked")
    private static List<String> optionalStringList(Map<String, Object> args, String key) {
        Object v = args.get(key);
        if (v == null) return null;
        if (v instanceof List<?> list) {
            List<String> out = new ArrayList<>(list.size());
            for (Object item : list) {
                if (!(item instanceof String s)) {
                    throw new IllegalArgumentException("Optional argument '" + key
                            + "' must be a list of strings; got: " + item);
                }
                out.add(s);
            }
            return out;
        }
        throw new IllegalArgumentException("Optional argument '" + key
                + "' must be a list; got: " + v);
    }

    private Path resolveProjectRoot(Map<String, Object> args) {
        Object p = args.get("project");
        if (p != null) return Paths.get(p.toString()).toAbsolutePath();
        if (hostDefaultProject != null && !hostDefaultProject.isBlank()) {
            return Paths.get(hostDefaultProject).toAbsolutePath();
        }
        return Paths.get(System.getProperty("user.dir")).toAbsolutePath();
    }
}
