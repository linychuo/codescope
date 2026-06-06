package com.codescope;

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

    ToolResult invoke(Map<String, Object> arguments) throws Exception;

    record ToolResult(String text, List<String> errors) {
        public static ToolResult ok(String text) { return new ToolResult(text, List.of()); }
        public static ToolResult err(String message) { return new ToolResult(message, List.of(message)); }
    }
}
