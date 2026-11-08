package com.codescope.analysis;

import static org.junit.jupiter.api.Assertions.*;
import com.codescope.index.JdtIndexer;
import com.codescope.index.ProjectIndex;
import com.codescope.model.MethodKey;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Regression tests for method-hierarchy edge cases the fixture project does
 * not cover: local classes, package-private visibility, record compact
 * constructors, generic type-variable overrides, and the diagnostic
 * message for pure self-recursion.
 */
class HierarchyEdgeCaseTest {

    private static ProjectIndex index(Path srcDir, Path tmp, String... files) throws Exception {
        List<Path> sources = new java.util.ArrayList<>();
        for (String f : files) sources.add(srcDir.resolve(f));
        return new JdtIndexer().build(sources, List.of(),
                List.of(srcDir.getParent().getParent().toString()), tmp);
    }

    @Test
    void localClassMethodsAreVisibleAndCalled(@TempDir Path tmp) throws Exception {
        Path srcDir = Files.createDirectories(tmp.resolve("src/main/java/com/example"));
        Files.writeString(srcDir.resolve("Outer.java"), """
                package com.example;
                public class Outer {
                    public void use() {
                        class Local { void lm() {} }
                        new Local().lm();
                    }
                }
                """);
        ProjectIndex index = index(srcDir, tmp, "Outer.java");

        MethodKey lm = null;
        for (MethodKey k : index.knownMethods()) {
            if (k.methodName.equals("lm")) { lm = k; break; }
        }
        assertNotNull(lm, "local class method lm must be indexed, known="
                + index.knownMethods());
        assertTrue(lm.declaringClass.contains("Local"),
                "declaring class must be the local class binary name, got: " + lm.declaringClass);
        assertFalse(index.callersOfSet(lm).isEmpty(),
                "the call from use() to the local class method must be recorded");
    }

    @Test
    void packagePrivateMethodIsNotLinkedAcrossPackages(@TempDir Path tmp) throws IOException {
        Path aDir = Files.createDirectories(tmp.resolve("src/main/java/a"));
        Path bDir = Files.createDirectories(tmp.resolve("src/main/java/b"));
        Files.writeString(aDir.resolve("A.java"), """
                package a;
                public class A { void foo() {} }
                """);
        Files.writeString(bDir.resolve("B.java"), """
                package b;
                public class B extends a.A { void foo() {} }
                """);
        Files.writeString(bDir.resolve("CallerB.java"), """
                package b;
                public class CallerB { void go() { new B().foo(); } }
                """);
        ProjectIndex index = new JdtIndexer().build(
                List.of(aDir.resolve("A.java"), bDir.resolve("B.java"),
                        bDir.resolve("CallerB.java")),
                List.of(), List.of(tmp.resolve("src/main/java").toString()), tmp);

        MethodKey aFoo = new MethodKey("a.A", "foo", 0, List.of());
        MethodKey bFoo = new MethodKey("b.B", "foo", 0, List.of());
        assertFalse(index.relatedMethods(aFoo).contains(bFoo),
                "package-private A.foo must not be linked to B.foo across packages, got: "
                        + index.relatedMethods(aFoo));
        assertFalse(index.callersOfSet(bFoo).isEmpty(),
                "B.foo must still have its own caller");
    }

    @Test
    void recordCompactConstructorMatchesCallSiteArity(@TempDir Path tmp) throws Exception {
        Path srcDir = Files.createDirectories(tmp.resolve("src/main/java/com/example"));
        Files.writeString(srcDir.resolve("Point.java"), """
                package com.example;
                public record Point(int x, int y) {
                    public Point {
                        if (x < 0) throw new IllegalArgumentException();
                    }
                }
                """);
        Files.writeString(srcDir.resolve("User.java"), """
                package com.example;
                public class User { void make() { new Point(1, 2); } }
                """);
        ProjectIndex index = index(srcDir, tmp, "Point.java", "User.java");

        MethodKey ctor = index.resolveTarget("com.example.Point", "Point", 2);
        assertNotNull(ctor, "compact constructor must resolve with arity 2");
        assertFalse(index.callersOfSet(ctor).isEmpty(),
                "new Point(1,2) must be a caller of the compact constructor");
    }

