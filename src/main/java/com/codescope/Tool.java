package com.codescope;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** A single MCP tool. Each implementation declares its name, JSON Schema, and invoke. */
public interface Tool {

    String name();
    String description();
    Map<String, Object> inputSchema();

    default Map<String, Object> definition() {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("name", name());
        d.put("description", description());
        d.put("inputSchema", inputSchema());
        return d;
    }

    /**
     * Implementations translate any failure into a {@link ToolResult#error} and
     * only let {@link IOException} escape for genuine I/O problems the
     * adapter layer can't recover from. The adapter relies on the
     * IllegalArgumentException-vs-Exception split to label errors as
     * "Invalid arguments" vs "Tool execution failed".
     */
    ToolResult invoke(Map<String, Object> arguments) throws IOException;

    /**
     * Result of a tool invocation, in MCP {@code tools/call} content-block
     * shape. The list may contain any number of blocks (text, image, etc.);
     * most tools just produce a single text block holding a JSON document.
     */
    record ToolResult(List<Map<String, Object>> content, boolean isError) {
        public static ToolResult text(String text) {
            return new ToolResult(List.of(Map.of("type", "text", "text", text)), false);
        }
        public static ToolResult error(String message) {
            return new ToolResult(List.of(Map.of("type", "text", "text", message)), true);
        }
    }
}
