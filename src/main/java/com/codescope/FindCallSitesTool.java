package com.codescope;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP adapter for {@code find_call_sites}. Argument parsing + JSON
 * envelope live here; the actual indexing and analysis live in
 * {@link FindCallSitesService}. Argument-parsing helpers and the
 * {@code project} resolution live in {@link AbstractMcpTool}.
 */
public final class FindCallSitesTool extends AbstractMcpTool {

    private final FindCallSitesService service = new FindCallSitesService();

    @Override public String name() { return "find_call_sites"; }

    @Override
    public String description() {
        return "List every call site (caller method + exact line) of the given method in the "
                + "project. Returns a flat array of call sites — not a transitive caller tree. "
                + "Use this to answer \"is X called in my project, and where exactly?\" "
                + "For transitive caller chains, use `trace_callers` instead. "
                + "The target may be a method declared in a jar on the classpath; call sites "
                + "in the project's own sources are still reported.";
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
                        + "Use together with `arity` to pick a specific overload. "
                        + "Primitives must be lowercase (\"int\", \"boolean\", \"long\", ...); "
                        + "boxed types use the FQN form (\"java.lang.Integer\"). "
                        + "Mismatch with the on-disk declaration will be reported as a missing target."));
        props.put("project", Map.of(
                "type", "string",
                "description", "Absolute path to the Maven/Gradle project root. "
                        + "Required unless the MCP host advertises a workspace root via `roots`. "
                        + "Only main source roots (src/<...>/main/java) are indexed; test sources are excluded."));
        props.put("refresh", Map.of(
                "type", "boolean",
                "default", false,
                "description", "If true, evict the cached index for this project and rebuild it. "
                        + "The index cache is process-lifetime and does not detect file changes, "
                        + "so set this after editing source files. Has no effect on the first call "
                        + "for a given project (the cache is empty)."));
        props.put("include_tests", Map.of(
                "type", "boolean",
                "default", false,
                "description", "If true, index src/test/java in addition to src/main/java. "
                        + "Default false: test sources are excluded (test code does not "
                        + "participate in the call-site index by default). When true, test "
                        + "methods surface as caller entries in find_call_sites results."));
        schema.put("properties", props);
        return schema;
    }

    @Override
    public ToolResult invoke(Map<String, Object> args) throws IOException {
        String className = requiredString(args, "class");
        String methodName = requiredString(args, "method");
        Integer arity = optionalInt(args, "arity");
        List<String> paramTypes = optionalStringList(args, "paramTypes");
        Path projectRoot = resolveProjectRoot(args);
        boolean refresh = optionalBool(args, "refresh");
        boolean includeTests = optionalBool(args, "include_tests");

        try {
            String json = service.findCallSitesJson(
                    className, methodName, arity, paramTypes,
                    projectRoot, refresh, includeTests);
            return ToolResult.text(json);
        } catch (FindCallSitesService.FindCallSitesException e) {
            return ToolResult.error(e.getMessage());
        }
    }
}
