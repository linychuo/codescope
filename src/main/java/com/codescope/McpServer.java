package com.codescope;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Minimal MCP (Model Context Protocol) server over stdio. Speaks JSON-RPC 2.0.
 *
 * <p>Features:
 * <ul>
 *   <li>Lenient framing: tolerates missing newlines, multiple JSON objects
 *       per line, and JSON split across reads.</li>
 *   <li>Outgoing requests: supports server→client calls (e.g. {@code roots/list}
 *       to fetch the host's workspace roots after {@code initialize}).</li>
 * </ul>
 */
public final class McpServer {

    private final ObjectMapper json = new ObjectMapper();
    private final ToolRegistry tools = new ToolRegistry();
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final Object stdoutLock = new Object();

    private final AtomicLong nextId = new AtomicLong(1_000_000);     // avoids collision with client-side small ids
    private final Map<Long, CompletableFuture<JsonNode>> pending = new ConcurrentHashMap<>();

    /** Notified (off the I/O thread) with the first workspace root the host advertises. */
    private volatile Consumer<String> defaultProjectRootSink;

    public McpServer register(Tool tool) {
        tools.register(tool);
        return this;
    }

    /**
     * Register a callback for the host's first advertised workspace root. Fires at
     * most once, asynchronously after {@code initialize}, and only if the client
     * advertises the {@code roots} capability.
     */
    public McpServer onDefaultProjectRoot(Consumer<String> sink) {
        this.defaultProjectRootSink = sink;
        return this;
    }

    public void run() throws IOException {
        InputStream in = System.in;
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] chunk = new byte[4096];
        int n;
        while (running.get() && (n = in.read(chunk)) != -1) {
            for (int i = 0; i < n; i++) {
                byte b = chunk[i];
                if (b == (byte) '\n' || b == (byte) '\r') continue;
                buf.write(b);
                if (tryParseAndDispatch(buf)) buf.reset();
            }
        }
    }

    private boolean tryParseAndDispatch(ByteArrayOutputStream buf) {
        if (buf.size() == 0) return false;
        byte[] bytes = buf.toByteArray();
        try (JsonParser p = json.getFactory().createParser(bytes)) {
            p.nextToken();
            if (p.currentToken() == null) return false;
            p.skipChildren();
            p.nextToken();
            if (p.currentToken() != null) return false;
        } catch (IOException e) {
            return false;
        }
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
        // Response to a server-initiated request?
        if (msg.containsKey("result") || msg.containsKey("error")) {
            Object idObj = msg.get("id");
            if (idObj instanceof Number n) {
                CompletableFuture<JsonNode> fut = pending.remove(n.longValue());
                if (fut != null) {
                    fut.complete(json.valueToTree(msg.get("result")));
                    return;
                }
            }
            return;     // unknown response, ignore
        }

        Object id = msg.get("id");
        String method = (String) msg.get("method");
        Map<String, Object> params = (Map<String, Object>) msg.get("params");

        if (id == null) {
            // notification: handle known ones, ignore the rest
            if ("notifications/initialized".equals(method)
                    || "notifications/cancelled".equals(method)) {
                return;
            }
            return;
        }

        try {
            switch (method) {
                case "initialize" -> {
                    respond(id, initializeResult());
                    if (clientHasRootsCapability(params)) {
                        new Thread(this::tryFetchRoots, "fetch-roots").start();
                    }
                }
                case "ping"       -> respond(id, Map.of());
                case "tools/list" -> respond(id, Map.of("tools", tools.list()));
                case "tools/call" -> respond(id, invokeTool(params));
                default           -> sendError(id, -32601, "Method not found: " + method);
            }
        } catch (Exception e) {
            sendError(id, -32603, "Internal error: " + e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private static boolean clientHasRootsCapability(Map<String, Object> initParams) {
        if (initParams == null) return false;
        Object caps = initParams.get("capabilities");
        if (!(caps instanceof Map<?, ?> m)) return false;
        return m.containsKey("roots");
    }

    private void tryFetchRoots() {
        try {
            JsonNode result = sendRequestAwait("roots/list", null, 2, TimeUnit.SECONDS);
            JsonNode roots = result.get("roots");
            if (roots == null || !roots.isArray() || roots.isEmpty()) return;
            String uri = roots.get(0).path("uri").asText(null);
            if (uri == null || uri.isBlank() || !uri.startsWith("file://")) return;
            String path = java.net.URI.create(uri).getPath();
            Consumer<String> sink = defaultProjectRootSink;
            if (sink != null) sink.accept(path);
        } catch (Exception ignored) {
            // client doesn't support / timed out / malformed -> no default
        }
    }

    /** Send a request and synchronously wait for the response. */
    private JsonNode sendRequestAwait(String method, Object params, long amount, TimeUnit unit)
            throws IOException, InterruptedException, ExecutionException, TimeoutException {
        long id = nextId.getAndIncrement();
        Map<String, Object> req = new LinkedHashMap<>();
        req.put("jsonrpc", "2.0");
        req.put("id", id);
        req.put("method", method);
        if (params != null) req.put("params", params);
        writeLine(json.writeValueAsBytes(req));

        CompletableFuture<JsonNode> fut = new CompletableFuture<>();
        pending.put(id, fut);
        try {
            return fut.get(amount, unit);
        } finally {
            pending.remove(id);
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
        writeLine(json.writeValueAsBytes(resp));
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
            writeLine(json.writeValueAsBytes(resp));
        } catch (IOException ignored) { }
    }

    private void writeLine(byte[] payload) throws IOException {
        synchronized (stdoutLock) {
            System.out.write(payload);
            System.out.write('\n');
            System.out.flush();
        }
    }

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
