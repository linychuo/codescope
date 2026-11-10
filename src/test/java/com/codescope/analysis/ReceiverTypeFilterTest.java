package com.codescope.analysis;

import static org.junit.jupiter.api.Assertions.*;
import com.codescope.index.JdtIndexer;
import com.codescope.index.ProjectIndex;
import com.codescope.model.MethodKey;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Regressions for issues #7 and #8: hierarchy expansion must be filtered by
 * the receiver's static type.
 *
 * <p>Issue #7 (sibling subclass caller merge): tracing {@code ChildA#save}
 * used to pull in {@code ChildB#save}, because both are recorded as callers
 * of the shared ancestor {@code Parent#save} and the BFS expanded the
 * undirected hierarchy group without regard to the receiver.
 *
 * <p>Issue #8 (no receiver static type filter): a call site that JDT binds
 * to an ancestor method on a receiver that cannot be the target's class
 * (e.g. {@code new ChildA().save()} where {@code ChildA} inherits
 * {@code save} and the target is {@code ChildB#save}) used to surface as a
 * caller of the target.
 *
 * <p>The fix records each call site's receiver static type
 * ({@link ProjectIndex.CallSite#receiverType()}) and, when the BFS expands
 * a frame to an <em>ancestor</em> method, keeps only the call sites whose
 * receiver type is a supertype of the frame method's declaring class.
 */
class ReceiverTypeFilterTest {

    private static ProjectIndex build(Path srcDir, Path tmp, String... files) throws Exception {
        List<Path> sources = new java.util.ArrayList<>();
        for (String f : files) sources.add(srcDir.resolve(f));
        return new JdtIndexer().build(sources, List.of(),
                List.of(srcDir.getParent().getParent().toString()), tmp);
    }

    /** Flattens the set of class simple names appearing in a trace tree. */
    private static java.util.Set<String> callerClasses(CallNode node) {
        java.util.Set<String> out = new java.util.HashSet<>();
        for (CallNode c : node.callers) {
            out.add(c.className);
            out.addAll(callerClasses(c));
        }
        return out;
    }

    @Test
    void siblingOverrideCallersAreNotMerged(@TempDir Path tmp) throws Exception {
        // Parent#save(String) is overridden by two sibling subclasses, each
        // of which calls super.save(). Tracing ChildA#save must surface
        // CallerA (which calls ChildA#save) and CallerP (which calls save on
        // a Parent-typed reference) — but NOT ChildB#save, whose super call
        // targets Parent#save and can never dispatch to ChildA#save.
        Path srcDir = Files.createDirectories(tmp.resolve("src/main/java/com/example"));
        Files.writeString(srcDir.resolve("Parent.java"), """
                package com.example;
                public class Parent {
                    public void save(String s) {}
                }
                """);
        Files.writeString(srcDir.resolve("ChildA.java"), """
                package com.example;
                public class ChildA extends Parent {
                    @Override public void save(String s) { super.save(s); }
                }
                """);
        Files.writeString(srcDir.resolve("ChildB.java"), """
                package com.example;
                public class ChildB extends Parent {
                    @Override public void save(String s) { super.save(s); }
                }
                """);
        Files.writeString(srcDir.resolve("CallerA.java"), """
                package com.example;
                public class CallerA { void go() { new ChildA().save("x"); } }
                """);
        Files.writeString(srcDir.resolve("CallerB.java"), """
                package com.example;
                public class CallerB { void go() { new ChildB().save("x"); } }
                """);
        Files.writeString(srcDir.resolve("CallerP.java"), """
                package com.example;
                public class CallerP { void go(Parent p) { p.save("x"); } }
                """);
        ProjectIndex index = build(srcDir, tmp, "Parent.java", "ChildA.java", "ChildB.java",
                "CallerA.java", "CallerB.java", "CallerP.java");

        MethodKey childA = index.resolveTarget("com.example.ChildA", "save");
        assertNotNull(childA);
        CallChainAnalyzer.Result r = new CallChainAnalyzer().traceCallers(index, childA);
        assertTrue(r.found());

        java.util.Set<String> classes = callerClasses(r.root());
        assertTrue(classes.contains("com.example.CallerA"),
                "CallerA calls ChildA#save directly, must be present: " + classes);
        assertTrue(classes.contains("com.example.CallerP"),
                "a Parent-typed receiver can hold a ChildA, so CallerP must be present: " + classes);
        assertFalse(classes.contains("com.example.ChildB"),
                "issue #7: ChildB#save is a sibling override, not a caller of ChildA#save: "
                        + classes);
        assertFalse(classes.contains("com.example.CallerB"),
                "issue #7: CallerB calls ChildB#save, which must not leak into ChildA#save: "
                        + classes);
    }

    @Test
    void inheritedMethodCallOnSiblingReceiverIsFilteredOut(@TempDir Path tmp) throws Exception {
        // Issue #8: ChildA inherits save() from Parent (no override), so
        // `new ChildA().save("x")` binds to Parent#save with a ChildA
        // receiver. ChildB overrides save(). Tracing ChildB#save must not
        // attribute that ChildA-receiver call site to ChildB — a ChildA
        // instance can never dispatch to ChildB#save.
        Path srcDir = Files.createDirectories(tmp.resolve("src/main/java/com/example"));
        Files.writeString(srcDir.resolve("Parent.java"), """
                package com.example;
                public class Parent {
                    public void save(String s) {}
                }
                """);
        Files.writeString(srcDir.resolve("ChildA.java"), """
                package com.example;
                public class ChildA extends Parent { }
                """);
        Files.writeString(srcDir.resolve("ChildB.java"), """
                package com.example;
                public class ChildB extends Parent {
                    @Override public void save(String s) {}
                }
                """);
        Files.writeString(srcDir.resolve("CallerA.java"), """
                package com.example;
                public class CallerA { void go() { new ChildA().save("x"); } }
                """);
        Files.writeString(srcDir.resolve("CallerB.java"), """
                package com.example;
                public class CallerB { void go() { new ChildB().save("x"); } }
                """);
        Files.writeString(srcDir.resolve("CallerP.java"), """
                package com.example;
                public class CallerP { void go(Parent p) { p.save("x"); } }
                """);
        ProjectIndex index = build(srcDir, tmp, "Parent.java", "ChildA.java", "ChildB.java",
                "CallerA.java", "CallerB.java", "CallerP.java");

        MethodKey childB = index.resolveTarget("com.example.ChildB", "save");
        assertNotNull(childB);
        CallChainAnalyzer.Result r = new CallChainAnalyzer().traceCallers(index, childB);
        assertTrue(r.found());

        java.util.Set<String> classes = callerClasses(r.root());
        assertTrue(classes.contains("com.example.CallerB"),
                "CallerB calls ChildB#save directly, must be present: " + classes);
        assertTrue(classes.contains("com.example.CallerP"),
                "a Parent-typed receiver can hold a ChildB, so CallerP must be present: " + classes);
        assertFalse(classes.contains("com.example.CallerA"),
                "issue #8: CallerA's receiver is statically ChildA, which cannot dispatch to "
                        + "ChildB#save: " + classes);
    }

    @Test
    void interfaceReceiverFilterSeparatesSiblingImplementations(@TempDir Path tmp) throws Exception {
        // Same shape through an interface: A and B both implement I. B does
        // not redeclare m(), so `b.m()` binds to I#m on a B receiver. That
        // call site must not surface when tracing A#m.
        Path srcDir = Files.createDirectories(tmp.resolve("src/main/java/com/example"));
        Files.writeString(srcDir.resolve("I.java"), """
                package com.example;
                public interface I { void m(); }
                """);
        Files.writeString(srcDir.resolve("A.java"), """
                package com.example;
                public class A implements I { @Override public void m() {} }
                """);
        Files.writeString(srcDir.resolve("B.java"), """
                package com.example;
                public class B implements I { }
                """);
        Files.writeString(srcDir.resolve("CallerA.java"), """
                package com.example;
                public class CallerA { void go() { new A().m(); } }
                """);
        Files.writeString(srcDir.resolve("CallerB.java"), """
                package com.example;
                public class CallerB { void go(B b) { b.m(); } }
                """);
        Files.writeString(srcDir.resolve("CallerI.java"), """
                package com.example;
                public class CallerI { void go(I i) { i.m(); } }
                """);
        ProjectIndex index = build(srcDir, tmp, "I.java", "A.java", "B.java",
                "CallerA.java", "CallerB.java", "CallerI.java");

        MethodKey aM = index.resolveTarget("com.example.A", "m");
        assertNotNull(aM);
        CallChainAnalyzer.Result r = new CallChainAnalyzer().traceCallers(index, aM);
        assertTrue(r.found());

        java.util.Set<String> classes = callerClasses(r.root());
        assertTrue(classes.contains("com.example.CallerA"),
                "CallerA calls A#m directly: " + classes);
        assertTrue(classes.contains("com.example.CallerI"),
                "an I-typed receiver can hold an A, so CallerI must be present: " + classes);
        assertFalse(classes.contains("com.example.CallerB"),
                "CallerB's receiver is statically B, which cannot dispatch to A#m: " + classes);
    }

    @Test
    void superCallOnThisIsAttributedToTheTargetClass(@TempDir Path tmp) throws Exception {
        // The super call inside ChildA must remain a caller of ChildA#save
        // (it dispatches on `this`, a ChildA). Recording the superclass as
        // the receiver type would wrongly drop it.
        Path srcDir = Files.createDirectories(tmp.resolve("src/main/java/com/example"));
        Files.writeString(srcDir.resolve("Parent.java"), """
                package com.example;
                public class Parent {
                    public void save(String s) {}
                }
                """);
        Files.writeString(srcDir.resolve("ChildA.java"), """
                package com.example;
                public class ChildA extends Parent {
                    @Override public void save(String s) { super.save(s); }
                }
                """);
        ProjectIndex index = build(srcDir, tmp, "Parent.java", "ChildA.java");

        MethodKey parentSave = index.resolveTarget("com.example.Parent", "save");
        assertNotNull(parentSave);
        assertTrue(index.callersOf(parentSave).stream()
                        .anyMatch(k -> k.declaringClass.equals("com.example.ChildA")),
                "super.save() in ChildA must be a caller of Parent#save");

        // And the receiver type recorded for that site is ChildA (this), not
        // Parent — the load-bearing fact for the issue #7 / #8 filter.
        Map<MethodKey, List<ProjectIndex.CallSite>> sites = index.callSitesOf(parentSave);
        ProjectIndex.CallSite superSite = sites.get(index.resolveTarget("com.example.ChildA", "save"))
                .get(0);
        assertEquals("com.example.ChildA", superSite.receiverType(),
                "super.save() dispatches on `this` (ChildA), not on the declaring superclass");
    }
}
