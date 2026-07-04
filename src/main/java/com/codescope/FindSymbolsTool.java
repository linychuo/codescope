package com.codescope;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP adapter for {@code find_symbols}. Argument parsing + JSON
 * envelope live here; the actual indexing and search live in
 * {@link FindSymbolsService}. Argument-parsing helpers and the
 * {@code project} resolution live in {@link AbstractMcpTool};
 * {@link #optionalLimit} is local because it carries a domain-specific
 * default and a {@code >=1} floor tied to {@link FindSymbolsService}.
 */
public final class FindSymbolsTool extends AbstractMcpTool {

    private final FindSymbolsService service = new FindSymbolsService();

    @Override public String name() { return "find_symbols"; }

    @Override
    public String description() {
        return "Search for Java symbols (classes, interfaces, enums, records, annotations, "
                + "methods, constructors, fields) by case-insensitive substring on the simple "
                + "name. Returns a flat list of matching symbols with their kind, FQN, "
                + "container, and source location. Use this to answer \"where is X defined?\" "
                + "or \"what classes/methods contain the word Y?\" without knowing the full "
                + "qualified name. For transitive caller chains, use `trace_callers`; for "
                + "non-transitive call positions, use `find_call_sites`. "
                + "Caveat: under JDT 3.45, top-level record declarations are wrapped in an "
                + "ImplicitTypeDeclaration, so the record kind and its components are NOT "
                + "indexed; nested records and enum/annotation/class kinds are indexed normally.";
    }

    @Override
    public Map<String, Object> inputSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("required", List.of("query"));
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("query", Map.of(
                "type", "string",
                "description", "Case-insensitive substring matched against symbol simple names. "
                        + "Examples: \"Repository\" matches UserRepository, OrderRepository; "
                        + "\"get\" matches every getter; \"validate\" matches a `validate()` "
                        + "method and a `Validator` class."));
        props.put("kind", Map.of(
                "type", "string",
                "enum", List.of("class", "interface", "enum", "record", "annotation",
                        "method", "constructor", "field", "synthetic"),
                "description", "Optional filter. If set, only symbols of this kind are returned. "
                        + "Useful for distinguishing a class named `equals` from the "
                        + "Object#equals method, or finding only fields when many classes share "
                        + "a helper method name. `synthetic` covers <clinit> and <class-init> "
                        + "synthetic methods recorded by JdtIndexer for static/instance init "
                        + "blocks, field initializers, and enum constant args."));
        props.put("project", Map.of(
                "type", "string",
                "description", "Absolute path to the Maven/Gradle project root. "
                        + "Required unless the MCP host advertises a workspace root via `roots`. "
                        + "Only main source roots (src/<...>/main/java) are indexed; test "
                        + "sources are excluded."));
        props.put("refresh", Map.of(
                "type", "boolean",
                "default", false,
                "description", "If true, evict the cached index for this project and rebuild "
                        + "it. The index cache is process-lifetime and does not detect file "
                        + "changes, so set this after editing source files. Has no effect on "
                        + "the first call for a given project (the cache is empty)."));
        props.put("limit", Map.of(
                "type", "integer",
                "minimum", 1,
                "maximum", FindSymbolsService.MAX_LIMIT,
                "default", FindSymbolsService.DEFAULT_LIMIT,
                "description", "Maximum number of symbols to return. Clamped to "
                        + FindSymbolsService.MAX_LIMIT + ". Large projects can match thousands "
                        + "of symbols on common names; lower this if you only need the first "
                        + "few hits."));
        schema.put("properties", props);
        return schema;
    }

    @Override
    public ToolResult invoke(Map<String, Object> args) throws IOException {
        String query = requiredString(args, "query");
        String kind = optionalString(args, "kind");
        Path projectRoot = resolveProjectRoot(args);
        boolean refresh = optionalBool(args, "refresh");
        int limit = optionalLimit(args);

        try {
            String json = service.findSymbolsJson(query, kind, projectRoot, refresh, limit);
            return ToolResult.text(json);
        } catch (FindSymbolsService.FindSymbolsException e) {
            return ToolResult.error(e.getMessage());
        }
    }

    private static int optionalLimit(Map<String, Object> args) {
        Object v = args.get("limit");
        if (v == null) return FindSymbolsService.DEFAULT_LIMIT;
        int n;
        if (v instanceof Number num) {
            n = num.intValue();
        } else if (v instanceof String s) {
            try {
                n = Integer.parseInt(s.trim());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Optional argument 'limit' must be an integer; got: " + v);
            }
        } else {
            throw new IllegalArgumentException("Optional argument 'limit' must be an integer; got: " + v);
        }
        if (n < 1) {
            throw new IllegalArgumentException("Optional argument 'limit' must be >= 1; got: " + n);
        }
        return n;
    }
}
