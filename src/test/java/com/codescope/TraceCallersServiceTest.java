package com.codescope;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

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
                null, null, FIXTURE, false, false);
        assertTrue(first.contains("\"signature\":\"com.example.Target#leaf/0\""),
                "first call should hit a real index, got: " + first);

        // Same call should be served from cache (no rebuild). We assert the
        // public surface: the result is consistent and the method doesn't throw.
        String second = svc.traceCallersJson("com.example.Target", "leaf",
                null, null, FIXTURE, false, false);
        assertEquals(first, second);

        // refresh=true should still return a valid result (the file is the same,
        // so the tree is identical, but the rebuild path was taken).
        String refreshed = svc.traceCallersJson("com.example.Target", "leaf",
                null, null, FIXTURE, true, false);
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
                    "com.example.Target", "leaf", null, null, tmp, false, false));
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
                    "com.example.Target", "leaf", null, null, tmp, false, false));
            assertEquals(1, tree2.path("target").path("callers").size(),
                    "cached result must NOT pick up the new file, got: " + tree2);

            // 4) With refresh=true, the new caller shows up.
            JsonNode tree3 = new ObjectMapper().readTree(svc.traceCallersJson(
                    "com.example.Target", "leaf", null, null, tmp, true, false));
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
                        null, null, projects[i], false, false);
            }
            // Touch a 9th distinct root — evicts project[0].
            svc.traceCallersJson("com.example.Target", "leaf",
                    null, null, projects[8], false, false);

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
                    "com.example.Target", "leaf", null, null, projects[0], false, false));
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
    void libraryTargetWithoutArityStillReturnsCallers() throws Exception {
        // Regression: querying a library method by (class, name) only — the
        // typical case for a user who doesn't know (or care about) the
        // overload — used to return "No callers found". The synthesized
        // MethodKey defaulted arity to 0 and paramTypes to [], but the call
        // edges recorded by JdtIndexer use the actual signature from JDT
        // bindings (`println(String)` → arity=1, ["java.lang.String"]).
        // Equality on MethodKey is exact, so the BFS lookup missed every
        // recorded edge. The fixture's Target and Mid both call
        // System.out.println(...), so this must return >=1 caller.
        TraceCallersService svc = new TraceCallersService();
        String json = svc.traceCallersJson(
                "java.io.PrintStream", "println",
                null, null,
                FIXTURE, false, false);
        JsonNode tree = new ObjectMapper().readTree(json);
        JsonNode callers = tree.path("target").path("callers");
        assertTrue(callers.isArray() && callers.size() > 0,
                "expected >=1 caller for PrintStream#println, got: " + json);
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

    @Test
    void leafNodeEmitsEmptyCallersArray() throws Exception {
        // Regression: CallNode.toJson() used to skip the "callers" key
        // entirely for nodes with no callers, producing inconsistent JSON
        // (some nodes carry the key, some don't). For a leaf target the
        // top-level "target.callers" must be an empty array, not missing —
        // otherwise downstream consumers that iterate `obj.callers` or
        // serialize the envelope can't tell "0 callers" from "schema bug".
        TraceCallersService svc = new TraceCallersService();
        String json = svc.traceCallersJson("com.example.Mid", "unrelated",
                null, null, FIXTURE, false, false);
        JsonNode tree = new ObjectMapper().readTree(json);
        JsonNode target = tree.path("target");
        assertEquals("com.example.Mid", target.path("class").asText());
        assertTrue(target.path("callers").isArray(),
                "leaf target's callers must be an array (was missing?), got: "
                        + target.path("callers"));
        assertEquals(0, target.path("callers").size(),
                "leaf target should have 0 callers, got: " + target.path("callers"));
    }

    @Test
    void callerCountReportsUniqueMethodsAcrossDiamondPaths() throws Exception {
        // Regression: the "OK; N caller(s) in chain" message used to
        // increment a counter for every addChild, so a diamond (one
        // method reached via two different parents) inflated the count
        // by the number of paths. The contract is "unique caller methods
        // in the chain", matching how FindCallSitesService reports its
        // union of caller keys.
        //
        // Build a self-contained fixture with this graph rooted at
        // DiamondTop.go:
        //   DiamondTop.go -> DiamondLeft.x  -> DiamondTarget.leaf
        //   DiamondTop.go -> DiamondRight.y -> DiamondTarget.leaf
        // Unique callers of DiamondTarget.leaf in this chain = 3
        // (Left, Right, Top). Per-path adds = 4 (Left, Right, Top, Top).
        Path tmp = Files.createTempDirectory("codescope-diamond-");
        try {
            copyDir(FIXTURE, tmp);
            Files.writeString(tmp.resolve("src/main/java/com/example/DiamondTarget.java"), """
                    package com.example;
                    public class DiamondTarget {
                        public void leaf() { System.out.println("d"); }
                    }
                    """);
            Files.writeString(tmp.resolve("src/main/java/com/example/DiamondLeft.java"), """
                    package com.example;
                    public class DiamondLeft {
                        public void x() { new DiamondTarget().leaf(); }
                    }
                    """);
            Files.writeString(tmp.resolve("src/main/java/com/example/DiamondRight.java"), """
                    package com.example;
                    public class DiamondRight {
                        public void y() { new DiamondTarget().leaf(); }
                    }
                    """);
            Files.writeString(tmp.resolve("src/main/java/com/example/DiamondTop.java"), """
                    package com.example;
                    public class DiamondTop {
                        public void go() {
                            new DiamondLeft().x();
                            new DiamondRight().y();
                        }
                    }
                    """);

            TraceCallersService svc = new TraceCallersService();
            String json = svc.traceCallersJson("com.example.DiamondTarget", "leaf",
                    null, null, tmp, true, false);
            JsonNode tree = new ObjectMapper().readTree(json);
            JsonNode target = tree.path("target");

            // The visible tree preserves the diamond: 2 direct callers
            // (Left, Right) at depth 1, and DiamondTop.go converges on
            // them at depth 2 — appearing as a child of both.
            assertEquals(2, target.path("callers").size(),
                    "expected 2 direct callers, got: " + target.path("callers"));

            int topCount = 0;
            for (JsonNode c1 : target.path("callers")) {
                JsonNode innerCallers = c1.path("callers");
                assertTrue(innerCallers.size() > 0,
                        "c1 " + c1.path("class").asText() + " should have callers, got: " + innerCallers);
                for (JsonNode c2 : innerCallers) {
                    if ("com.example.DiamondTop".equals(c2.path("class").asText())
                            && "go".equals(c2.path("method").asText())) topCount++;
                }
            }
            assertEquals(2, topCount,
                    "DiamondTop.go should converge from both Left and Right, appeared "
                            + topCount + " times");

            // The summary must say 3 (unique), not 4 (raw path-adds).
            String message = tree.path("message").asText();
            assertTrue(message.contains("3 caller(s)"),
                    "message should report 3 unique callers, got: " + message);
            assertFalse(message.contains("4 caller(s)"),
                    "message must not use the per-path-add count, got: " + message);
        } finally {
            deleteRecursively(tmp);
        }
    }

    @Test
    void diamondFoldsIntoDedupedMarker() throws Exception {
        // Regression: a method reachable from the target via two
        // different parents (a diamond) used to appear as two full
        // copies in the tree, doubling the output size for any popular
        // upstream. The tree now folds the second occurrence into a
        // "deduped" marker — same class/method/arity/signature plus
        // the method's declaration location, but no callers subtree —
        // so consumers still see "this method is reached from
        // multiple paths" without paying the size cost twice.
        Path tmp = Files.createTempDirectory("codescope-dedup-");
        try {
            copyDir(FIXTURE, tmp);
            Files.writeString(tmp.resolve("src/main/java/com/example/DiamondTarget.java"), """
                    package com.example;
                    public class DiamondTarget {
                        public void leaf() { System.out.println("d"); }
                    }
                    """);
            Files.writeString(tmp.resolve("src/main/java/com/example/DiamondLeft.java"), """
                    package com.example;
                    public class DiamondLeft {
                        public void x() { new DiamondTarget().leaf(); }
                    }
                    """);
            Files.writeString(tmp.resolve("src/main/java/com/example/DiamondRight.java"), """
                    package com.example;
                    public class DiamondRight {
                        public void y() { new DiamondTarget().leaf(); }
                    }
                    """);
            Files.writeString(tmp.resolve("src/main/java/com/example/DiamondTop.java"), """
                    package com.example;
                    public class DiamondTop {
                        public void go() {
                            new DiamondLeft().x();
                            new DiamondRight().y();
                        }
                    }
                    """);

            TraceCallersService svc = new TraceCallersService();
            String json = svc.traceCallersJson("com.example.DiamondTarget", "leaf",
                    null, null, tmp, true, false);
            JsonNode tree = new ObjectMapper().readTree(json);
            JsonNode target = tree.path("target");
            assertEquals(2, target.path("callers").size(),
                    "expected 2 direct callers, got: " + target.path("callers"));

            int fullCount = 0;
            int dedupedCount = 0;
            for (JsonNode c1 : target.path("callers")) {
                for (JsonNode c2 : c1.path("callers")) {
                    if (!"com.example.DiamondTop".equals(c2.path("class").asText())
                            || !"go".equals(c2.path("method").asText())) continue;
                    if (c2.path("deduped").asBoolean(false)) dedupedCount++;
                    else fullCount++;
                }
            }
            assertEquals(1, fullCount,
                    "DiamondTop.go should appear exactly once as a full node, got: "
                            + fullCount);
            assertEquals(1, dedupedCount,
                    "DiamondTop.go should appear exactly once as a deduped marker, got: "
                            + dedupedCount);

            // The deduped marker must still carry enough identity to be
            // useful: class, method, arity, signature, and the method's
            // declaration location so a reader can jump to it.
            for (JsonNode c1 : target.path("callers")) {
                for (JsonNode c2 : c1.path("callers")) {
                    if (!c2.path("deduped").asBoolean(false)) continue;
                    assertEquals("com.example.DiamondTop", c2.path("class").asText());
                    assertEquals("go", c2.path("method").asText());
                    assertTrue(c2.path("file").asText().contains("DiamondTop"),
                            "deduped marker should carry the method's declaration file, got: "
                                    + c2);
                    assertTrue(c2.path("line").asInt() > 0,
                            "deduped marker should carry the method's declaration line, got: "
                                    + c2);
                    assertTrue(c2.path("callers").isArray(),
                            "deduped marker must still emit callers as [] for uniform iteration, got: "
                                    + c2);
                    assertEquals(0, c2.path("callers").size(),
                            "deduped marker has no subtree, got: " + c2);
                }
            }
        } finally {
            deleteRecursively(tmp);
        }
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
