package com.codescope;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Direct unit tests for {@link TraceCallersService}. Covers the LRU cache and
 * the {@code refresh} arg, which are not exercised by the stdio test.
 */
class TraceCallersServiceTest {

    private static final Path FIXTURE = Path.of("src/test/resources/fixture-project");

    @Test
    void refreshRebuildsCachedIndex() throws Exception {
        TraceCallersService svc = new TraceCallersService();

        // Warm the cache
        String first = svc.traceCallersJson("com.example.Target", "leaf",
                null, null, FIXTURE, false);
        assertTrue(first.contains("\"signature\":\"com.example.Target#leaf/0\""),
                "first call should hit a real index, got: " + first);

        // Same call should be served from cache (no rebuild). We assert the
        // public surface: the result is consistent and the method doesn't throw.
        String second = svc.traceCallersJson("com.example.Target", "leaf",
                null, null, FIXTURE, false);
        assertEquals(first, second);

        // refresh=true should still return a valid result (the file is the same,
        // so the tree is identical, but the rebuild path was taken).
        String refreshed = svc.traceCallersJson("com.example.Target", "leaf",
                null, null, FIXTURE, true);
        assertEquals(first, refreshed);
    }

    @Test
    void refreshAfterFileEditPicksUpNewCallers() throws Exception {
        // Use a fresh service + tmp copy of the fixture so the first call's
        // cache doesn't carry over from another test.
        Path tmp = Files.createTempDirectory("codescope-refresh-");
        try {
            copyDir(FIXTURE, tmp);
            TraceCallersService svc = new TraceCallersService();

            // 1) First call: Target.leaf is called by Mid.callsLeaf only
            JsonNode tree1 = new ObjectMapper().readTree(svc.traceCallersJson(
                    "com.example.Target", "leaf", null, null, tmp, false));
            JsonNode callers1 = tree1.path("target").path("callers");
            assertEquals(1, callers1.size(), "expected 1 caller initially, got: " + callers1);

            // 2) Add a new caller in another class. Target.java stays the same;
            //    we add a NewCaller.java that calls Target.leaf.
            String newCaller = """
                    package com.example;
                    public class NewCaller {
                        public void go() {
                            new com.example.Target().leaf();
                        }
                    }
                    """;
            Files.writeString(tmp.resolve("src/main/java/com/example/NewCaller.java"), newCaller);

            // 3) Without refresh, the cached index does NOT see the new caller.
            JsonNode tree2 = new ObjectMapper().readTree(svc.traceCallersJson(
                    "com.example.Target", "leaf", null, null, tmp, false));
            assertEquals(1, tree2.path("target").path("callers").size(),
                    "cached result must NOT pick up the new file, got: " + tree2);

            // 4) With refresh=true, the new caller shows up.
            JsonNode tree3 = new ObjectMapper().readTree(svc.traceCallersJson(
                    "com.example.Target", "leaf", null, null, tmp, true));
            JsonNode callers3 = tree3.path("target").path("callers");
            assertEquals(2, callers3.size(),
                    "refresh should pick up the new caller, got: " + callers3);
        } finally {
            // best-effort cleanup
            deleteRecursively(tmp);
        }
    }

    @Test
    void optionalBoolArgsRejectWrongTypes() {
        // The adapter's boolean coercion must throw IllegalArgumentException
        // for non-boolean non-null values — that's the contract McpServer
        // relies on to label the error as "Invalid arguments" (vs. a generic
        // "Tool execution failed"). Drive TraceCallersTool directly with a
        // synthetic args map and catch the IAE.
        TraceCallersTool tool = new TraceCallersTool();
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> tool.invoke(java.util.Map.of(
                        "class", "com.example.Target",
                        "method", "leaf",
                        "project", FIXTURE.toString(),
                        "refresh", "yes"  // not a boolean
                )));
        assertTrue(ex.getMessage().contains("boolean"),
                "expected 'boolean' in error message, got: " + ex.getMessage());
    }

    // --- helpers ---

    private static void copyDir(Path src, Path dst) throws java.io.IOException {
        try (var s = Files.walk(src)) {
            s.forEach(p -> {
                try {
                    Path rel = src.relativize(p);
                    Path target = dst.resolve(rel.toString());
                    if (Files.isDirectory(p)) {
                        Files.createDirectories(target);
                    } else {
                        Files.copy(p, target);
                    }
                } catch (java.io.IOException e) {
                    throw new RuntimeException(e);
                }
            });
        }
    }

    private static void deleteRecursively(Path p) throws java.io.IOException {
        if (!Files.exists(p)) return;
        try (var s = Files.walk(p)) {
            s.sorted(java.util.Comparator.reverseOrder()).forEach(child -> {
                try { Files.deleteIfExists(child); } catch (java.io.IOException ignored) {}
            });
        }
    }
}
