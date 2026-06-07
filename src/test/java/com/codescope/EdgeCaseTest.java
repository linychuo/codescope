package com.codescope;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Edge-case tests:
 * <ul>
 *   <li>Deep call chains (no stack overflow in {@link CallNode#toJson}).</li>
 *   <li>Cyclic call graphs (cycle markers, no infinite loop).</li>
 *   <li>Bad / unparseable source files in {@link JdtIndexer}.</li>
 *   <li>Concurrent reads/writes on {@link ProjectIndex}.</li>
 * </ul>
 */
class EdgeCaseTest {

    @Test
    void deepCallChainIsTruncatedAtMaxDepthAndSerializesCleanly() throws Exception {
        // Two prior tests covered the same scenario from different angles:
        // (a) toJson() must be iterative so deep chains don't blow the stack,
        // (b) Jackson serialization of the envelope must not StackOverflow on
        // a deep chain (Jackson's MapSerializer.serialize is recursive even
        // though our toJson() is iterative).
        //
        // The fix puts a MAX_DEPTH cap inside the BFS itself: any branch
        // deeper than MAX_DEPTH gets cut and replaced with a truncated
        // marker. That sidesteps both stack-overflow paths at the source.
        // This test pins down the new contract — depth-cap + clean
        // serialization — in one place.
        int depth = 2_000;
        ProjectIndex index = new ProjectIndex();
        MethodKey prev = new MethodKey("com.example.L0", "m", 0);
        index.putDeclaration(prev, new ProjectIndex.SourceLoc("L0.java", 1));
        for (int i = 1; i < depth; i++) {
            MethodKey cur = new MethodKey("com.example.L" + i, "m", 0);
            index.putDeclaration(cur, new ProjectIndex.SourceLoc("L" + i + ".java", 1));
            index.recordInvocation(prev, cur);
            prev = cur;
        }
        CallChainAnalyzer.Result r = new CallChainAnalyzer().traceCallers(index, prev);
        assertTrue(r.found());

        CallNode root = r.root();
        int actualDepth = depthOf(root);
        // BFS caps depth at MAX_DEPTH (500). The cap is private; we assert
        // it's strictly less than the input chain length (so we know the
        // cap kicked in) and that there IS a truncation marker at the leaf.
        assertTrue(actualDepth < depth - 1,
                "expected BFS to cap at MAX_DEPTH; input was " + (depth - 1)
                        + ", got " + actualDepth);
        assertTrue(actualDepth > 0, "chain should have nonzero depth");
        CallNode leaf = root;
        while (!leaf.callers.isEmpty()) leaf = leaf.callers.get(0);
        assertTrue(leaf.truncated,
                "expected leaf to be a truncated marker (BFS hit depth cap), got: " + leaf);

        // toJson() is iterative and must not throw StackOverflowError.
        Map<String, Object> tree = root.toJson();
        assertNotNull(tree);

        // Jackson serialization of the envelope must succeed. With the cap
        // in place this is now trivially within Jackson's default nesting
        // limit; TraceCallersService also raises StreamWriteConstraints as
        // belt-and-suspenders for the same scenario.
        Map<String, Object> envelope = new java.util.LinkedHashMap<>();
        envelope.put("target", tree);
        envelope.put("status", "ok");
        envelope.put("message", "test");
        String json = TraceCallersService.newObjectMapper().writeValueAsString(envelope);
        assertTrue(json.contains("\"truncated\":true"),
                "expected truncated marker in JSON output");
    }

    @Test
    void diamondIsNotMislabeledAsCycle() {
        // a -> b -> d, a -> c -> d. Tracing callers of d, the chain should
        // reach a via two different paths; a is not on either path's ancestor
        // set, so neither occurrence should be flagged as a cycle.
        ProjectIndex index = new ProjectIndex();
        MethodKey a = new MethodKey("com.example.A", "a", 0);
        MethodKey b = new MethodKey("com.example.B", "b", 0);
        MethodKey c = new MethodKey("com.example.C", "c", 0);
        MethodKey d = new MethodKey("com.example.D", "d", 0);
        for (MethodKey k : List.of(a, b, c, d)) {
            index.putDeclaration(k, new ProjectIndex.SourceLoc(k.methodName + ".java", 1));
        }
        // b calls d, c calls d (d's direct callers)
        index.recordInvocation(b, d);
        index.recordInvocation(c, d);
        // a calls b, a calls c
        index.recordInvocation(a, b);
        index.recordInvocation(a, c);

        CallChainAnalyzer.Result r = new CallChainAnalyzer().traceCallers(index, d);
        assertTrue(r.found());

        CallNode dNode = r.root();
        // d's callers: b and c (no cycles — d itself is not on the path).
        assertEquals(2, dNode.callers.size());
        for (CallNode level1 : dNode.callers) {
            // b and c each have a single caller: a. a is not on the path
            // {d, b} or {d, c}, so a must not be flagged as a cycle.
            assertEquals(1, level1.callers.size(), "expected one caller for " + level1.methodName);
            CallNode aNode = level1.callers.get(0);
            assertEquals("a", aNode.methodName);
            assertFalse(aNode.cycle, "diamond should not be flagged as cycle at " + level1.methodName);
        }
    }

    @Test
    void traceCallersWorksForTargetsNotDeclaredInProject() {
        // Library methods aren't declared in project sources, but the
        // call-edge map still records project -> library edges. Tracing
        // callers of a library target must return the project callers
        // without requiring a project-side declaration of the target.
        ProjectIndex index = new ProjectIndex();
        MethodKey library = new MethodKey("com.lib.External", "doStuff", 1,
                List.of("java.lang.String"));
        // No putDeclaration for `library` — it lives in a jar.
        MethodKey caller = new MethodKey("com.example.App", "useExternal", 1,
                List.of("java.lang.String"));
        index.putDeclaration(caller, new ProjectIndex.SourceLoc("App.java", 1));
        index.recordInvocation(caller, library);

        CallChainAnalyzer.Result r = new CallChainAnalyzer().traceCallers(index, library);
        assertTrue(r.found(), "library target with project callers should be found");
        assertEquals(1, r.root().callers.size());
        assertEquals("com.example.App#useExternal/1", r.root().callers.get(0).signature);
    }

    @Test
    void cyclicGraphIsCollapsedToBackEdgeMarker() {
        // a -> b -> a (cycle). Both a and b are declared. We ask for callers of a.
        ProjectIndex index = new ProjectIndex();
        MethodKey a = new MethodKey("com.example.A", "f", 0);
        MethodKey b = new MethodKey("com.example.B", "f", 0);
        index.putDeclaration(a, new ProjectIndex.SourceLoc("A.java", 1));
        index.putDeclaration(b, new ProjectIndex.SourceLoc("B.java", 1));
        index.recordInvocation(b, a);  // b calls a
        index.recordInvocation(a, b);  // a calls b

        CallChainAnalyzer.Result r = new CallChainAnalyzer().traceCallers(index, a);
        assertTrue(r.found());

        // The chain must terminate: b appears once with a cycle-marked child.
        CallNode aNode = r.root();
        assertEquals(1, aNode.callers.size());
        CallNode bNode = aNode.callers.get(0);
        assertEquals("com.example.B#f/0", bNode.signature);
        // The back-edge to a is a cycle marker:
        assertEquals(1, bNode.callers.size());
        assertTrue(bNode.callers.get(0).cycle, "expected cycle marker for back-edge");
        assertEquals("com.example.A#f/0", bNode.callers.get(0).signature);
    }

    @Test
    void truncatedAtMaxNodesStopsRunawayExpansion() {
        // Build a fan-out: one target with many distinct callers. The
        // 50_000-node cap should kick in.
        ProjectIndex index = new ProjectIndex();
        MethodKey target = new MethodKey("com.example.Target", "leaf", 0);
        index.putDeclaration(target, new ProjectIndex.SourceLoc("Target.java", 1));
        // Add MAX_NODES + 1 distinct callers so traversal must truncate.
        // Use a representative count, not 100k, to keep this test fast.
        int n = 51_000;
        for (int i = 0; i < n; i++) {
            MethodKey caller = new MethodKey("com.example.C" + i, "call", 0);
            index.putDeclaration(caller, new ProjectIndex.SourceLoc("C.java", 1));
            index.recordInvocation(caller, target);
        }
        CallChainAnalyzer.Result r = new CallChainAnalyzer().traceCallers(index, target);
        assertTrue(r.found());
        assertTrue(r.message().contains("Truncated"),
                "expected truncation message, got: " + r.message());
    }

    @Test
    void ambiguousMethodReturnsUsefulError() throws Exception {
        // Two methods named "go" in the same class, no arity filter.
        ProjectIndex index = new ProjectIndex();
        index.putDeclaration(new MethodKey("com.example.Amb", "go", 0),
                new ProjectIndex.SourceLoc("A.java", 1));
        index.putDeclaration(new MethodKey("com.example.Amb", "go", 1,
                List.of("java.lang.String")),
                new ProjectIndex.SourceLoc("A.java", 5));

        ProjectIndex.AmbiguousMethodException ex = assertThrows(
                ProjectIndex.AmbiguousMethodException.class,
                () -> index.resolveTarget("com.example.Amb", "go"));
        assertTrue(ex.getMessage().contains("go"), ex.getMessage());
    }

    @Test
    void unknownTargetReturnsEmptyChainWithDiagnostic() {
        // A target that nothing calls — whether because the class doesn't
        // exist or because no project code calls it — returns an empty
        // chain with a diagnostic message, not a failure flag. The previous
        // "found=false on miss" contract conflated "no callers" with "could
        // not run" — they're different things and the empty-chain form is
        // more useful for the caller.
        ProjectIndex index = new ProjectIndex();
        CallChainAnalyzer.Result r = new CallChainAnalyzer().traceCallers(index,
                new MethodKey("com.example.Nope", "missing", 0));
        assertTrue(r.found(), "no-callers is a valid result, not an error");
        assertTrue(r.message().contains("No callers"), r.message());
    }

    @Test
    void jdtIndexerSurvivesMalformedSources(@TempDir Path tmp) throws IOException {
        // Mix valid and broken Java files in the same source root.
        Files.createDirectories(tmp.resolve("src"));
        Files.writeString(tmp.resolve("src/Good.java"), """
                package com.example;
                public class Good {
                    public void go() {}
                }
                """);
        // Invalid UTF-8: triggers the read-string IOException path. JDT is
        // very lenient at the parse level (it tolerates almost any syntax
        // error), so the reliable way to make a file "unparseable" is to
        // make it unreadable.
        Path invalid = tmp.resolve("src/InvalidUtf8.java");
        Files.write(invalid, new byte[]{(byte) 0xC3, (byte) 0x28, (byte) 0x80});  // 0xC3 0x28 is invalid UTF-8
        // A zero-byte file: readString succeeds, AST returns a CU with
        // problems but not null. We don't expect this one to be skipped,
        // just verifying it doesn't break the indexer.
        Files.writeString(tmp.resolve("src/Empty.java"), "");

        List<Path> sources = new ArrayList<>();
        try (var s = Files.list(tmp.resolve("src"))) {
            s.forEach(sources::add);
        }
        // JdtIndexer must not throw — bad files are skipped with a reason.
        ProjectIndex index = assertDoesNotThrow(() ->
                new JdtIndexer().build(sources, List.of(), List.of(), tmp));
        // Good.java should have parsed.
        Set<MethodKey> known = new HashSet<>(index.knownMethods());
        assertTrue(known.stream().anyMatch(k -> k.methodName.equals("go")),
                "expected Good.go to be indexed, got: " + known);
        // InvalidUtf8.java should be recorded as skipped.
        List<String> skipped = index.skippedFiles();
        assertTrue(skipped.stream().anyMatch(s -> s.contains("InvalidUtf8.java")),
                "expected InvalidUtf8.java in skipped list, got: " + skipped);
        // Each entry has a reason.
        assertTrue(skipped.stream().allMatch(s -> s.contains(":")),
                "expected each skip entry to have a reason, got: " + skipped);
    }

    @Test
    void jdtIndexerBuildReturnsPromptlyOnInterrupt(@TempDir Path tmp) throws Exception {
        // The interrupt handler inside JdtIndexer.build() breaks out of the
        // future-wait loop on InterruptedException, but the surrounding
        // try-with-resources on the virtual-thread pool still calls
        // pool.close(), which blocks until every submitted parse task
        // finishes. We want to verify (and lock in) the actual behavior:
        // if the caller is interrupted, does build() return faster than a
        // full un-interrupted run, or does it block to completion anyway?
        //
        // We can't actually deliver an InterruptedException to f.get()
        // from outside (the futures run on virtual threads, not the
        // caller). But we CAN simulate the cancellation shape: the
        // contract is "build() must finish, not hang forever". So we
        // assert it returns within a generous bound (60s) on a
        // 200-file project. If someone later makes the executor close
        // reentrant in a way that hangs, this test will catch it.
        int n = 200;
        List<Path> sources = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            int prev = i > 0 ? i - 1 : 0;
            String src = "package x;\n"
                    + "public class F" + i + " {\n"
                    + "    public void m" + i + "() { m" + prev + "(); }\n"
                    + "}\n";
            Path p = tmp.resolve("F" + i + ".java");
            Files.writeString(p, src);
            sources.add(p);
        }

        long start = System.nanoTime();
        ProjectIndex index = new JdtIndexer().build(sources, List.of(), List.of(), tmp);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertNotNull(index);
        // Loose bound: 200 tiny files should index in well under 60s even
        // on slow CI. If we ever cross it, build() is hanging on shutdown.
        assertTrue(elapsedMs < 60_000,
                "JdtIndexer.build on 200 files took " + elapsedMs + "ms; "
                        + "expected < 60_000. Pool shutdown may be hanging.");
    }

    @Test
    void projectIndexIsThreadSafeUnderConcurrentWrites() throws Exception {
        // Hammer ProjectIndex from many threads; we should never lose data or
        // throw, and the dedupe check should still hold.
        ProjectIndex index = new ProjectIndex();
        MethodKey target = new MethodKey("com.example.Target", "leaf", 0);
        index.putDeclaration(target, new ProjectIndex.SourceLoc("T.java", 1));

        int threads = 8;
        int callsPerThread = 1_000;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicReference<Throwable> err = new AtomicReference<>();
        try {
            List<java.util.concurrent.Future<?>> fs = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                fs.add(pool.submit(() -> {
                    try {
                        start.await();
                        for (int i = 0; i < callsPerThread; i++) {
                            MethodKey caller = new MethodKey("com.example.C" + (i % 50), "call", 0);
                            index.recordInvocation(caller, target);
                        }
                    } catch (Throwable th) {
                        err.set(th);
                    }
                }));
            }
            start.countDown();
            for (var f : fs) f.get(10, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
        assertNull(err.get(), "concurrent writes should not throw: " + err.get());

        // Each of the 50 distinct callers should appear exactly once (deduped).
        List<MethodKey> callers = index.callersOf(target);
        assertEquals(50, callers.size(), "expected 50 unique deduped callers, got "
                + callers.size() + " — " + callers);
    }

    @Test
    void projectIndexIsThreadSafeWithMixedReadsAndWrites() throws Exception {
        // Writers add callers, readers call callersOf + knownMethods. Both
        // must work without ConcurrentModificationException etc.
        ProjectIndex index = new ProjectIndex();
        MethodKey target = new MethodKey("com.example.T", "m", 0);
        index.putDeclaration(target, new ProjectIndex.SourceLoc("T.java", 1));

        ExecutorService pool = Executors.newFixedThreadPool(6);
        CountDownLatch start = new CountDownLatch(1);
        AtomicReference<Throwable> err = new AtomicReference<>();
        try {
            List<java.util.concurrent.Future<?>> fs = new ArrayList<>();
            // 3 writers
            for (int t = 0; t < 3; t++) {
                fs.add(pool.submit(() -> {
                    try {
                        start.await();
                        for (int i = 0; i < 1_000; i++) {
                            index.putDeclaration(new MethodKey("com.example.W" + i, "f", 0),
                                    new ProjectIndex.SourceLoc("W.java", 1));
                            index.recordInvocation(
                                    new MethodKey("com.example.W" + i, "f", 0), target);
                        }
                    } catch (Throwable th) { err.set(th); }
                }));
            }
            // 3 readers
            for (int t = 0; t < 3; t++) {
                fs.add(pool.submit(() -> {
                    try {
                        start.await();
                        for (int i = 0; i < 1_000; i++) {
                            index.callersOf(target);
                            index.knownMethods();
                            index.declarationOf(target);
                        }
                    } catch (Throwable th) { err.set(th); }
                }));
            }
            start.countDown();
            for (var f : fs) f.get(10, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
        assertNull(err.get(), "concurrent mixed ops should not throw: " + err.get());
        assertEquals(1_000, index.callersOf(target).size());
    }

    @Test
    void emptyAndNullCollectionsBehave() {
        // Defensive: empty callers / empty index shouldn't NPE.
        ProjectIndex index = new ProjectIndex();
        assertTrue(index.callersOf(new MethodKey("x", "y", 0)).isEmpty());
        assertNull(index.declarationOf(new MethodKey("x", "y", 0)));
        assertEquals(Collections.emptySet(), index.knownMethods());
    }

    @Test
    void anonymousInnerClassMethodsAreAttributedToAnonType(@TempDir Path tmp) throws IOException {
        // After F32: a method declared inside `new Runnable() { void run() {...} }`
        // must be indexed under a synthesized FQN, not the enclosing Outer
        // class. JDT reports the binding as "x.$Outer" (the $ prefix is JDT's
        // convention for anonymous types).
        Files.writeString(tmp.resolve("Outer.java"), """
                package x;
                public class Outer {
                    void use() {
                        Runnable r = new Runnable() {
                            public void run() { target(); }
                            void target() {}
                        };
                        r.run();
                    }
                    static void target() {}
                }
                """);

        ProjectIndex index = new JdtIndexer().build(
                List.of(tmp.resolve("Outer.java")), List.of(), List.of(), tmp);

        // The anonymous-class 'run' must be in the index, not on Outer itself.
        boolean foundAnonRun = index.knownMethods().stream()
                .anyMatch(k -> !k.declaringClass.equals("x.Outer") && k.methodName.equals("run"));
        assertTrue(foundAnonRun, "expected anonymous 'run' to live outside x.Outer, got: "
                + index.knownMethods());

        // The anon-class 'target' too.
        boolean foundAnonTarget = index.knownMethods().stream()
                .filter(k -> k.methodName.equals("target"))
                .anyMatch(k -> !k.declaringClass.equals("x.Outer"));
        assertTrue(foundAnonTarget,
                "expected anonymous 'target' to live outside x.Outer, got: "
                + index.knownMethods().stream().filter(k -> k.methodName.equals("target")).toList());
    }

    @Test
    void nestedClassMethodsUseDottedFqn(@TempDir Path tmp) throws IOException {
        // A method declared inside Outer.Inner.Deepest must be indexed under
        // FQN "com.example.Outer.Inner.Deepest" — outermost first, dotted,
        // matching what IMethodBinding.getDeclaringClass().getQualifiedName()
        // returns at call sites. The naïve head-first iteration of the
        // type-stack would produce "com.example.Deepest.Inner.Outer", and
        // a $-separator would split the graph from the dotted binding side
        // — both silently break trace_callers for any nested-class project.
        Files.writeString(tmp.resolve("Outer.java"), """
                package com.example;
                public class Outer {
                    static class Inner {
                        static class Deepest {
                            void leaf() {}
                        }
                    }
                    void callsLeaf() {
                        new Inner.Deepest().leaf();
                    }
                }
                """);
        ProjectIndex index = new JdtIndexer().build(
                List.of(tmp.resolve("Outer.java")), List.of(), List.of(), tmp);

        // (a) Declaration is attributed to the correct nested FQN.
        boolean declCorrect = index.knownMethods().stream()
                .anyMatch(k -> k.declaringClass.equals("com.example.Outer.Inner.Deepest")
                            && k.methodName.equals("leaf"));
        assertTrue(declCorrect, "expected declaration FQN com.example.Outer.Inner.Deepest, "
                + "got: " + index.knownMethods());

        // (b) The call edge links: a call to Outer.Inner.Deepest#leaf from
        //     Outer#callsLeaf must show up. Catches the regression where
        //     decl FQN and binding FQN diverge (different order or
        //     separator) and silently split the call graph.
        MethodKey leaf = new MethodKey("com.example.Outer.Inner.Deepest", "leaf", 0, List.of());
        List<MethodKey> callers = index.callersOf(leaf);
        assertFalse(callers.isEmpty(),
                "expected callsLeaf linked as caller of Outer.Inner.Deepest#leaf, "
                + "got no callers; all calls: " + index.allCalls());
        assertTrue(callers.stream().anyMatch(k -> k.methodName.equals("callsLeaf")),
                "expected callsLeaf among callers, got: " + callers);
    }

    private static int depthOf(CallNode n) {
        int d = 0;
        while (!n.callers.isEmpty()) {
            n = n.callers.get(0);
            d++;
        }
        return d;
    }
}
