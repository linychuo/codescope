package com.codescope;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/** Minimal MCP (Model Context Protocol) server over stdio. Speaks JSON-RPC 2.0. */
public final class McpServer {

    private final ObjectMapper json = new ObjectMapper();
    private final ToolRegistry tools = new ToolRegistry();
    private final AtomicBoolean running = new AtomicBoolean(true);

    public McpServer register(Tool tool) {
        tools.register(tool);
        return this;
    }

    public void run() throws IOException {
        InputStream in = System.in;
        // Accumulate bytes; peel off complete JSON objects and dispatch them.
        // Tolerant of inputs that omit newlines, send multiple objects per "line",
        // or split a single object across reads.
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] chunk = new byte[4096];
        int n;
        while (running.get() && (n = in.read(chunk)) != -1) {
            for (int i = 0; i < n; i++) {
                byte b = chunk[i];
                if (b == (byte) '\n' || b == (byte) '\r') continue;     // ignore line separators
                buf.write(b);
                if (tryParseAndDispatch(buf)) buf.reset();
            }
        }
    }

    /** Try to parse the current buffer as one JSON object. If it parses, dispatch and return true. */
    private boolean tryParseAndDispatch(ByteArrayOutputStream buf) {
        if (buf.size() == 0) return false;
        byte[] bytes = buf.toByteArray();
        try (JsonParser p = json.getFactory().createParser(bytes)) {
            p.nextToken();
            // we expect an object
            if (p.currentToken() == null) return false;
            // consume the rest of the object
            p.skipChildren();
            p.nextToken();
            if (p.currentToken() != null) return false;     // more tokens, not a single object
        } catch (IOException e) {
            return false;   // need more bytes
        }
        // got a single complete object — parse and dispatch
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> msg = json.readValue(bytes, Map.class);
            handle(msg);
            return true;
        } catch (Exception e) {
            sendError(null, -32700, "Parse error: " + e.getMessage());
            return true;
        }
    }

    public void stop() { running.set(false); }

    @SuppressWarnings("unchecked")
    private void handle(Map<String, Object> msg) throws IOException {
        Object id = msg.get("id");
        String method = (String) msg.get("method");
        Map<String, Object> params = (Map<String, Object>) msg.get("params");

        if (id == null) {
            // notification: handle known ones, ignore the rest, never respond
            if ("notifications/initialized".equals(method)
                    || "notifications/cancelled".equals(method)) {
                return;
            }
            return;
        }

        try {
            switch (method) {
                case "initialize" -> respond(id, initializeResult());
                case "ping"       -> respond(id, Map.of());
                case "tools/list" -> respond(id, Map.of("tools", tools.list()));
                case "tools/call" -> respond(id, invokeTool(params));
                default           -> sendError(id, -32601, "Method not found: " + method);
            }
        } catch (Exception e) {
            sendError(id, -32603, "Internal error: " + e.getMessage());
        }
    }

    private Map<String, Object> initializeResult() {
        Map<String, Object> caps = new LinkedHashMap<>();
        caps.put("tools", Map.of());
        Map<String, Object> serverInfo = new LinkedHashMap<>();
        serverInfo.put("name", "codescope");
        serverInfo.put("version", "0.1.0");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("protocolVersion", "2024-11-05");
        result.put("capabilities", caps);
        result.put("serverInfo", serverInfo);
        return result;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> invokeTool(Map<String, Object> params) {
        String name = (String) params.get("name");
        Map<String, Object> args = (Map<String, Object>) params.get("arguments");
        Tool t = tools.get(name);
        if (t == null) {
            return toolError("Unknown tool: " + name);
        }
        try {
            Tool.ToolResult r = t.invoke(args == null ? Map.of() : args);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("content", List.of(Map.of("type", "text", "text", r.text())));
            if (!r.errors().isEmpty()) {
                out.put("isError", true);
            }
            return out;
        } catch (Exception e) {
            return toolError("Tool execution failed: " + e.getMessage());
        }
    }

    private static Map<String, Object> toolError(String message) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("isError", true);
        out.put("content", List.of(Map.of("type", "text", "text", message)));
        return out;
    }

    private void respond(Object id, Object result) throws IOException {
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("jsonrpc", "2.0");
        resp.put("id", id);
        resp.put("result", result);
        System.out.write(json.writeValueAsBytes(resp));
        System.out.write('\n');
        System.out.flush();
    }

    private void sendError(Object id, int code, String message) {
        try {
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("code", code);
            err.put("message", message);
            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("jsonrpc", "2.0");
            if (id != null) resp.put("id", id);
            resp.put("error", err);
            System.out.write(json.writeValueAsBytes(resp));
            System.out.write('\n');
            System.out.flush();
        } catch (IOException ignored) { /* stdio is gone, nothing we can do */ }
    }

    /** Simple tool registry. */
    static final class ToolRegistry {
        private final List<Tool> tools = new ArrayList<>();
        void register(Tool t) { tools.add(t); }
        Tool get(String name) { return tools.stream().filter(t -> t.name().equals(name)).findFirst().orElse(null); }
        List<Map<String, Object>> list() {
            List<Map<String, Object>> out = new ArrayList<>();
            for (Tool t : tools) out.add(t.definition());
            return out;
        }
    }
}
