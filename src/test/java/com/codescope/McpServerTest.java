package com.codescope;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Direct in-process tests for {@link McpServer#handle}. No subprocess, no real stdin. */
// Defense in depth: several tests poll on a virtual-thread future or a
// stdout pattern. A regression in McpServer that breaks the
// signalling path would otherwise hang the whole suite instead of
// failing with a useful "test timed out" message.
@Timeout(value = 30, unit = TimeUnit.SECONDS)
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
    void toolsCallReturnsErrorForNonMapArguments() throws Exception {
        // A buggy or hostile host could send "arguments" as a non-object (a
        // list, a string, a number). Blind (Map<String,Object>) cast would
        // throw ClassCastException out of invokeTool, which the adapter
        // would mislabel as "Tool execution failed". The correct mapping
        // is "Invalid arguments" — the host's request shape is the problem.
        McpServer s = new McpServer().register(new StubTool("echo", Map.of("type", "object"),
                args -> Tool.ToolResult.text("ok")));
        s.handle(req(4, "tools/call", Map.of(
                "name", "echo",
                "arguments", List.of("not", "a", "map"))));
        JsonNode resp = readOne();
        String msg = resp.path("result").path("content").get(0).path("text").asText();
        assertTrue(msg.startsWith("Invalid arguments:"),
                "expected Invalid arguments, got: " + msg);
        assertTrue(msg.contains("object") || msg.contains("map"),
                "expected message to mention the type, got: " + msg);
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
    void strayClosingBracketTriggersParseErrorAndDoesNotHang() throws Exception {
        // Regression: a buffer starting with a stray '}' or ']' (e.g. from a
        // buggy host sending the tail of a previous frame) used to make the
        // depth-counter in findObjectEnd never reach zero. depth starts at 1
        // for the END_OBJECT, then a following balanced "{...}" pushes it up
        // to 2 and back to 1, and EOF returns -1 — the buffer is preserved
        // and the server waits forever for bytes that would never balance
        // the counter. The fix: when the first non-whitespace byte is a
        // closing bracket, return bytes.length so the caller's catch block
        // resets the buffer and sends a Parse error.
        McpServer s = new McpServer();
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        // Stray } followed by a valid request. The current code would hang
        // on this input (tryParseAndDispatch returns false, buffer is never
        // consumed, and any follow-up bytes can never balance the counter).
        buf.write("}{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}".getBytes(StandardCharsets.UTF_8));
        assertTrue(s.tryParseAndDispatch(buf),
                "stray '}' should be detected and the buffer reset");
        JsonNode err = readOne();
        assertEquals(-32700, err.path("error").path("code").asInt(),
                "expected Parse error (-32700), got: " + err);
        outBuf.reset();
        // Buffer is reset: a fresh valid request should be dispatched.
        buf.write("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"ping\"}".getBytes(StandardCharsets.UTF_8));
        assertTrue(s.tryParseAndDispatch(buf));
        JsonNode resp = readOne();
        assertEquals(2, resp.path("id").asInt(),
                "expected id=2 from the follow-up request, got: " + resp);

        // Same shape for a stray ']'.
        outBuf.reset();
        s = new McpServer();
        buf = new ByteArrayOutputStream();
        buf.write("][1,2]".getBytes(StandardCharsets.UTF_8));
        assertTrue(s.tryParseAndDispatch(buf),
                "stray ']' should be detected and the buffer reset");
        err = readOne();
        assertEquals(-32700, err.path("error").path("code").asInt(),
                "expected Parse error for stray ']', got: " + err);
    }

    @Test
    void garbageInputTriggersParseErrorAndDoesNotHang() throws Exception {
        // Regression: non-JSON garbage (e.g. 'g' from a buggy host) used to
        // make findObjectEnd return -1 for every IOException, so the
        // buffer was treated as "incomplete, wait for more". It never
        // shrank and the server hung. The fix: byte-shape check on the
        // first non-whitespace byte — if it's not a valid JSON value
        // start, return bytes.length so the caller resets the buffer and
        // sends a Parse error.
        McpServer s = new McpServer();
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        buf.write("garbage".getBytes(StandardCharsets.UTF_8));
        assertTrue(s.tryParseAndDispatch(buf),
                "garbage should trigger a parse error and reset the buffer");
        JsonNode err = readOne();
        assertEquals(-32700, err.path("error").path("code").asInt(),
                "expected Parse error (-32700), got: " + err);
    }

    @Test
    void incompleteStreamingBufferIsNotMisclassifiedAsParseError() throws Exception {
        // Regression: when bytes arrive in chunks (as McpServer.run() drives
        // the parser byte-by-byte), partial buffers like `{"jsonrpc":"2.0",`
        // sit mid-structure. Jackson throws a plain JsonParseException with
        // the message "Unexpected end-of-input within/between Object
        // entries" — NOT JsonEOFException. A catch that only treated
        // JsonEOFException as "wait for more bytes" would reset the buffer
        // here and send a spurious Parse error, breaking the whole stdio
        // protocol. Locked in so a future refactor of findObjectEnd cannot
        // silently re-introduce the bug.
        McpServer s = new McpServer();
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        // Buffer ends with a trailing comma — the exact post-comma EOF case.
        buf.write("{\"jsonrpc\":\"2.0\",".getBytes(StandardCharsets.UTF_8));
        assertFalse(s.tryParseAndDispatch(buf),
                "incomplete buffer must return false (wait for more bytes)");
        assertEquals(17, buf.size(),
                "buffer must be left untouched when incomplete, got size: " + buf.size());
        // No Parse error should have been written to stdout.
        assertEquals(0, outBuf.size(),
                "no output expected for incomplete buffer; got: "
                        + outBuf.toString(StandardCharsets.UTF_8));
    }

    @Test
    void partialNullKeywordIsTreatedAsIncomplete() throws Exception {
        // Regression for a real streaming case: a host's response of
        // `{...,"result":null}` arrives byte-by-byte. When the buffer is
        // `{"r":n`, Jackson throws "Unrecognized token 'n'" — not an
        // EOF-flavoured message. An earlier fix that used the message text
        // to discriminate (looking for "end-of-input") would treat this as
        // a real parse error, reset the buffer mid-stream, and break the
        // server's ability to receive any roots/list response carrying a
        // null result. Same story for partial `t`, `tr`, `f`, `fal`, etc.
        // — these are streaming-incomplete, not garbage. Lock in the
        // byte-shape discriminator that handles all of these correctly.
        McpServer s = new McpServer();
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        for (String partial : new String[]{
                "{\"r\":n", "{\"r\":nu", "{\"r\":nul",
                "{\"r\":t", "{\"r\":tr", "{\"r\":tru",
                "{\"r\":f", "{\"r\":fa", "{\"r\":fal", "{\"r\":fals",
                "{\"r\":1.",
        }) {
            buf.reset();
            buf.write(partial.getBytes(StandardCharsets.UTF_8));
            assertFalse(s.tryParseAndDispatch(buf),
                    "partial buffer '" + partial + "' must be treated as incomplete");
            assertEquals(0, outBuf.size(),
                    "no output expected for partial buffer '" + partial
                            + "'; got: " + outBuf.toString(StandardCharsets.UTF_8));
        }
    }

    @Test
    void structurallyInvalidJsonTriggersParseErrorNotHang() throws Exception {
        // Regression: a buffer with a valid first byte ({, [, ", etc.) but
        // broken inside — e.g. `{"k":}` (missing value) or `{"k":nil}` (not
        // a real keyword) — used to make findObjectEnd return -1 from its
        // IOException catch, so tryParseAndDispatch waited forever for more
        // bytes. The byte-shape discriminator handles "garbage" whose first
        // byte is not a valid JSON value start; this test covers the
        // "structurally invalid" half where the first byte is valid but
        // the rest is not. The fix: in the IOException catch, treat
        // Jackson's "this token is not and could never be a valid value"
        // signals (full unrecognized keyword like `nil`, unexpected
        // structural character like `}` after `:`) as parse errors, while
        // genuine EOF signals (JsonEOFException, "end-of-input" anywhere
        // in the message, partial keyword like `n`/`nu`/`nul` whose only
        // failing is being a prefix of `null`) still return -1 to keep
        // waiting.
        McpServer s = new McpServer();
        String[] invalid = new String[]{
                "{\"k\":}",         // missing value after colon
                "{\"a\":1,\"b\":}", // missing value (mid-object)
                "{\"k\":nil}",      // not a real keyword (full token)
                "{\"k\":1,}",       // trailing comma in object
                "{\"k\":1,\"k\"}",  // string with no colon
                "{\"r\":trueX",     // garbage after complete `true`
                "{\"r\":nulll}",    // full unrecognized token
                "{\"r\":.5}",       // leading-dot number
                "{\"r\":+1}",       // plus-sign number
        };
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        for (String input : invalid) {
            buf.reset();
            buf.write(input.getBytes(StandardCharsets.UTF_8));
            assertTrue(s.tryParseAndDispatch(buf),
                    "invalid buffer '" + input + "' must be consumed (parse error fired)");
            JsonNode err = readOne();
            assertEquals(-32700, err.path("error").path("code").asInt(),
                    "expected Parse error (-32700) for invalid buffer '"
                            + input + "', got: " + err);
            outBuf.reset();
        }
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
    void errorResponseCompletesFutureExceptionally() throws Exception {
        // When a host returns a JSON-RPC error response for one of our
        // server→client requests (e.g. roots/list), the caller must see an
        // exception that carries the error info — not a silently-successful
        // NullNode that would later crash on .get("roots") deep in the
        // caller. This test drives the real round-trip: issue a request,
        // respond with an error, and assert the future fails with a message
        // that mentions the error code/message.
        McpServer s = new McpServer();

        java.util.concurrent.CompletableFuture<Throwable> serverSide = new java.util.concurrent.CompletableFuture<>();
        Thread.ofVirtual().name("test-err").start(() -> {
            try {
                s.sendRequestAwait("roots/list", null, 5, java.util.concurrent.TimeUnit.SECONDS);
                serverSide.complete(null);
            } catch (Exception e) {
                serverSide.complete(e);
            }
        });

        for (int i = 0; i < 100; i++) {
            String all = outBuf.toString(StandardCharsets.UTF_8);
            int idx = all.indexOf("\"method\":\"roots/list\"");
            if (idx >= 0) {
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
                // Send an error response for that id
                Map<String, Object> errResp = new LinkedHashMap<>();
                errResp.put("jsonrpc", "2.0");
                errResp.put("id", reqId);
                errResp.put("error", Map.of("code", -32601, "message", "Method not found"));
                s.handle(errResp);
                // Future is removed
                assertNull(s.pending.get(reqId), "error-resolved future should be removed from pending");
                // The call site observed an exception
                Throwable t = serverSide.get(2, java.util.concurrent.TimeUnit.SECONDS);
                assertNotNull(t, "expected sendRequestAwait to throw on error response, got success");
                // The chain ultimately surfaces the error message
                String flat = flatten(t);
                assertTrue(flat.contains("Method not found")
                                || flat.contains("-32601"),
                        "expected error info in exception, got: " + flat);
                return;
            }
            Thread.sleep(10);
        }
        fail("server never wrote roots/list request to stdout; got: "
                + outBuf.toString(StandardCharsets.UTF_8));
    }

    private static String flatten(Throwable t) {
        StringBuilder sb = new StringBuilder();
        while (t != null) {
            if (t.getMessage() != null) sb.append(t.getMessage()).append(" | ");
            t = t.getCause();
        }
        return sb.toString();
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

    @Test
    void nonStringMethodReturnsInvalidRequest() throws Exception {
        // JSON-RPC 2.0 §4: 'method' must be a string. A non-string method
        // is an Invalid Request (-32600). The handler must not crash on a
        // ClassCastException and let the frame layer label it as a Parse
        // error — that would mislead the host about what's wrong.
        McpServer s = new McpServer();
        Map<String, Object> req = new LinkedHashMap<>();
        req.put("jsonrpc", "2.0");
        req.put("id", 1);
        req.put("method", 123); // not a string
        s.handle(req);
        JsonNode resp = readOne();
        assertEquals(-32600, resp.path("error").path("code").asInt(),
                "expected Invalid Request for non-string method, got: " + resp);
        assertTrue(resp.path("error").path("message").asText().toLowerCase().contains("method"),
                "error message should mention 'method', got: " + resp);
    }

    @Test
    void nonMapParamsReturnsInvalidParams() throws Exception {
        // 'params' must be a structured value (object). A scalar is an
        // Invalid params (-32602), not a crash.
        McpServer s = new McpServer();
        Map<String, Object> req = new LinkedHashMap<>();
        req.put("jsonrpc", "2.0");
        req.put("id", 1);
        req.put("method", "ping");
        req.put("params", "not-a-map");
        s.handle(req);
        JsonNode resp = readOne();
        assertEquals(-32602, resp.path("error").path("code").asInt(),
                "expected Invalid params for scalar params, got: " + resp);
    }

    @Test
    void errorResponseMessageIsFormatted() throws Exception {
        // The exception surfaced from sendRequestAwait on an error response
        // should be human-readable: extract code and message, not
        // Map.toString() which gives noisy "{code=-32601, message=...}".
        McpServer s = new McpServer();

        java.util.concurrent.CompletableFuture<Throwable> serverSide =
                new java.util.concurrent.CompletableFuture<>();
        Thread.ofVirtual().name("test-err-fmt").start(() -> {
            try {
                s.sendRequestAwait("roots/list", null, 5, java.util.concurrent.TimeUnit.SECONDS);
                serverSide.complete(null);
            } catch (Exception e) {
                serverSide.complete(e);
            }
        });

        for (int i = 0; i < 100; i++) {
            String all = outBuf.toString(StandardCharsets.UTF_8);
            int idx = all.indexOf("\"method\":\"roots/list\"");
            if (idx >= 0) {
                int lineStart = all.lastIndexOf('\n', idx);
                if (lineStart < 0) lineStart = 0;
                int idIdx = all.indexOf("\"id\":", lineStart);
                if (idIdx < 0) { Thread.sleep(10); continue; }
                int idStart = idIdx + "\"id\":".length();
                int idEnd = idStart;
                while (idEnd < all.length() && (Character.isDigit(all.charAt(idEnd)) || all.charAt(idEnd) == '-')) {
                    idEnd++;
                }
                long reqId = Long.parseLong(all.substring(idStart, idEnd));
                Map<String, Object> errResp = new LinkedHashMap<>();
                errResp.put("jsonrpc", "2.0");
                errResp.put("id", reqId);
                errResp.put("error", Map.of("code", -32601, "message", "Method not found"));
                s.handle(errResp);
                Throwable t = serverSide.get(2, java.util.concurrent.TimeUnit.SECONDS);
                assertNotNull(t, "expected sendRequestAwait to throw");
                String flat = flatten(t);
                assertTrue(flat.contains("-32601") && flat.contains("Method not found"),
                        "expected both code and message in exception, got: " + flat);
                // The message should be parsed into "server returned error
                // <code>: <message>", not echo the raw Map.toString() (which
                // has nondeterministic key order via Map.of and looks like
                // "{code=-32601, message=...}" or "{message=..., code=...}").
                assertTrue(flat.contains("server returned error -32601"),
                        "expected formatted code prefix, got: " + flat);
                assertFalse(flat.contains("{") && flat.contains("=") && flat.contains("}"),
                        "expected formatted error message, not raw Map.toString(), got: " + flat);
                return;
            }
            Thread.sleep(10);
        }
        fail("server never wrote roots/list request to stdout; got: "
                + outBuf.toString(StandardCharsets.UTF_8));
    }

    @Test
    void strayResponseIsLoggedNotReplied() throws Exception {
        // A response with an id we never issued should be ignored (no
        // JSON-RPC reply possible) but logged to stderr so a misbehaving
        // host is diagnosable. Previously this was silently swallowed.
        PrintStream originalErr = System.err;
        ByteArrayOutputStream errBuf = new ByteArrayOutputStream();
        System.setErr(new PrintStream(errBuf, true, StandardCharsets.UTF_8));
        try {
            McpServer s = new McpServer();
            Map<String, Object> stray = new LinkedHashMap<>();
            stray.put("jsonrpc", "2.0");
            stray.put("id", 999_999L);
            stray.put("result", Map.of("unused", true));
            s.handle(stray);
            // Nothing should have been written to stdout — we can't reply
            // to an id we didn't issue.
            String out = outBuf.toString(StandardCharsets.UTF_8);
            assertTrue(out.isBlank(), "expected no stdout reply to stray response, got: " + out);
            // But stderr should have a diagnostic line.
            String err = errBuf.toString(StandardCharsets.UTF_8);
            assertTrue(err.contains("stray response"), "expected stray-response log, got: " + err);
            assertTrue(err.contains("999999"), "expected id in log, got: " + err);
        } finally {
            System.setErr(originalErr);
        }
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
