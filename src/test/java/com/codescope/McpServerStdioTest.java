package com.codescope;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** End-to-end: launches the fat jar, speaks JSON-RPC 2.0 over stdio, verifies responses. */
class McpServerStdioTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Path FIXTURE = Path.of("src/test/resources/fixture-project");

    @Test
    void handlesInitializeAndToolsCall() throws Exception {
        Process proc = startServer();

        try (BufferedReader out = new BufferedReader(
                new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8));
             OutputStream in = proc.getOutputStream()) {

            // 1) initialize
            send(in, """
                    {"jsonrpc":"2.0","id":1,"method":"initialize","params":{}}
                    """);
            JsonNode initResp = readJson(out);
            assertEquals("2.0", initResp.get("jsonrpc").asText());
            assertEquals(1, initResp.get("id").asInt());
            assertEquals("codescope", initResp.path("result").path("serverInfo").path("name").asText());

            // 2) tools/list
            send(in, """
                    {"jsonrpc":"2.0","id":2,"method":"tools/list"}
                    """);
            JsonNode listResp = readJson(out);
            JsonNode tools = listResp.path("result").path("tools");
            assertTrue(tools.isArray() && tools.size() >= 1, "expected at least one tool");
            JsonNode trace = null;
            for (JsonNode t : tools) {
                if ("trace_callers".equals(t.path("name").asText())) { trace = t; break; }
            }
            assertNotNull(trace, "trace_callers tool not advertised");
            assertTrue(trace.path("inputSchema").path("properties").has("class"));
            assertTrue(trace.path("inputSchema").path("properties").has("method"));
            assertTrue(trace.path("inputSchema").path("properties").has("project"));

            // 3) tools/call -> trace_callers on Target.leaf
            String args = JSON.writeValueAsString(java.util.Map.of(
                    "class", "com.example.Target",
                    "method", "leaf",
                    "project", FIXTURE.toAbsolutePath().toString()));
            String call = String.format(
                    "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\","
                            + "\"params\":{\"name\":\"trace_callers\",\"arguments\":%s}}\n",
                    args);
            send(in, call);
            JsonNode callResp = readJson(out);
            assertEquals(3, callResp.get("id").asInt());
            JsonNode content = callResp.path("result").path("content");
            assertTrue(content.isArray() && content.size() >= 1, "expected content array");
            String text = content.get(0).path("text").asText();

            // The text is the JSON tree we serialized. Parse it and check the chain.
            JsonNode tree = JSON.readTree(text);
            String target = tree.path("target").path("signature").asText();
            assertEquals("com.example.Target#leaf/0", target);

            JsonNode callers = tree.path("target").path("callers");
            assertTrue(callers.isArray() && !callers.isEmpty(), "expected at least one caller");
            assertEquals("com.example.Mid#callsLeaf/0", callers.get(0).path("signature").asText());
            assertEquals("com.example.Top#entryPoint/0",
                    callers.get(0).path("callers").get(0).path("signature").asText());
            assertEquals("com.example.SideBranch#branch/0",
                    callers.get(0).path("callers").get(0).path("callers").get(0).path("signature").asText());
        } finally {
            proc.destroy();
            proc.waitFor(5, TimeUnit.SECONDS);
            if (proc.isAlive()) proc.destroyForcibly();
        }
    }

    @Test
    void returnsErrorForUnknownMethod() throws Exception {
        Process proc = startServer();
        try (BufferedReader out = new BufferedReader(
                new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8));
             OutputStream in = proc.getOutputStream()) {

            send(in, "{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"tools/call\","
                    + "\"params\":{\"name\":\"nope\",\"arguments\":{}}}\n");
            JsonNode resp = readJson(out);
            assertTrue(resp.path("result").path("isError").asBoolean(),
                    "expected isError=true for unknown tool, got " + resp);
        } finally {
            proc.destroy();
            proc.waitFor(5, TimeUnit.SECONDS);
            if (proc.isAlive()) proc.destroyForcibly();
        }
    }

    @Test
    void fetchesRootsWhenClientAdvertisesCapability() throws Exception {
        Process proc = startServer();

        try (BufferedReader out = new BufferedReader(
                new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8));
             OutputStream in = proc.getOutputStream()) {

            // 1) initialize, declaring `roots` capability
            send(in, "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\","
                    + "\"params\":{\"capabilities\":{\"roots\":{}}}}\n");
            System.err.println("[t] sent initialize");
            JsonNode initResp = readJson(out);
            assertEquals("codescope", initResp.path("result").path("serverInfo").path("name").asText());

            // 2) server should follow up with a `roots/list` request — respond with one root
            JsonNode rootsReq = readJson(out);
            assertEquals("roots/list", rootsReq.path("method").asText(),
                    "expected server to call roots/list, got " + rootsReq);
            long rootsReqId = rootsReq.path("id").asLong();
            String rootsResp = "{\"jsonrpc\":\"2.0\",\"id\":" + rootsReqId
                    + ",\"result\":{\"roots\":[{\"uri\":\"file://" + FIXTURE.toAbsolutePath()
                    + "\",\"name\":\"fixture\"}]}}\n";
            send(in, rootsResp);

            // 3) a small grace period for the server to record the default
            Thread.sleep(300);

            // 4) tools/call WITHOUT a `project` arg should now succeed using the root
            String args = JSON.writeValueAsString(java.util.Map.of(
                    "class", "com.example.Target",
                    "method", "leaf"));
            send(in, "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\","
                    + "\"params\":{\"name\":\"trace_callers\",\"arguments\":" + args + "}}\n");
            System.err.println("[t] sent tools/call");
            JsonNode callResp = readJson(out);
            String text = callResp.path("result").path("content").get(0).path("text").asText();
            assertFalse(callResp.path("result").path("isError").asBoolean(),
                    "expected successful call, got error: " + text);
            JsonNode tree = JSON.readTree(text);
            assertEquals("com.example.Target#leaf/0", tree.path("target").path("signature").asText());
            assertTrue(tree.path("target").path("callers").size() >= 1);
        } finally {
            proc.destroy();
            proc.waitFor(5, TimeUnit.SECONDS);
            if (proc.isAlive()) proc.destroyForcibly();
        }
    }

    @Test
    void doesNotFetchRootsWhenClientDoesNotAdvertise() throws Exception {
        Process proc = startServer();
        try (BufferedReader out = new BufferedReader(
                new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8));
             OutputStream in = proc.getOutputStream()) {

            // initialize WITHOUT roots capability
            send(in, "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\","
                    + "\"params\":{\"capabilities\":{}}}\n");
            JsonNode initResp = readJson(out);
            assertEquals("codescope", initResp.path("result").path("serverInfo").path("name").asText());

            // Verify the server didn't send any other message in the meantime:
            // tools/list should be the next thing we get.
            send(in, "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}\n");
            JsonNode listResp = readJson(out);
            assertEquals(2, listResp.path("id").asInt());
            assertTrue(listResp.path("result").path("tools").isArray());
        } finally {
            proc.destroy();
            proc.waitFor(5, TimeUnit.SECONDS);
            if (proc.isAlive()) proc.destroyForcibly();
        }
    }

    private Process startServer() throws Exception {
        // Always launch via the surefire-supplied classpath. It is reliably
        // available (mvn test always populates java.class.path) and exercises
        // the same Main entry point that a packaged jar would.
        String cp = System.getProperty("java.class.path");
        if (cp == null || cp.isEmpty()) {
            throw new IllegalStateException("no classpath available");
        }
        ProcessBuilder pb = new ProcessBuilder("java", "-cp", cp, "com.codescope.Main");
        return pb
                .redirectError(ProcessBuilder.Redirect.PIPE)
                .redirectOutput(ProcessBuilder.Redirect.PIPE)
                .start();
    }

    private static void send(OutputStream in, String json) throws Exception {
        in.write(json.getBytes(StandardCharsets.UTF_8));
        in.flush();
    }

    private static JsonNode readJson(BufferedReader out) throws Exception {
        // Lines until we find a non-empty one that parses as JSON
        String line;
        while ((line = out.readLine()) != null) {
            if (line.isBlank()) continue;
            try {
                return JSON.readTree(line);
            } catch (Exception ignored) {
                // skip non-JSON lines (defensive)
            }
        }
        throw new IllegalStateException("server closed stdout before responding");
    }
}
