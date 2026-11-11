package com.codescope.service;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * Direct unit tests for {@link TraceCalleesService} — the downward mirror
 * of {@link TraceCallersService} added for issue #9 item 2.
 */
class TraceCalleesServiceTest {

    private static final Path FIXTURE = Path.of("src/test/resources/fixture-project");

    private static JsonNode call(String cls, String method, int maxDepth) throws Exception {
        TraceCalleesService svc = new TraceCalleesService();
        return new ObjectMapper().readTree(svc.traceCalleesJson(
                cls, method, null, null, FIXTURE, false, false, maxDepth));
    }

    @Test
    void simpleChainIsExpandedDownward() throws Exception {
        // Top.entryPoint -> Mid.callsLeaf -> Target.leaf
        JsonNode tree = call("com.example.Top", "entryPoint",
                com.codescope.analysis.CalleeChainAnalyzer.DEFAULT_MAX_DEPTH);
        assertEquals("ok", tree.path("status").asText());
        JsonNode root = tree.path("target");
        assertEquals("com.example.Top#entryPoint/0", root.path("signature").asText());
        assertTrue(root.has("callees"), "callee tree must use the `callees` key, got: " + root);
        assertEquals(1, root.path("callees").size(), "direct callees: " + root);
        JsonNode mid = root.path("callees").get(0);
        assertEquals("com.example.Mid", mid.path("class").asText());
        assertEquals("callsLeaf", mid.path("method").asText());
        // Issue #9: snippets ride along in the downward tree too.
        assertEquals("    public void callsLeaf() {", mid.path("snippet").asText());
        assertEquals(1, mid.path("callees").size(), "one more level: " + mid);
        JsonNode leaf = mid.path("callees").get(0);
        assertEquals("com.example.Target", leaf.path("class").asText());
        assertEquals("leaf", leaf.path("method").asText());
    }

    @Test
    void maxDepthLimitsCalleeLevels() throws Exception {
        JsonNode tree = call("com.example.Top", "entryPoint", 1);
        JsonNode root = tree.path("target");
        assertEquals(1, root.path("callees").size(), "maxDepth=1 keeps direct callees: " + root);
        JsonNode mid = root.path("callees").get(0);
        assertEquals("callsLeaf", mid.path("method").asText());
        assertEquals(1, mid.path("callees").size(),
                "the cut branch leaves a depth marker: " + mid);
        assertTrue(mid.path("callees").get(0).path("truncated").asBoolean());
    }

    @Test
    void leafReportsNoCallees() throws Exception {
        // Target.leaf actually calls System.out.println, so use a fresh
        // no-op method to exercise the empty-tree diagnostic.
        java.nio.file.Path tmp = java.nio.file.Files.createTempDirectory("codescope-callees-leaf-");
        try {
            copyDir(FIXTURE, tmp);
            java.nio.file.Files.writeString(
                    tmp.resolve("src/main/java/com/example/DoNothing.java"), """
                    package com.example;
                    public class DoNothing { public void nothing() {} }
                    """);
            TraceCalleesService svc = new TraceCalleesService();
            JsonNode tree = new ObjectMapper().readTree(svc.traceCalleesJson(
                    "com.example.DoNothing", "nothing", null, null, tmp, true, false,
                    com.codescope.analysis.CalleeChainAnalyzer.DEFAULT_MAX_DEPTH));
            assertEquals("ok", tree.path("status").asText());
            assertTrue(tree.path("message").asText().contains("No callees found"),
                    "expected the no-callees diagnostic, got: " + tree.path("message").asText());
            assertEquals(0, tree.path("target").path("callees").size());
        } finally {
            deleteRecursively(tmp);
        }
    }

    @Test
    void interfaceTargetExpandsToImplementorBodies() throws Exception {
        // The interface method has no body; its tree is the union of the
        // implementor bodies' callees, so IfaceRepositoryImpl#findById's
        // call to IfaceDao#findById surfaces directly under the interface.
        JsonNode tree = call("com.example.IfaceRepository", "findById",
                com.codescope.analysis.CalleeChainAnalyzer.DEFAULT_MAX_DEPTH);
        JsonNode root = tree.path("target");
        assertEquals("com.example.IfaceRepository#findById/1", root.path("signature").asText());
        assertEquals(1, root.path("callees").size(),
                "the implementor's body belongs to the interface's tree: " + root);
        JsonNode callee = root.path("callees").get(0);
        assertEquals("com.example.IfaceDao", callee.path("class").asText());
        assertEquals("findById", callee.path("method").asText());
    }

    // --- helpers (mirror FindCallSitesServiceTest) ---

    private static void copyDir(java.nio.file.Path src, java.nio.file.Path dst)
            throws java.io.IOException {
        try (var s = java.nio.file.Files.walk(src)) {
            s.forEach(p -> {
                try {
                    java.nio.file.Path rel = src.relativize(p);
                    java.nio.file.Path target = dst.resolve(rel.toString());
                    if (java.nio.file.Files.isDirectory(p)) {
                        java.nio.file.Files.createDirectories(target);
                    } else {
                        java.nio.file.Files.copy(p, target);
                    }
                } catch (java.io.IOException e) {
                    throw new RuntimeException(e);
                }
            });
        }
    }

    private static void deleteRecursively(java.nio.file.Path p) throws java.io.IOException {
        if (!java.nio.file.Files.exists(p)) return;
        try (var s = java.nio.file.Files.walk(p)) {
            s.sorted(java.util.Comparator.reverseOrder()).forEach(child -> {
                try { java.nio.file.Files.deleteIfExists(child); } catch (java.io.IOException ignored) {}
            });
        }
    }

    @Test
    void invalidMaxDepthIsRejected() {
        TraceCalleesService svc = new TraceCalleesService();
        assertThrows(TraceCalleesService.TraceCalleesException.class,
                () -> svc.traceCalleesJson("com.example.Top", "entryPoint", null, null,
                        FIXTURE, false, false, -1));
        assertThrows(TraceCalleesService.TraceCalleesException.class,
                () -> svc.traceCalleesJson("com.example.Top", "entryPoint", null, null,
                        FIXTURE, false, false, TraceCalleesService.MAX_DEPTH_LIMIT + 1));
    }
}
