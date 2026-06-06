package com.codescope;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP adapter for {@code trace_callers}. Argument parsing + JSON envelope
 * live here; the actual indexing and analysis live in
 * {@link TraceCallersService}.
 */
public final class TraceCallersTool implements Tool {

    private final TraceCallersService service = new TraceCallersService();

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

        try {
            String json = service.traceCallersJson(className, methodName, arity, paramTypes, projectRoot);
            return ToolResult.text(json);
        } catch (TraceCallersService.TraceCallersException e) {
            return ToolResult.error(e.getMessage());
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
