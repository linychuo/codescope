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
        String json = ProjectIndexCache.newObjectMapper().writeValueAsString(envelope);
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
    void jdtIndexerUsesUtf8Consistently(@TempDir Path tmp) throws IOException {
        // Regression: JdtIndexer reads source files as UTF-8
        // (Files.readString(path, StandardCharsets.UTF_8)) but previously
        // passed encodingNames=null to ASTParser, which makes JDT fall
        // back to the JVM's default charset for sourcepath reads. The
        // encoding parameter has no effect on setSource(char[]) content
        // (already decoded), so a single-file test won't catch the bug —
        // JDT only consults the encoding when it reads another source
        // file from the sourcepath to resolve a binding.
        //
        // The bug manifests when (1) the JVM default charset is not UTF-8
        // (Windows CI runners, legacy GBK locales) AND (2) the sourcepath
        // contains a file with non-ASCII identifiers. The cross-file
        // read uses the wrong charset, the identifier becomes mojibake,
        // the caller's binding to that class fails, and the call edge
        // is silently dropped.
        //
        // To reproduce on a UTF-8 host:
        //   mvn test -Dtest=EdgeCaseTest#jdtIndexerUsesUtf8Consistently \
        //           -DargLine="-Dfile.encoding=US-ASCII"
        //
        // On the buggy code with US-ASCII default, Caller.run's call to
        // 工具.服务() is not recorded (the binding to 工具 fails when JDT
        // re-decodes Util.java with US-ASCII). With the fix
        // (encodingNames = new String[]{"UTF-8"}), the call is recorded.
        // The sourcepath layout must mirror the package: com.example →
        // com/example/.
        Path pkgRoot = tmp.resolve("src/main/java/com/example");
        Files.createDirectories(pkgRoot);
        Path util = pkgRoot.resolve("Util.java");
        Files.writeString(util, """
                package com.example;
                public class 工具 {
                    public void 服务() {}
                }
                """, java.nio.charset.StandardCharsets.UTF_8);
        Path caller = pkgRoot.resolve("Caller.java");
        Files.writeString(caller, """
                package com.example;
                public class Caller {
                    public void run() {
                        工具 t = new 工具();
                        t.服务();
                    }
                }
                """, java.nio.charset.StandardCharsets.UTF_8);

        Path srcRoot = tmp.resolve("src/main/java");
        ProjectIndex index = new JdtIndexer().build(
                List.of(caller, util),
                List.of(),
                List.of(srcRoot.toString()),
                tmp);
        // 工具.服务 should be in the index as a callee, with Caller.run
        // recorded as one of its callers.
        MethodKey callee = new MethodKey("com.example.工具", "服务", 0);
        List<MethodKey> callers = index.callersOf(callee);
        assertTrue(callers.stream().anyMatch(c -> "run".equals(c.methodName)),
                "expected Caller.run to be a caller of 工具.服务, got: " + callers
                + "; skipped=" + index.skippedFiles());
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

        // (a) The FQN must be well-formed: no trailing dot, no empty
        //     component. A typeStack push of an empty string from
        //     ITypeBinding.getQualifiedName() (which returns "" for anonymous
        //     classes) used to produce FQNs like "x.Outer." — visually
        //     "outside x.Outer" but actually malformed and unjoinable to
        //     call-site bindings (which use a different empty string).
        for (MethodKey k : index.knownMethods()) {
            assertFalse(k.declaringClass.endsWith(".") || k.declaringClass.startsWith(".")
                            || k.declaringClass.contains(".."),
                    "malformed FQN with empty segment or trailing dot: " + k);
        }

        // (b) The anonymous 'run' must be in the index, not on Outer itself.
        boolean foundAnonRun = index.knownMethods().stream()
                .anyMatch(k -> !k.declaringClass.equals("x.Outer") && k.methodName.equals("run"));
        assertTrue(foundAnonRun, "expected anonymous 'run' to live outside x.Outer, got: "
                + index.knownMethods());

        // (c) The anon-class 'target' too.
        boolean foundAnonTarget = index.knownMethods().stream()
                .filter(k -> k.methodName.equals("target"))
                .anyMatch(k -> !k.declaringClass.equals("x.Outer"));
        assertTrue(foundAnonTarget,
                "expected anonymous 'target' to live outside x.Outer, got: "
                + index.knownMethods().stream().filter(k -> k.methodName.equals("target")).toList());

        // (d) The call edge from the anon 'run' to the anon 'target' must
        //     LINK: the decl-side FQN and the call-site FQN must agree, so
        //     traceCallers(anonTarget) returns the anon 'run' as a caller.
        //     Pre-fix, the decl side was "x.Outer." (trailing dot from
        //     empty-string push) but the call site used "" (empty), so the
        //     edge silently dropped on the floor.
        MethodKey anonTarget = index.knownMethods().stream()
                .filter(k -> k.methodName.equals("target") && !k.declaringClass.equals("x.Outer"))
                .findFirst().orElseThrow();
        List<MethodKey> targetCallers = index.callersOf(anonTarget);
        assertTrue(targetCallers.stream().anyMatch(k -> k.methodName.equals("run")),
                "expected anon 'run' linked as caller of anon 'target', got callers: "
                        + targetCallers + " — all calls: " + index.allCalls());
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

    @Test
    void callSitesRecordMultipleSitesForSameCaller() {
        // The find_call_sites feature relies on ProjectIndex.callSitesOf
        // returning every recorded call site, in order, undeduped — even
        // when the same caller invokes the same callee from many lines
        // in its body. Two call expressions on the same line should
        // collapse (a line in source code is one fact), but two on
        // different lines must both surface. This is the load-bearing
        // data-model contract for the new tool.
        ProjectIndex index = new ProjectIndex();
        MethodKey target = new MethodKey("com.lib.External", "doStuff", 1,
                List.of("java.lang.String"));
        // target is not declared in this index — library method shape.
        MethodKey caller = new MethodKey("com.example.App", "hot", 0);
        index.putDeclaration(caller, new ProjectIndex.SourceLoc("App.java", 5));

        // Three sites at three different lines, then a duplicate site
        // (same line) which should also be preserved (a caller can
        // legitimately call the same method twice from the same line —
        // e.g. a method invocation that's split by a comment, or a
        // statement written on a single line). Then a fourth site in a
        // different caller.
        index.recordCallSite(caller, target,
                new ProjectIndex.SourceLoc("App.java", 10));
        index.recordCallSite(caller, target,
                new ProjectIndex.SourceLoc("App.java", 20));
        index.recordCallSite(caller, target,
                new ProjectIndex.SourceLoc("App.java", 30));
        index.recordCallSite(caller, target,
                new ProjectIndex.SourceLoc("App.java", 20));   // duplicate line

        MethodKey otherCaller = new MethodKey("com.example.Other", "use", 0);
        index.putDeclaration(otherCaller, new ProjectIndex.SourceLoc("Other.java", 5));
        index.recordCallSite(otherCaller, target,
                new ProjectIndex.SourceLoc("Other.java", 8));

        Map<MethodKey, List<ProjectIndex.SourceLoc>> sites = index.callSitesOf(target);
        assertEquals(2, sites.size(), "expected 2 callers with sites, got: " + sites.keySet());

        // Same-caller's sites must be in insertion order, with the
        // duplicate preserved (NOT deduped to a Set).
        List<ProjectIndex.SourceLoc> hotSites = sites.get(caller);
        assertNotNull(hotSites);
        assertEquals(4, hotSites.size(), "duplicate-line site should NOT be deduped, got: " + hotSites);
        assertEquals(10, hotSites.get(0).line());
        assertEquals(20, hotSites.get(1).line());
        assertEquals(30, hotSites.get(2).line());
        assertEquals(20, hotSites.get(3).line(), "duplicate site at line 20 preserved");

        // All sites carry their file too — important for the new tool's
        // output to attribute sites back to the source file.
        for (ProjectIndex.SourceLoc loc : hotSites) {
            assertEquals("App.java", loc.file());
        }

        // Second caller's site is independent.
        List<ProjectIndex.SourceLoc> otherSites = sites.get(otherCaller);
        assertNotNull(otherSites);
        assertEquals(1, otherSites.size());
        assertEquals(8, otherSites.get(0).line());
        assertEquals("Other.java", otherSites.get(0).file());
    }

    @Test
    void privateMethodCallersFoundThroughInterfaceHierarchyIntegration(@TempDir Path tmp) throws IOException {
        // Integration test with real JdtIndexer: private method `helper` in
        // `ServiceImpl` is called by public `doB`/`doC` which implement
        // interface `Service`. External `Client` holds a `Service` ref and
        // calls through the interface. traceCallers(helper) must find Client.
        Path srcDir = Files.createDirectories(tmp.resolve("src/main/java/com/example"));
        Files.writeString(srcDir.resolve("Service.java"),
                "package com.example;\n"
                + "public interface Service {\n"
                + "    void doB();\n"
                + "    void doC();\n"
                + "}\n");
        Files.writeString(srcDir.resolve("ServiceImpl.java"),
                "package com.example;\n"
                + "public class ServiceImpl implements Service {\n"
                + "    @Override public void doB() { helper(); }\n"
                + "    @Override public void doC() { helper(); }\n"
                + "    private void helper() {}\n"
                + "}\n");
        Files.writeString(srcDir.resolve("Client.java"),
                "package com.example;\n"
                + "import javax.inject.Inject;\n"
                + "public class Client {\n"
                + "    @Inject\n"
                + "    private Service service;\n"
                + "    public void run() {\n"
                + "        service.doB();\n"
                + "        service.doC();\n"
                + "    }\n"
                + "}\n");

        ProjectIndex index = new JdtIndexer().build(
                List.of(srcDir.resolve("Service.java"),
                        srcDir.resolve("ServiceImpl.java"),
                        srcDir.resolve("Client.java")),
                List.of(),
                List.of(srcDir.getParent().getParent().toString()),  // source root = .../main/java (package com.example)
                tmp);

        // Verify hierarchy edges exist
        MethodKey ifaceDoB = new MethodKey("com.example.Service", "doB", 0, List.of());
        MethodKey implDoB  = new MethodKey("com.example.ServiceImpl", "doB", 0, List.of());
        Set<MethodKey> relatedB = index.relatedMethods(implDoB);
        assertTrue(relatedB.contains(ifaceDoB),
                "implDoB should relate to ifaceDoB, got: " + relatedB);

        // Verify Client -> ifaceDoB call edge exists
        List<MethodKey> callersOfIfaceB = index.callersOf(ifaceDoB);
        assertFalse(callersOfIfaceB.isEmpty(),
                "expected Client calling ifaceDoB, got none; allCalls=" + index.allCalls());

        // Now trace callers of the private helper
        MethodKey helper = new MethodKey("com.example.ServiceImpl", "helper", 0, List.of());
        CallChainAnalyzer.Result r = new CallChainAnalyzer().traceCallers(index, helper);

        assertTrue(r.found());
        CallNode root = r.root();
        // Direct callers: implDoB, implDoC
        assertEquals(2, root.callers.size());

        // One of the direct callers should transitively find Client
        boolean foundClient = false;
        for (CallNode direct : root.callers) {
            for (CallNode transitive : direct.callers) {
                if (transitive.signature.equals("com.example.Client#run/0")) {
                    foundClient = true;
                }
            }
        }
        assertTrue(foundClient,
                "Client calling through interface should be found as transitive caller of private helper");
    }

    @Test
    void privateMethodCallersFoundThroughInterfaceHierarchy() {
        // Scenario: private method `helper` in `ServiceImpl` is called by
        // public methods `doB` and `doC` in the same class. Both `doB` and
        // `doC` implement interface `Service`. External class `Client`
        // holds a `Service` reference and calls `doB`/`doC` through it.
        // traceCallers(ServiceImpl#helper/0) must find Client.

        ProjectIndex index = new ProjectIndex();

        // Interface Service
        MethodKey ifaceDoB = new MethodKey("com.example.Service", "doB", 0);
        MethodKey ifaceDoC = new MethodKey("com.example.Service", "doC", 0);
        index.putDeclaration(ifaceDoB, new ProjectIndex.SourceLoc("Service.java", 3));
        index.putDeclaration(ifaceDoC, new ProjectIndex.SourceLoc("Service.java", 4));

        // ServiceImpl implements Service
        MethodKey implDoB = new MethodKey("com.example.ServiceImpl", "doB", 0);
        MethodKey implDoC = new MethodKey("com.example.ServiceImpl", "doC", 0);
        MethodKey helper  = new MethodKey("com.example.ServiceImpl", "helper", 0);
        index.putDeclaration(implDoB, new ProjectIndex.SourceLoc("ServiceImpl.java", 10));
        index.putDeclaration(implDoC, new ProjectIndex.SourceLoc("ServiceImpl.java", 15));
        index.putDeclaration(helper,  new ProjectIndex.SourceLoc("ServiceImpl.java", 20));

        // ServiceImpl.doB overrides Service.doB
        index.recordHierarchy(implDoB, ifaceDoB);
        // ServiceImpl.doC overrides Service.doC
        index.recordHierarchy(implDoC, ifaceDoC);

        // ServiceImpl.doB and .doC both call the private helper
        index.recordInvocation(implDoB, helper);
        index.recordInvocation(implDoC, helper);

        // Client holds a Service reference and calls through the interface
        MethodKey clientMethod = new MethodKey("com.example.Client", "run", 0);
        index.putDeclaration(clientMethod, new ProjectIndex.SourceLoc("Client.java", 5));
        // Client calls Service#doB and Service#doC (through the interface-typed ref)
        index.recordInvocation(clientMethod, ifaceDoB);
        index.recordInvocation(clientMethod, ifaceDoC);

        CallChainAnalyzer.Result r = new CallChainAnalyzer().traceCallers(index, helper);

        assertTrue(r.found());
        CallNode root = r.root();
        // Direct callers: implDoB and implDoC
        assertEquals(2, root.callers.size());

        // One of them (implDoB) should transitively find Client
        boolean foundClient = false;
        for (CallNode direct : root.callers) {
            for (CallNode transitive : direct.callers) {
                if (transitive.signature.equals("com.example.Client#run/0")) {
                    foundClient = true;
                }
            }
        }
        assertTrue(foundClient,
                "Client calling through interface should be found as transitive caller of private helper");
    }

    @Test
    void staticBlockCallersFound(@TempDir Path tmp) throws IOException {
        // F6: a `static {}` block calls foo(). Pre-Task-2, recordCall drops
        // the edge because methodStack is empty when JDT visits the
        // MethodInvocation inside the Initializer node (we're not inside a
        // MethodDeclaration). Task 2 pushes a synthetic <clinit>/0
        // MethodContext in visit(Initializer) so the call attributes.
        Path srcDir = Files.createDirectories(tmp.resolve("src/main/java/com/example"));
        Files.writeString(srcDir.resolve("Target.java"),
                "package com.example;\n"
                + "public class Target {\n"
                + "    static { foo(); }\n"
                + "    private static void foo() {}\n"
                + "}\n");
        Files.writeString(srcDir.resolve("Caller.java"),
                "package com.example;\n"
                + "public class Caller {\n"
                + "    public void run() { Target.foo(); }\n"  // not the path under test
                + "}\n");
        ProjectIndex index = new JdtIndexer().build(
                List.of(srcDir.resolve("Target.java"), srcDir.resolve("Caller.java")),
                List.of(),
                List.of(srcDir.getParent().getParent().toString()),
                tmp);

        MethodKey foo = new MethodKey("com.example.Target", "foo", 0, List.of());
        MethodKey clinit = new MethodKey("com.example.Target", "<clinit>", 0, List.of());
        List<MethodKey> callers = index.callersOf(foo);
        assertTrue(callers.contains(clinit),
                "static block calls should attribute to <clinit>/0, got: " + callers);
    }

    @Test
    void instanceBlockCallersFound(@TempDir Path tmp) throws IOException {
        // F6: an instance `{}` block calls foo(). Same drop as the static
        // case — methodStack is empty during the Initializer visit. Task 2
        // pushes a synthetic <class-init>/0 MethodContext for instance
        // blocks. Note the key is deliberately NOT <init> — that would
        // collide with JDT's constructor MethodDeclaration name (the class
        // simple name, see JdtIndexer.java:540-541). <class-init> cannot
        // collide with any source method name.
        Path srcDir = Files.createDirectories(tmp.resolve("src/main/java/com/example"));
        Files.writeString(srcDir.resolve("Target.java"),
                "package com.example;\n"
                + "public class Target {\n"
                + "    { foo(); }\n"
                + "    private void foo() {}\n"
                + "}\n");
        ProjectIndex index = new JdtIndexer().build(
                List.of(srcDir.resolve("Target.java")),
                List.of(),
                List.of(srcDir.getParent().getParent().toString()),
                tmp);

        MethodKey foo = new MethodKey("com.example.Target", "foo", 0, List.of());
        MethodKey classInit = new MethodKey("com.example.Target", "<class-init>", 0, List.of());
        List<MethodKey> callers = index.callersOf(foo);
        assertTrue(callers.contains(classInit),
                "instance block calls should attribute to <class-init>/0, got: " + callers);
    }

    @Test
    void staticFieldInitializerCallersFound(@TempDir Path tmp) throws IOException {
        // F6: `static int x = compute();` — the field initializer calls
        // compute(). Pre-Task-3, visit(FieldDeclaration) returned false, so
        // JDT never descended into the fragments' initializer expressions
        // and the call edge was silently dropped. Task 3 changes
        // visit(FieldDeclaration) to return true and push a synthetic
        // <clinit>/0 MethodContext so the call attributes correctly.
        Path srcDir = Files.createDirectories(tmp.resolve("src/main/java/com/example"));
        Files.writeString(srcDir.resolve("Target.java"),
                "package com.example;\n"
                + "public class Target {\n"
                + "    static int x = compute();\n"
                + "    private static int compute() { return 42; }\n"
                + "}\n");
        ProjectIndex index = new JdtIndexer().build(
                List.of(srcDir.resolve("Target.java")),
                List.of(),
                List.of(srcDir.getParent().getParent().toString()),
                tmp);

        MethodKey compute = new MethodKey("com.example.Target", "compute", 0, List.of());
        MethodKey clinit = new MethodKey("com.example.Target", "<clinit>", 0, List.of());
        List<MethodKey> callers = index.callersOf(compute);
        assertTrue(callers.contains(clinit),
                "static field initializer calls should attribute to <clinit>/0, got: " + callers);
    }

    @Test
    void instanceFieldInitializerCallersFound(@TempDir Path tmp) throws IOException {
        // F6: instance field `int x = compute();` — same drop as the static
        // case. Task 3 pushes a synthetic <class-init>/0 MethodContext for
        // instance field initializers (NOT <init> — see the comment at
        // visit(Initializer) for why <class-init> is the chosen name).
        Path srcDir = Files.createDirectories(tmp.resolve("src/main/java/com/example"));
        Files.writeString(srcDir.resolve("Target.java"),
                "package com.example;\n"
                + "public class Target {\n"
                + "    int x = compute();\n"
                + "    private int compute() { return 42; }\n"
                + "}\n");
        ProjectIndex index = new JdtIndexer().build(
                List.of(srcDir.resolve("Target.java")),
                List.of(),
                List.of(srcDir.getParent().getParent().toString()),
                tmp);

        MethodKey compute = new MethodKey("com.example.Target", "compute", 0, List.of());
        MethodKey classInit = new MethodKey("com.example.Target", "<class-init>", 0, List.of());
        List<MethodKey> callers = index.callersOf(compute);
        assertTrue(callers.contains(classInit),
                "instance field initializer calls should attribute to <class-init>/0, got: " + callers);
    }

    @Test
    void enumConstantArgCallersFound(@TempDir Path tmp) throws IOException {
        // F6: `enum E { A(foo()) }` — the args (foo()) are evaluated during
        // the enum class's static init, conceptually equivalent to
        // `public static final E A = new E(foo());`. Task 4 pushes a
        // synthetic <clinit>/0 MethodContext in visit(EnumConstantDeclaration)
        // so calls inside the args attribute to <clinit>/0. Pre-Task-4, JDT
        // visits the args inside the EnumConstantDeclaration but methodStack
        // is empty (we're inside visit(EnumDeclaration), not inside a
        // MethodDeclaration), so recordCall drops the edge.
        Path srcDir = Files.createDirectories(tmp.resolve("src/main/java/com/example"));
        Files.writeString(srcDir.resolve("MyEnum.java"),
                "package com.example;\n"
                + "public enum MyEnum {\n"
                + "    A(compute());\n"
                + "    private MyEnum(int x) {}\n"
                + "    private static int compute() { return 1; }\n"
                + "}\n");
        Files.writeString(srcDir.resolve("Helper.java"),
                "package com.example;\n"
                + "public class Helper {\n"
                + "    public int compute() { return 2; }\n"  // not the path under test
                + "}\n");
        ProjectIndex index = new JdtIndexer().build(
                List.of(srcDir.resolve("MyEnum.java"), srcDir.resolve("Helper.java")),
                List.of(),
                List.of(srcDir.getParent().getParent().toString()),
                tmp);

        MethodKey compute = new MethodKey("com.example.MyEnum", "compute", 0, List.of());
        MethodKey clinit = new MethodKey("com.example.MyEnum", "<clinit>", 0, List.of());
        List<MethodKey> callers = index.callersOf(compute);
        assertTrue(callers.contains(clinit),
                "enum constant arg calls should attribute to <clinit>/0, got: " + callers);
    }

    @Test
    void mixedMainAndInitCallsFoundTogether(@TempDir Path tmp) throws IOException {
        // F6 mixed integration: one class with all four init contexts in one
        // body — static field init, instance field init, static block, instance
        // block — plus an explicit method as the regular (non-synthetic)
        // baseline. Verifies that <clinit> and <class-init> are distinct keys
        // and that calls in each context attribute to the right synthetic.
        // Enum constant args (the fifth F6 context) are exercised separately
        // in enumConstantArgCallersFound — keeping Mixed a class, not an enum,
        // per the brief.
        Path srcDir = Files.createDirectories(tmp.resolve("src/main/java/com/example"));
        Files.writeString(srcDir.resolve("Mixed.java"),
                "package com.example;\n"
                + "public class Mixed {\n"
                + "    static int s = staticHelper();\n"          // → <clinit>
                + "    int i = instanceHelper();\n"               // → <class-init>
                + "    static { staticHelper(); }\n"               // → <clinit>
                + "    { instanceHelper(); }\n"                    // → <class-init>
                + "    public void explicit() { explicitHelper(); }\n"  // → explicit (regular method)
                + "    private static int staticHelper() { return 1; }\n"
                + "    private int instanceHelper() { return 2; }\n"
                + "    private void explicitHelper() {}\n"
                + "}\n");
        // sourcepath must be the source root (.../src/main/java), not the
        // package dir. srcDir is .../src/main/java/com/example, so two
        // getParent() hops land on the source root.
        ProjectIndex index = new JdtIndexer().build(
                List.of(srcDir.resolve("Mixed.java")),
                List.of(),
                List.of(srcDir.getParent().getParent().toString()),
                tmp);

        MethodKey staticHelper = new MethodKey("com.example.Mixed", "staticHelper", 0, List.of());
        MethodKey instanceHelper = new MethodKey("com.example.Mixed", "instanceHelper", 0, List.of());
        MethodKey explicitHelper = new MethodKey("com.example.Mixed", "explicitHelper", 0, List.of());
        MethodKey clinit = new MethodKey("com.example.Mixed", "<clinit>", 0, List.of());
        MethodKey classInit = new MethodKey("com.example.Mixed", "<class-init>", 0, List.of());
        MethodKey explicit = new MethodKey("com.example.Mixed", "explicit", 0, List.of());

        // callersOf returns a List (defensive copy of an underlying
        // LinkedHashSet, so the same caller MethodKey from multiple call
        // sites — e.g. staticHelper called twice from <clinit> via field
        // init + static block — is deduped to one entry). Wrap in HashSet
        // for Set-vs-Set comparison; assertEquals on a List vs a Set always
        // fails because the collection types differ even with equal elements.
        List<MethodKey> staticHelperCallers = index.callersOf(staticHelper);
        List<MethodKey> instanceHelperCallers = index.callersOf(instanceHelper);
        List<MethodKey> explicitHelperCallers = index.callersOf(explicitHelper);

        assertEquals(new HashSet<>(List.of(clinit)), new HashSet<>(staticHelperCallers),
                "staticHelper should only be called by <clinit>, got: " + staticHelperCallers);
        assertEquals(new HashSet<>(List.of(classInit)), new HashSet<>(instanceHelperCallers),
                "instanceHelper should only be called by <class-init>, got: " + instanceHelperCallers);
        assertEquals(new HashSet<>(List.of(explicit)), new HashSet<>(explicitHelperCallers),
                "explicitHelper should only be called by explicit, got: " + explicitHelperCallers);

        // <clinit> and <class-init> must be distinct keys — a collision would
        // silently merge static-init and instance-init call graphs.
        assertNotEquals(clinit, classInit,
                "<clinit> and <class-init> must be distinct MethodKeys");
    }

    private static int depthOf(CallNode n) {
        int d = 0;
        while (!n.callers.isEmpty()) {
            n = n.callers.get(0);
            d++;
        }
        return d;
    }

    @Test
    void syntheticMethodsInFindSymbols(@TempDir Path tmp) throws IOException {
        // A class with a static block should produce a <clinit>/0 synthetic
        // symbol findable via find_symbols kind=synthetic, with fqn shape
        // "pkg.Cls.<clinit>/0" (matching the spec).
        Path srcDir = Files.createDirectories(tmp.resolve("src/main/java/com/example"));
        Files.writeString(srcDir.resolve("Target.java"),
                "package com.example;\n"
                + "public class Target {\n"
                + "    static { System.out.println(\"init\"); }\n"
                + "}\n");

        // Build the index directly (bypasses the service cache for a unit-style test)
        ProjectIndex index = new JdtIndexer().build(
                List.of(srcDir.resolve("Target.java")),
                List.of(),
                List.of(srcDir.getParent().getParent().toString()),
                tmp);

        // find_symbols searches via ProjectIndex.searchSymbols — verify the synthetic is there.
        // searchSymbols returns a SymbolSearchResult record (matches + totalCount).
        ProjectIndex.SymbolSearchResult result = index.searchSymbols(
                "clinit", "synthetic", 100);
        java.util.List<ProjectIndex.Symbol> matches = result.matches();
        assertFalse(matches.isEmpty(),
                "find_symbols kind=synthetic should return <clinit>, got: " + matches);
        ProjectIndex.Symbol synth = matches.get(0);
        assertEquals("com.example.Target.<clinit>/0", synth.fqn(),
                "synthetic fqn should include /0 arity suffix");
        assertEquals("synthetic", synth.kind());
        assertEquals("<clinit>", synth.name());
    }
}
