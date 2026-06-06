package com.codescope;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
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

    private static final int STDIN_CHUNK_SIZE = 4096;
    private static final long REQUEST_ID_BASE = 1_000_000L;  // avoids collision with client-side small ids
    private static final int ROOTS_FETCH_TIMEOUT_SECONDS = 2;

    private final ObjectMapper json = new ObjectMapper();
    private final ToolRegistry tools = new ToolRegistry();
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final Object stdoutLock = new Object();

    private final AtomicLong nextId = new AtomicLong(REQUEST_ID_BASE);
    // package-private so unit tests can drive server→client round-trips.
    final Map<Long, CompletableFuture<JsonNode>> pending = new ConcurrentHashMap<>();

    /** Notified (off the I/O thread) with the first workspace root the host advertises. */
    private volatile Consumer<String> defaultProjectRootSink;

    /**
     * Wrapper over System.in whose {@link #close()} only sets a flag — we
     * never close the underlying System.in because that would permanently
     * break stdio for the rest of the JVM. The flag is checked by
     * {@link #run()} to exit the read loop on {@link #stop()}.
     */
    private final CloseableInputStream stdin = new CloseableInputStream(System.in);

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
        InputStream in = stdin;
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] chunk = new byte[STDIN_CHUNK_SIZE];
        int n;
        while (running.get() && (n = in.read(chunk)) != -1) {
            for (int i = 0; i < n; i++) {
                byte b = chunk[i];
                // Skip pure framing whitespace. Per RFC 8259, raw control
                // characters (including \r and \n) MUST NOT appear inside
                // JSON strings — they have to be escaped as \r / \n. So
                // stripping them at the byte level is safe against
                // spec-compliant hosts and never corrupts string values.
                if (b == (byte) '\n' || b == (byte) '\r') continue;
                buf.write(b);
                // tryParseAndDispatch owns buf on success: it removes the
                // consumed bytes (preserving any trailing data) or resets
                // the whole buffer on parse error.
                tryParseAndDispatch(buf);
            }
        }
    }

    /**
     * Try to consume the first complete JSON object from {@code buf}.
     * On success, dispatches it, removes only the consumed bytes (preserving
     * any trailing data), and returns {@code true}. On incomplete input,
     * returns {@code false} and leaves the buffer untouched. On a parse
     * error, replies once with id=null and resets the whole buffer.
     *
     * <p>Robust against trailing noise: if the buffer holds
     * {@code {valid}{junk}}, the first object is dispatched and the junk
     * stays in the buffer for the next round.
     */
    /** @return true if a complete object was consumed from {@code buf}. */
    boolean tryParseAndDispatch(ByteArrayOutputStream buf) {
        if (buf.size() == 0) return false;
        byte[] bytes = buf.toByteArray();
        int consumed = findObjectEnd(bytes);
        if (consumed < 0) return false;   // incomplete — keep reading
        try {
            byte[] frame = new byte[consumed];
            System.arraycopy(bytes, 0, frame, 0, consumed);
            @SuppressWarnings("unchecked")
            Map<String, Object> msg = json.readValue(frame, Map.class);
            // Drop the consumed bytes; keep any trailing data for the next
            // round by copying it back to the start of the buffer.
            int tailLen = bytes.length - consumed;
            if (tailLen == 0) {
                buf.reset();
            } else {
                byte[] tail = new byte[tailLen];
                System.arraycopy(bytes, consumed, tail, 0, tailLen);
                buf.reset();
                buf.write(tail, 0, tailLen);
            }
            handle(msg);
            return true;
        } catch (Exception e) {
            // Malformed object: reply with id=null and drop the bad bytes.
            // There's no reliable way to find the next valid object after
            // a parse error, so clear the buffer rather than risk looping.
            sendError(null, -32700, "Parse error: " + e.getMessage());
            buf.reset();
            return true;
        }
    }

    /**
     * Returns the number of bytes in {@code bytes} that form exactly one
     * complete top-level JSON value (object, array, or scalar), or {@code -1}
     * if the buffer doesn't yet contain a complete value.
     */
    private int findObjectEnd(byte[] bytes) {
        try (JsonParser p = json.getFactory().createParser(bytes)) {
            JsonToken first = p.nextToken();
            if (first == null) return -1;
            if (first.isScalarValue()) {
                return bytes.length;
            }
            int depth = 1;
            while (depth > 0) {
                JsonToken t = p.nextToken();
                if (t == null) return -1;
                if (t == JsonToken.START_OBJECT || t == JsonToken.START_ARRAY) depth++;
                else if (t == JsonToken.END_OBJECT || t == JsonToken.END_ARRAY) depth--;
            }
            // For byte-array parsers, Jackson's getByteOffset() can return
            // -1; in that case the only safe assumption is to consume the
            // whole buffer (next round will see what's left, if anything).
            long off = p.currentLocation().getByteOffset();
            return off < 0 ? bytes.length : (int) off;
        } catch (IOException e) {
            // An IOException here means Jackson hit the end of the buffer
            // mid-structure (JsonEOFException). That's the "incomplete"
            // case — we'll see more bytes next round.
            return -1;
        }
    }

    public void stop() {
        running.set(false);
        stdin.close();
        // Cancel any in-flight server→client requests so the virtual threads
        // waiting on them exit promptly instead of running out the timeout.
        // Each sendRequestAwait's finally block will still remove its entry
        // from `pending`; the cancel() here just unblocks the .get().
        for (CompletableFuture<JsonNode> f : pending.values()) {
            f.cancel(true);
        }
    }

    // package-private for direct unit tests; not part of the public API.
    @SuppressWarnings("unchecked")
    void handle(Map<String, Object> msg) throws IOException {
        // Response to a server-initiated request?
        if (msg.containsKey("result") || msg.containsKey("error")) {
            Long responseId = coerceId(msg.get("id"));
            if (responseId != null) {
                CompletableFuture<JsonNode> fut = pending.remove(responseId);
                if (fut != null) {
                    if (msg.containsKey("error")) {
                        // An error response must surface to the caller as
                        // an exceptional completion. Completing normally
                        // with a NullNode would let the caller think the
                        // request succeeded and then crash on
                        // .get("expectedField") deep in its own code.
                        fut.completeExceptionally(new RuntimeException(
                                formatErrorResponse(msg.get("error"))));
                    } else {
                        fut.complete(json.valueToTree(msg.get("result")));
                    }
                    return;
                }
            }
            return;     // unknown response, ignore
        }

        Object id = msg.get("id");
        // Use safe type narrowing rather than blind casts: JSON-RPC §4
        // requires "method" to be a string and §4.2 requires "params" to be
        // a structured value. A blind (String)/(Map) cast on a malformed
        // host message would throw ClassCastException out of handle(),
        // which the frame layer would then mislabel as Parse error
        // (-32700). The correct mapping is Invalid Request / Invalid params.
        Object methodObj = msg.get("method");
        String method = (methodObj instanceof String s) ? s : null;
        Object paramsObj = msg.get("params");
        Map<String, Object> params;
        if (paramsObj == null) {
            params = null;
        } else if (paramsObj instanceof Map<?, ?> m) {
            params = (Map<String, Object>) m;
        } else {
            // Type-broken params: -32602 for requests, silent drop for
            // notifications (the host has no way to receive an error
            // response for a notification anyway — id is missing).
            if (id != null) sendError(id, -32602, "Invalid params: must be an object");
            return;
        }

        if (id == null) {
            if ("notifications/cancelled".equals(method)) {
                Long cancelledId = coerceId(params == null ? null : params.get("id"));
                if (cancelledId != null) {
                    CompletableFuture<JsonNode> fut = pending.remove(cancelledId);
                    if (fut != null) fut.cancel(true);
                }
                return;
            }
            if ("notifications/initialized".equals(method)) {
                return;
            }
            return;
        }

        if (methodObj != null && method == null) {
            // Non-string method: §4 violation. Distinct from missing
            // method, which is also -32600 but with a different message.
            sendError(id, -32600, "Invalid request: 'method' must be a string");
            return;
        }
        if (method == null) {
            sendError(id, -32600, "Invalid request: missing 'method'");
            return;
        }

        try {
            switch (method) {
                case "initialize" -> {
                    respond(id, initializeResult());
                    if (clientHasRootsCapability(params)) {
                        // Virtual thread: this is a one-shot blocking call that
                        // spends most of its time waiting on a stdio response
                        // (or the timeout), so a platform thread would be
                        // overkill. Java 21+ virtual threads are the right fit.
                        Thread.ofVirtual().name("fetch-roots").start(this::tryFetchRoots);
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

    /**
     * Formats a JSON-RPC 2.0 error object for inclusion in an exception
     * message. Extracts {@code code} and {@code message} fields when
     * present; falls back to the raw value otherwise. The goal is human
     * readability — {@code Map.toString()} produces
     * {@code {code=X, message=Y}} with nondeterministic key order, which
     * makes stack traces harder to read and grep.
     */
    private static String formatErrorResponse(Object errorObj) {
        if (errorObj instanceof Map<?, ?> em) {
            Object code = em.get("code");
            Object message = em.get("message");
            if (code != null && message != null) {
                return "server returned error " + code + ": " + message;
            }
            if (message != null) {
                return "server returned error: " + message;
            }
        }
        return "server returned error: " + errorObj;
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
            JsonNode result = sendRequestAwait("roots/list", null,
                    ROOTS_FETCH_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            JsonNode roots = result.get("roots");
            if (roots == null || !roots.isArray() || roots.isEmpty()) return;
            String uri = roots.get(0).path("uri").asText(null);
            if (uri == null || uri.isBlank() || !uri.startsWith("file://")) return;
            String path = java.net.URI.create(uri).getPath();
            Consumer<String> sink = defaultProjectRootSink;
            if (sink != null) sink.accept(path);
        } catch (Exception e) {
            // Most common cases: client doesn't support roots, response timed
            // out, or the first root isn't a file:// URI. All are normal for
            // a non-roots host; log to stderr so a future "why didn't the
            // default project work?" question has an answer.
            System.err.println("[codescope] roots/list failed: " + e);
            // client doesn't support / timed out / malformed -> no default
        }
    }

    /** Send a request and synchronously wait for the response. */
    JsonNode sendRequestAwait(String method, Object params, long amount, TimeUnit unit)
            throws IOException, InterruptedException, ExecutionException, TimeoutException {
        long id = nextId.getAndIncrement();
        Map<String, Object> req = new LinkedHashMap<>();
        req.put("jsonrpc", "2.0");
        req.put("id", id);
        req.put("method", method);
        if (params != null) req.put("params", params);

        // Register the future BEFORE writing the request. A host that
        // responds very fast (e.g. local loopback) could otherwise have its
        // response arrive on the I/O thread between writeLine() and
        // pending.put(), in which case the I/O thread's pending.remove(id)
        // would return null and the response would be silently dropped.
        CompletableFuture<JsonNode> fut = new CompletableFuture<>();
        pending.put(id, fut);
        try {
            writeLine(json.writeValueAsBytes(req));
            return fut.get(amount, unit);
        } finally {
            // Clean up unconditionally: a throw from writeLine (broken pipe)
            // would otherwise leak the future in `pending` until process exit.
            pending.remove(id);
        }
    }

    /**
     * JSON-RPC 2.0 allows {@code id} to be a Number, String, or null. Our
     * request/response correlation only uses numeric ids internally, so we
     * coerce strings via {@link Long#parseLong} and reject anything else.
     * Returns null if the value cannot be interpreted as a long.
     */
    private static Long coerceId(Object idObj) {
        if (idObj instanceof Number n) return n.longValue();
        if (idObj instanceof String s) {
            try { return Long.parseLong(s); }
            catch (NumberFormatException ignored) { return null; }
        }
        return null;
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
        if (params == null) {
            return toolErrorResult("Missing params");
        }
        Object nameObj = params.get("name");
        if (!(nameObj instanceof String name)) {
            return toolErrorResult("Missing or non-string 'name'");
        }
        Map<String, Object> args = (Map<String, Object>) params.get("arguments");
        Tool t = tools.get(name);
        if (t == null) {
            return toolErrorResult("Unknown tool: " + name);
        }
        try {
            return wrap(t.invoke(args == null ? Map.of() : args));
        } catch (IllegalArgumentException e) {
            return toolErrorResult("Invalid arguments: " + e.getMessage());
        } catch (RuntimeException | IOException e) {
            // Tool contract: implementations translate user-facing failures
            // into ToolResult.error. Anything reaching here is a real bug —
            // surface it as "Tool execution failed".
            return toolErrorResult("Tool execution failed: " + e.getMessage());
        }
    }

    /** Wraps a tool result in the {@code tools/call} response shape. */
    private static Map<String, Object> wrap(Tool.ToolResult r) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("content", r.content());
        if (r.isError()) out.put("isError", true);
        return out;
    }

    private static Map<String, Object> toolErrorResult(String message) {
        return wrap(Tool.ToolResult.error(message));
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
        } catch (IOException e) {
            // A misconfigured pipe (broken parent process, redirected
            // stdout to a non-writable file, etc.) leaves us with nowhere
            // to send the JSON-RPC error. Stderr is the only remaining
            // channel — log there so the failure is visible at all.
            System.err.println("[codescope] failed to write error response: " + e);
        }
    }

    private void writeLine(byte[] payload) throws IOException {
        synchronized (stdoutLock) {
            System.out.write(payload);
            System.out.write('\n');
            System.out.flush();
        }
    }

    static final class ToolRegistry {
        private final Map<String, Tool> byName = new LinkedHashMap<>();

        void register(Tool t) {
            Tool prev = byName.putIfAbsent(t.name(), t);
            if (prev != null) {
                throw new IllegalStateException("Duplicate tool name: " + t.name());
            }
        }

        Tool get(String name) { return byName.get(name); }

        List<Map<String, Object>> list() {
            List<Map<String, Object>> out = new ArrayList<>(byName.size());
            for (Tool t : byName.values()) out.add(t.definition());
            return out;
        }
    }

    /**
     * InputStream wrapper that lets {@link McpServer#stop()} unblock the
     * read loop without permanently closing the JVM's stdin.
     */
    private static final class CloseableInputStream extends InputStream {
        private final InputStream delegate;
        private volatile boolean closed = false;

        CloseableInputStream(InputStream delegate) { this.delegate = delegate; }

        @Override public int read() throws IOException { ensureOpen(); return delegate.read(); }
        @Override public int read(byte[] b, int off, int len) throws IOException {
            ensureOpen();
            return delegate.read(b, off, len);
        }
        @Override public int available() throws IOException {
            ensureOpen();
            return delegate.available();
        }
        @Override public void close() { closed = true; }

        private void ensureOpen() throws IOException {
            if (closed) throw new IOException("stdin closed");
        }
    }
}
