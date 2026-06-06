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
    void lruEvictsOldestEntryBeyondCapacity() throws Exception {
        // The indexCache is sized at MAX_CACHED_PROJECTS=8 and uses
        // access-order. Touching 9 distinct project roots should evict
        // the first one we touched. We assert this indirectly: after
        // touching 9 projects in order, the first project's index has
        // been dropped from the cache. We detect the eviction by
        // replacing one of the source files in the first project and
        // calling again WITHOUT refresh — the cached index would NOT
        // see the change, but since the cache evicted this project,
        // the second call rebuilds and DOES see it. This locks in both
        // the cap and the access-order eviction.
        int n = 9;
        Path[] projects = new Path[n];
        for (int i = 0; i < n; i++) {
            projects[i] = Files.createTempDirectory("codescope-lru-" + i + "-");
            copyDir(FIXTURE, projects[i]);
        }
        try {
            TraceCallersService svc = new TraceCallersService();

            // Warm: 8 distinct roots, with the first one being project[0].
            for (int i = 0; i < 8; i++) {
                svc.traceCallersJson("com.example.Target", "leaf",
                        null, null, projects[i], false);
            }
            // Touch a 9th distinct root — evicts project[0].
            svc.traceCallersJson("com.example.Target", "leaf",
                    null, null, projects[8], false);

            // Mutate project[0]'s sources and call again WITHOUT refresh.
            // If project[0] is still cached, the result won't see the new
            // file. If evicted (as it should be), the rebuild picks it up.
            String newCaller = """
                    package com.example;
                    public class NewCaller {
                        public void go() { new com.example.Target().leaf(); }
                    }
                    """;
            Files.writeString(projects[0].resolve(
                    "src/main/java/com/example/NewCaller.java"), newCaller);

            JsonNode tree = new ObjectMapper().readTree(svc.traceCallersJson(
                    "com.example.Target", "leaf", null, null, projects[0], false));
            // We expect 2 callers now (Mid.callsLeaf + NewCaller.go) — the
            // rebuild path was taken, which only happens when the entry
            // was evicted.
            assertEquals(2, tree.path("target").path("callers").size(),
                    "LRU should have evicted project[0]; expected rebuild to "
                            + "pick up the new caller, got: " + tree);
        } finally {
            for (Path p : projects) deleteRecursively(p);
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