    @Test
    void genericTypeVariableOverrideIsLinked(@TempDir Path tmp) throws Exception {
        Path srcDir = Files.createDirectories(tmp.resolve("src/main/java/com/example"));
        Files.writeString(srcDir.resolve("I.java"), """
                package com.example;
                public interface I<T> { void bar(T t); }
                """);
        Files.writeString(srcDir.resolve("Impl.java"), """
                package com.example;
                public class Impl implements I<String> { public void bar(String t) {} }
                """);
        Files.writeString(srcDir.resolve("Use.java"), """
                package com.example;
                public class Use { void m2() { new Impl().bar("y"); } }
                """);
        ProjectIndex index = index(srcDir, tmp, "I.java", "Impl.java", "Use.java");

        MethodKey iBar = index.resolveTarget("com.example.I", "bar", 1);
        assertNotNull(iBar, "I.bar must resolve");
        CallChainAnalyzer.Result r = new CallChainAnalyzer().traceCallers(index, iBar);
        assertTrue(r.found());
        String json = r.root().toJson().toString();
        assertTrue(json.contains("Use"),
                "chain must reach Use through the generic override, got: " + json);
    }

    @Test
    void overloadsHintIncludesInheritedOverloads(@TempDir Path tmp) throws Exception {
        Path srcDir = Files.createDirectories(tmp.resolve("src/main/java/com/example"));
        Files.writeString(srcDir.resolve("Parent.java"), """
                package com.example;
                public class Parent { public void foo() {} public void foo(int x) {} }
                """);
        Files.writeString(srcDir.resolve("Child.java"), """
                package com.example;
                public class Child extends Parent {}
                """);
        ProjectIndex index = index(srcDir, tmp, "Parent.java", "Child.java");

        ProjectIndex.AmbiguousMethodException cause;
        try {
            index.resolveTarget("com.example.Child", "foo");
            fail("expected ambiguity for Child#foo");
            return;
        } catch (ProjectIndex.AmbiguousMethodException e) {
            cause = e;
        }
        String hint = MethodResolver.overloadsHint(index, "com.example.Child", "foo", cause);
        assertTrue(hint.contains("Parent"),
                "hint must mention the inherited overloads, got: " + hint);
    }

    @Test
    void pureSelfRecursionDoesNotReportNoCallers() {
        ProjectIndex index = new ProjectIndex();
        MethodKey f = new MethodKey("q.F", "f", 0);
        index.putDeclaration(f, new ProjectIndex.SourceLoc("F.java", 1));
        index.recordInvocation(f, f);   // f calls itself

        CallChainAnalyzer.Result r = new CallChainAnalyzer().traceCallers(index, f);
        assertFalse(r.message().startsWith("No callers found"),
                "self-recursion must not report 'No callers found', got: " + r.message());
    }

    @Test
    void duplicateCycleMarkersAreCollapsed() {
        ProjectIndex index = new ProjectIndex();
        MethodKey x = new MethodKey("q.X", "x", 0);
        MethodKey mInt = new MethodKey("q.Mid", "m", 1, List.of("int"));
        MethodKey mStr = new MethodKey("q.Mid", "m", 1, List.of("java.lang.String"));
        index.putDeclaration(x, new ProjectIndex.SourceLoc("X.java", 1));
        index.putDeclaration(mInt, new ProjectIndex.SourceLoc("Mid.java", 1));
        index.putDeclaration(mStr, new ProjectIndex.SourceLoc("Mid.java", 1));
        // Both overloads call back into X.x (a cycle), and are hierarchy-related.
        index.recordInvocation(x, mInt);
        index.recordInvocation(x, mStr);
        index.recordInvocation(mInt, x);
        index.recordInvocation(mStr, x);
        index.recordHierarchy(mInt, mStr);
        index.recordHierarchy(mStr, mInt);

        CallChainAnalyzer.Result r = new CallChainAnalyzer().traceCallers(index, x);
        int maxDuplicate = maxDuplicateCycleMarkers(r.root());
        assertEquals(1, maxDuplicate,
                "no single frame may emit the same ancestor cycle marker twice");
    }

    /** Largest number of identical cycle markers emitted under any single node. */
    private static int maxDuplicateCycleMarkers(CallNode node) {
        java.util.Map<String, Integer> counts = new java.util.HashMap<>();
        for (CallNode c : node.callers) {
            if (c.cycle) counts.merge(c.signature, 1, Integer::sum);
        }
        int max = counts.values().stream().mapToInt(Integer::intValue).max().orElse(0);
        for (CallNode c : node.callers) {
            max = Math.max(max, maxDuplicateCycleMarkers(c));
        }
        return max;
    }
}
