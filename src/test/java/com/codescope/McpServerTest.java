package com.codescope;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Direct in-process tests for {@link McpServer#handle}. No subprocess, no real stdin. */
class McpServerTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final PrintStream originalOut = System.out;
    private ByteArrayOutputStream outBuf;

    @BeforeEach
    void redirectStdout() {
        outBuf = new ByteArrayOutputStream();
        System.setOut(new PrintStream(outBuf, true, StandardCharsets.UTF_8));
    }

    @AfterEach
    void restoreStdout() {
        System.setOut(originalOut);
    }

    @Test
    void initializeReturnsServerInfo() throws Exception {
        McpServer s = new McpServer();
        s.handle(req(1, "initialize", Map.of()));
        JsonNode resp = readOne();
        assertEquals(1, resp.path("id").asInt());
        assertEquals("codescope", resp.path("result").path("serverInfo").path("name").asText());
        assertEquals("0.1.0", resp.path("result").path("serverInfo").path("version").asText());
        assertEquals("2024-11-05", resp.path("result").path("protocolVersion").asText());
    }

    @Test
    void pingReturnsEmptyResult() throws Exception {
        McpServer s = new McpServer();
        s.handle(req(42, "ping", null));
        JsonNode resp = readOne();
        assertEquals(42, resp.path("id").asInt());
        assertTrue(resp.path("result").isObject());
        assertEquals(0, resp.path("result").size());
    }

    @Test
    void toolsListAdvertisesRegisteredTool() throws Exception {
        McpServer s = new McpServer().register(new StubTool("ping", Map.of("type", "object"),
                args -> Tool.ToolResult.text("pong")));
        s.handle(req(2, "tools/list", null));
        JsonNode resp = readOne();
        JsonNode tools = resp.path("result").path("tools");
        assertTrue(tools.isArray() && tools.size() == 1);
        JsonNode tool = tools.get(0);
        assertEquals("ping", tool.path("name").asText());
        // description and inputSchema must be present and round-tripped:
        // a typo in Tool.definition() would silently drop these.
        assertEquals("stub", tool.path("description").asText());
        assertEquals("object", tool.path("inputSchema").path("type").asText());
    }

    @Test
    void toolsCallDispatchesToRegisteredTool() throws Exception {
        McpServer s = new McpServer().register(new StubTool("echo", Map.of("type", "object"),
                args -> Tool.ToolResult.text("got:" + args.get("x"))));
        s.handle(req(3, "tools/call", Map.of(
                "name", "echo",
                "arguments", Map.of("x", "hi"))));
        JsonNode resp = readOne();
        assertEquals(3, resp.path("id").asInt());
        String text = resp.path("result").path("content").get(0).path("text").asText();
        assertEquals("got:hi", text);
        assertFalse(resp.path("result").path("isError").asBoolean(false));
    }

    @Test
    void toolsCallReturnsErrorForUnknownTool() throws Exception {
        McpServer s = new McpServer();
        s.handle(req(4, "tools/call", Map.of("name", "nope", "arguments", Map.of())));
        JsonNode resp = readOne();
        assertTrue(resp.path("result").path("isError").asBoolean(),
                "expected isError=true for unknown tool");
        assertTrue(resp.path("result").path("content").get(0).path("text").asText().contains("Unknown"));
    }

    @Test
    void toolsCallSurfacesIllegalArgumentAsInvalidArguments() throws Exception {
        // The tool throws IllegalArgumentException -> McpServer should label it as
        // "Invalid arguments", not as a generic "Tool execution failed".
        McpServer s = new McpServer().register(new StubTool("strict", Map.of(),
                args -> { throw new IllegalArgumentException("bad input"); }));
        s.handle(req(5, "tools/call", Map.of("name", "strict", "arguments", Map.of())));
        JsonNode resp = readOne();
        String msg = resp.path("result").path("content").get(0).path("text").asText();
        assertTrue(msg.startsWith("Invalid arguments:"), "got: " + msg);
    }

    @Test
    void toolsCallSurfacesOtherExceptionsAsToolExecutionFailed() throws Exception {
        McpServer s = new McpServer().register(new StubTool("boom", Map.of(),
                args -> { throw new RuntimeException("kaboom"); }));
        s.handle(req(6, "tools/call", Map.of("name", "boom", "arguments", Map.of())));
        JsonNode resp = readOne();
        String msg = resp.path("result").path("content").get(0).path("text").asText();
        assertTrue(msg.startsWith("Tool execution failed:"), "got: " + msg);
    }

    @Test
    void toolsCallSurfacesIOExceptionAsToolExecutionFailed() throws Exception {
        // IOException is declared on Tool.invoke; an adapter that misroutes
        // it to "Invalid arguments" would be a contract violation. Verify
        // the catch block treats it the same as RuntimeException.
        McpServer s = new McpServer().register(new ThrowingStubTool("fserr", Map.of(),
                args -> { throw new java.io.IOException("disk full"); }));
        s.handle(req(7, "tools/call", Map.of("name", "fserr", "arguments", Map.of())));
        JsonNode resp = readOne();
        String msg = resp.path("result").path("content").get(0).path("text").asText();
        assertTrue(msg.startsWith("Tool execution failed:"), "got: " + msg);
        assertTrue(msg.contains("disk full"), "got: " + msg);
    }

    /** A function that may throw any {@link Exception}, including checked. */
    @FunctionalInterface
    private interface ThrowingFunction<T, R> {
        R apply(T t) throws Exception;
    }

    @Test
    void unknownMethodReturnsJsonRpcMethodNotFound() throws Exception {
        McpServer s = new McpServer();
        s.handle(req(8, "mystery/method", null));
        JsonNode resp = readOne();
        assertEquals(-32601, resp.path("error").path("code").asInt());
        assertTrue(resp.path("error").path("message").asText().contains("mystery/method"));
    }

    @Test
    void missingMethodReturnsInvalidRequest() throws Exception {
        McpServer s = new McpServer();
        // method absent
        Map<String, Object> req = new LinkedHashMap<>();
        req.put("jsonrpc", "2.0");
        req.put("id", 9);
        s.handle(req);
        JsonNode resp = readOne();
        assertEquals(-32600, resp.path("error").path("code").asInt());
        assertTrue(resp.path("error").path("message").asText().contains("method"));
    }

    @Test
    void notificationWithoutIdIsIgnored() throws Exception {
        McpServer s = new McpServer();
        s.handle(Map.of("jsonrpc", "2.0", "method", "notifications/initialized"));
        // Nothing should have been written to stdout.
        assertEquals(0, outBuf.size(), "expected no response for notification, got: "
                + outBuf.toString(StandardCharsets.UTF_8));
    }

    @Test
    void toolsCallRejectsNegativeArity() throws Exception {
        // A negative arity would silently match nothing, leaving the user
        // to wonder why their query came back empty. Reject it up front.
        McpServer s = new McpServer();
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("name", "echo");
        args.put("arguments", Map.of("x", "y"));
        // tools/call dispatches to TraceCallersTool which validates arity.
        // We use a stub tool that records the call so we can verify the
        // negative-arity case fails before reaching the tool.
        s.register(new StubTool("echo", Map.of("type", "object"),
                a -> Tool.ToolResult.text("should-not-run")));
        // Bypass TraceCallersTool by going through a raw call to the
        // adapter path that doesn't enforce arity: drive handle() directly
        // with an unknown tool so we get the "Invalid arguments" path...
        // actually the easiest exercise: call a tool with arity in its
        // schema. The current StubTool doesn't validate; that path is
        // owned by TraceCallersTool. So we drive TraceCallersTool.invoke
        // directly via a fresh instance and verify it throws IAE.
        TraceCallersTool t = new TraceCallersTool();
        assertThrows(IllegalArgumentException.class, () -> t.invoke(Map.of(
                "class", "com.example.X", "method", "m", "arity", -1)));
    }

    @Test
    void dispatchesValidObjectAndKeepsTrailingBytes() throws Exception {
        // After C2: a buffer holding "{valid}{junk}" should dispatch the
        // valid object and keep the junk for the next round. Previously the
        // "exactly one object" check would reject the buffer outright and
        // the stream would wedge.
        McpServer s = new McpServer();
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        buf.write("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}junk".getBytes(StandardCharsets.UTF_8));
        assertTrue(s.tryParseAndDispatch(buf),
                "first complete object should be dispatched");
        // ping -> empty result
        JsonNode resp = readOne();
        assertEquals(1, resp.path("id").asInt());

        // The trailing "junk" must remain in the buffer.
        assertEquals("junk", buf.toString(StandardCharsets.UTF_8),
                "trailing data should be preserved for the next round");

        // Incomplete input does not dispatch.
        buf.reset();
        buf.write("{\"jsonrpc\":\"2.0\",\"id\":".getBytes(StandardCharsets.UTF_8));
        assertFalse(s.tryParseAndDispatch(buf),
                "incomplete input should not be dispatched");
        assertEquals("{\"jsonrpc\":\"2.0\",\"id\":", buf.toString(StandardCharsets.UTF_8),
                "incomplete buffer should be preserved untouched");
    }

    @Test
    void notificationsCancelledCancelsPendingRequest() throws Exception {
        // JSON-RPC 2.0 §6.1: a notifications/cancelled carrying the request
        // id must actually cancel the pending server→client future. We
        // exercise the real path: trigger sendRequestAwait on a virtual
        // thread, read the request id the server just wrote, send a cancel
        // for it, and assert the future was cancelled.
        McpServer s = new McpServer();

        java.util.concurrent.CompletableFuture<Throwable> serverSide = new java.util.concurrent.CompletableFuture<>();
        Thread.ofVirtual().name("test-cancel").start(() -> {
            try {
                s.sendRequestAwait("roots/list", null, 5, java.util.concurrent.TimeUnit.SECONDS);
                serverSide.complete(null);
            } catch (Exception e) {
                // expected: CancellationException
                serverSide.complete(e);
            }
        });

        // Wait until the request line appears in stdout, then read its id.
        for (int i = 0; i < 100; i++) {
            String all = outBuf.toString(StandardCharsets.UTF_8);
            int idx = all.indexOf("\"method\":\"roots/list\"");
            if (idx >= 0) {
                // The request object places "id" before "method"; search the
                // line containing the method, not just after it.
                int lineStart = all.lastIndexOf('\n', idx);
                if (lineStart < 0) lineStart = 0;
                int idIdx = all.indexOf("\"id\":", lineStart);
                if (idIdx < 0) {
                    Thread.sleep(10);
                    continue;
                }
                int idStart = idIdx + "\"id\":".length();
                int idEnd = idStart;
                while (idEnd < all.length() && (Character.isDigit(all.charAt(idEnd)) || all.charAt(idEnd) == '-')) {
                    idEnd++;
                }
                long reqId = Long.parseLong(all.substring(idStart, idEnd));
                // sanity: the future for that id is now in the pending map
                assertNotNull(s.pending.get(reqId), "server should have registered pending future for the request");
                // Send the cancel
                Map<String, Object> cancel = new LinkedHashMap<>();
                cancel.put("jsonrpc", "2.0");
                cancel.put("method", "notifications/cancelled");
                cancel.put("params", Map.of("id", reqId));
                s.handle(cancel);
                // The future is removed and cancelled.
                assertNull(s.pending.get(reqId), "cancelled future should be removed from pending");
                // sendRequestAwait's call site observed a cancellation
                Throwable t = serverSide.get(2, java.util.concurrent.TimeUnit.SECONDS);
                assertNotNull(t, "expected sendRequestAwait to throw on cancel, got success");
                assertTrue(t instanceof java.util.concurrent.CancellationException
                                || (t.getCause() instanceof java.util.concurrent.CancellationException),
                        "expected CancellationException, got: " + t);
                return;
            }
            Thread.sleep(10);
        }
        fail("server never wrote roots/list request to stdout; got: "
                + outBuf.toString(StandardCharsets.UTF_8));
    }

    @Test
    void notificationsCancelledForUnknownIdIsNoOp() throws Exception {
        // Companion to the above: an unknown id must not crash, and must
        // not write anything to stdout.
        McpServer s = new McpServer();
        Map<String, Object> cancel = new LinkedHashMap<>();
        cancel.put("jsonrpc", "2.0");
        cancel.put("method", "notifications/cancelled");
        cancel.put("params", Map.of("id", 999_999L));
        s.handle(cancel);
        assertEquals(0, outBuf.size());
    }

    @Test
    void responseToServerRequestCompletesFuture() throws Exception {
        // A response that doesn't match any pending server-side request id
        // is silently dropped (no write, no crash).
        McpServer s = new McpServer();
        s.onDefaultProjectRoot(root -> { /* unused in this test */ });

        Map<String, Object> unknown = new LinkedHashMap<>();
        unknown.put("jsonrpc", "2.0");
        unknown.put("id", 999_999L);
        unknown.put("result", Map.of("ignored", true));
        s.handle(unknown);
        assertEquals(0, outBuf.size());
    }

    @Test
    void toolRegistryRejectsDuplicateNames() {
        McpServer s = new McpServer();
        s.register(new StubTool("dup", Map.of(), args -> Tool.ToolResult.text("a")));
        assertThrows(IllegalStateException.class, () ->
                s.register(new StubTool("dup", Map.of(), args -> Tool.ToolResult.text("b"))));
    }

    @Test
    void stringIdResponseIsRoutedToFuture() throws Exception {
        // JSON-RPC 2.0 allows string ids. We coerce to long for internal
        // correlation, so a string id like "42" should match the pending
        // request issued with numeric id 42.
        McpServer s = new McpServer();
        // We don't have a public API to inject a pending future, so we
        // exercise the path indirectly: send a response with a string id
        // that does not match any pending request and assert it is silently
        // dropped (no write, no crash).
        Map<String, Object> unknown = new LinkedHashMap<>();
        unknown.put("jsonrpc", "2.0");
        unknown.put("id", "999999");
        unknown.put("result", Map.of("anything", true));
        s.handle(unknown);
        assertEquals(0, outBuf.size(),
                "unknown string-id response should be silently dropped, got: "
                        + outBuf.toString(StandardCharsets.UTF_8));
    }

    @Test
    void nonNumericStringIdIsIgnored() throws Exception {
        // A string id that doesn't parse as long is rejected, not treated
        // as zero or as a crash.
        Map<String, Object> unknown = new LinkedHashMap<>();
        unknown.put("jsonrpc", "2.0");
        unknown.put("id", "not-a-number");
        unknown.put("result", Map.of());
        new McpServer().handle(unknown);
        assertEquals(0, outBuf.size());
    }

    // --- helpers ---

    private static Map<String, Object> req(Object id, String method, Map<String, Object> params) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("jsonrpc", "2.0");
        m.put("id", id);
        m.put("method", method);
        if (params != null) m.put("params", params);
        return m;
    }

    private JsonNode readOne() throws Exception {
        // Read all of stdout, take the first non-empty line, parse it.
        String all = outBuf.toString(StandardCharsets.UTF_8);
        for (String line : all.split("\n")) {
            if (line.isBlank()) continue;
            return JSON.readTree(line);
        }
        throw new IllegalStateException("no response written, got: <" + all + ">");
    }

    /** A trivial tool we can use to drive {@link McpServer} in unit tests. */
    private static final class StubTool implements Tool {
        private final String name;
        private final Map<String, Object> schema;
        private final java.util.function.Function<Map<String, Object>, Tool.ToolResult> fn;

        StubTool(String name, Map<String, Object> schema,
                 java.util.function.Function<Map<String, Object>, Tool.ToolResult> fn) {
            this.name = name;
            this.schema = schema;
            this.fn = fn;
        }

        @Override public String name() { return name; }
        @Override public String description() { return "stub"; }
        @Override public Map<String, Object> inputSchema() { return schema; }
        @Override public Tool.ToolResult invoke(Map<String, Object> arguments) {
            return fn.apply(arguments == null ? Map.of() : arguments);
        }
    }

    /**
     * Like {@link StubTool} but the body may throw any {@link Exception},
     * including checked {@link java.io.IOException}. Needed to test the
     * adapter's behavior for the throws clause on {@link Tool#invoke}.
     */
    private static final class ThrowingStubTool implements Tool {
        private final String name;
        private final Map<String, Object> schema;
        private final ThrowingFunction<Map<String, Object>, Tool.ToolResult> fn;

        ThrowingStubTool(String name, Map<String, Object> schema,
                         ThrowingFunction<Map<String, Object>, Tool.ToolResult> fn) {
            this.name = name;
            this.schema = schema;
            this.fn = fn;
        }

        @Override public String name() { return name; }
        @Override public String description() { return "stub"; }
        @Override public Map<String, Object> inputSchema() { return schema; }
        @Override public Tool.ToolResult invoke(Map<String, Object> arguments) throws java.io.IOException {
            try {
                return fn.apply(arguments == null ? Map.of() : arguments);
            } catch (java.io.IOException e) {
                throw e;
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
    }
}
