package com.codescope;

import com.fasterxml.jackson.databind.ObjectMapper;
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
    void deepCallChainDoesNotOverflowStack() {
        // Build a synthetic index with a deep call chain.
        // That used to overflow CallNode.toJson() when it was recursive.
        // We use a depth well above the JVM's default stack limit (~1000
        // frames) but small enough to keep the test fast.
        int depth = 2_000;
        ProjectIndex index = new ProjectIndex();
        MethodKey prev = new MethodKey("com.example.L0", "m", 0);
        index.putDeclaration(prev, new ProjectIndex.SourceLoc("L0.java", 1));
        for (int i = 1; i < depth; i++) {
            MethodKey cur = new MethodKey("com.example.L" + i, "m", 0);
            index.putDeclaration(cur, new ProjectIndex.SourceLoc("L" + i + ".java", 1));
            index.addCall(cur, prev);   // prev calls cur
            prev = cur;
        }
        // After the loop, prev is the deepest node. traceCallers should
        // produce a chain of (depth-1) callers.
        CallChainAnalyzer.Result r = new CallChainAnalyzer().traceCallers(index, prev);
        assertTrue(r.found());

        CallNode root = r.root();
        int actualDepth = depthOf(root);
        assertEquals(depth - 1, actualDepth, "expected chain depth of " + (depth - 1));

        // toJson() is iterative and must not throw StackOverflowError on deep
        // trees. (Jackson's StreamWriteConstraints would later reject >1000
        // nesting, but that's a downstream concern — we only assert the
        // in-process tree-build succeeds.)
        ObjectMapper m = new ObjectMapper();
        Map<String, Object> tree = root.toJson();
        assertNotNull(tree);
        // On a chain short enough for Jackson, it should serialize fine.
        assertDoesNotThrow(() -> m.writeValueAsString(smallChainJson()));
    }

    private static Map<String, Object> smallChainJson() {
        // A 3-deep chain that Jackson can happily serialize.
        ProjectIndex idx = new ProjectIndex();
        MethodKey a = new MethodKey("com.example.A", "m", 0);
        MethodKey b = new MethodKey("com.example.B", "m", 0);
        MethodKey c = new MethodKey("com.example.C", "m", 0);
        idx.putDeclaration(a, new ProjectIndex.SourceLoc("A.java", 1));
        idx.putDeclaration(b, new ProjectIndex.SourceLoc("B.java", 1));
        idx.putDeclaration(c, new ProjectIndex.SourceLoc("C.java", 1));
        idx.addCall(a, b);
        idx.addCall(b, c);
        return new CallChainAnalyzer().traceCallers(idx, a).root().toJson();
    }

    @Test
    void cyclicGraphIsCollapsedToBackEdgeMarker() {
        // a -> b -> a (cycle). Both a and b are declared. We ask for callers of a.
        ProjectIndex index = new ProjectIndex();
        MethodKey a = new MethodKey("com.example.A", "f", 0);
        MethodKey b = new MethodKey("com.example.B", "f", 0);
        index.putDeclaration(a, new ProjectIndex.SourceLoc("A.java", 1));
        index.putDeclaration(b, new ProjectIndex.SourceLoc("B.java", 1));
        index.addCall(a, b);  // b calls a
        index.addCall(b, a);  // a calls b

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
            index.addCall(target, caller);
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
    void targetNotFoundReturnsFailureResult() {
        ProjectIndex index = new ProjectIndex();
        CallChainAnalyzer.Result r = new CallChainAnalyzer().traceCallers(index,
                new MethodKey("com.example.Nope", "missing", 0));
        assertFalse(r.found());
        assertTrue(r.message().contains("not found"), r.message());
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
                            index.addCall(target, caller);
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
                            index.addCall(target,
                                    new MethodKey("com.example.W" + i, "f", 0));
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

    private static int depthOf(CallNode n) {
        int d = 0;
        while (!n.callers.isEmpty()) {
            n = n.callers.get(0);
            d++;
        }
        return d;
    }
}
