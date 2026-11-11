package com.codescope.mcp;

import com.codescope.analysis.CalleeChainAnalyzer;
import com.codescope.index.ProjectIndexCache;
import com.codescope.service.TraceCalleesService;
import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP adapter for {@code trace_callees}. Argument parsing + JSON envelope
 * live here; the actual indexing and analysis live in
 * {@link TraceCalleesService}. Mirrors {@link TraceCallersTool} for the
 * shared arguments and the {@code project} resolution.
 */
public final class TraceCalleesTool extends AbstractMcpTool {

    private final TraceCalleesService service;

    public TraceCalleesTool() {
        this(new ProjectIndexCache());
    }

    public TraceCalleesTool(ProjectIndexCache cache) {
        this.service = new TraceCalleesService(cache);
    }

    @Override public String name() { return "trace_callees"; }

    @Override
    public String description() {
        return "Find every method the given method calls, transitively, in the project. "
                + "Returns a nested tree (root = target method, children = direct callees, "
                + "each child expanded with their own callees) — the downward mirror of "
                + "`trace_callers`. Use this to answer \"what does this method eventually "
                + "do?\" For upward caller chains, use `trace_callers` instead. "
                + "Each node carries the source `snippet` and, for framework entry points, "
                + "an `entryPoint` label. MyBatis mapper interface methods additionally "
                + "carry their SQL statement as an `sql` leaf.";
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
                "description", "Absolute path to the Maven project root. "
                        + "Required unless the MCP host advertises a workspace root via `roots`."));
        props.put("refresh", Map.of(
                "type", "boolean",
                "default", false,
                "description", "If true, evict the cached index for this project and rebuild it."));
        props.put("include_tests", Map.of(
                "type", "boolean",
                "default", false,
                "description", "If true, index src/test/java in addition to src/main/java."));
        props.put("max_depth", Map.of(
                "type", "integer",
                "minimum", 0,
                "maximum", TraceCalleesService.MAX_DEPTH_LIMIT,
                "default", CalleeChainAnalyzer.DEFAULT_MAX_DEPTH,
                "description", "Maximum number of callee levels to expand: 0 returns only the "
                        + "target, 1 adds direct callees, 2 adds their callees, and so on. "
                        + "Default " + CalleeChainAnalyzer.DEFAULT_MAX_DEPTH + "."));
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
        Integer maxDepthArg = optionalInt(args, "max_depth");
        int maxDepth = maxDepthArg == null ? CalleeChainAnalyzer.DEFAULT_MAX_DEPTH : maxDepthArg;

        try {
            String json = service.traceCalleesJson(
                    className, methodName, arity, paramTypes,
                    projectRoot, refresh, includeTests, maxDepth);
            return ToolResult.text(json);
        } catch (TraceCalleesService.TraceCalleesException e) {
            return ToolResult.error(e.getMessage());
        }
    }
}
