package com.codescope;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP adapter for {@code trace_callers}. Argument parsing + JSON envelope
 * live here; the actual indexing and analysis live in
 * {@link TraceCallersService}. Argument-parsing helpers and the
 * {@code project} resolution live in {@link AbstractMcpTool}.
 */
public final class TraceCallersTool extends AbstractMcpTool {

    private final TraceCallersService service = new TraceCallersService();

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
                        + "participate in the call chain by default). When true, test "
                        + "methods appear as callers in trace_callers results."));
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
            String json = service.traceCallersJson(
                    className, methodName, arity, paramTypes,
                    projectRoot, refresh, includeTests);
            return ToolResult.text(json);
        } catch (TraceCallersService.TraceCallersException e) {
            return ToolResult.error(e.getMessage());
        }
    }
}
