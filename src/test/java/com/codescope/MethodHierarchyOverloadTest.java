package com.codescope;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression: hierarchy edges must match on parameter types, not just
 * name + arity. A class that declares {@code m(String)} and
 * {@code m(int)} as overloads should NOT have its {@code m(int)}
 * override candidate cross-link to the parent's {@code m(String)}
 * (or vice versa) — those are separate methods, not overrides.
 *
 * <p>Without the param-type check, the BFS would expand unrelated
 * overloads as if they were virtual-dispatch siblings, surfacing
 * callers of the wrong method when a user traces one of the two.
 */
class MethodHierarchyOverloadTest {

    @Test
    void unrelatedOverloadIsNotTreatedAsOverride(@TempDir Path tmp) throws IOException {
        // Parent has both m(String) and m(int) as overloads.
        // Child overrides only m(int) (its own m(int)); m(String) is
        // inherited from Parent and not re-declared. CallerA calls
        // child.m(int) — this should be a caller of (Parent.m(int) or
        // Child.m(int)), NOT of Parent.m(String).
        Path srcDir = Files.createDirectories(tmp.resolve("src/main/java/com/example"));
        Files.writeString(srcDir.resolve("Parent.java"), """
                package com.example;
                public class Parent {
                    public void m(String s) {}
                    public void m(int x) {}
                }
                """);
        Files.writeString(srcDir.resolve("Child.java"), """
                package com.example;
                public class Child extends Parent {
                    @Override
                    public void m(int x) {}
                }
                """);
        Files.writeString(srcDir.resolve("CallerA.java"), """
                package com.example;
                public class CallerA {
                    public void go() { new Child().m(42); }
                }
                """);
        Files.writeString(srcDir.resolve("CallerB.java"), """
                package com.example;
                public class CallerB {
                    public void go() { new Child().m("hi"); }
                }
                """);

        ProjectIndex index = new JdtIndexer().build(
                List.of(
                        srcDir.resolve("Parent.java"),
                        srcDir.resolve("Child.java"),
                        srcDir.resolve("CallerA.java"),
                        srcDir.resolve("CallerB.java")),
                List.of(),
                List.of(srcDir.getParent().getParent().toString()),
                tmp);

        MethodKey parentMString = new MethodKey("com.example.Parent", "m", 1, List.of("java.lang.String"));
        MethodKey parentMInt = new MethodKey("com.example.Parent", "m", 1, List.of("int"));
        MethodKey childMInt = new MethodKey("com.example.Child", "m", 1, List.of("int"));

        // Parent.m(String)'s relatedMethods must NOT include the
        // (Child.m(int) or Parent.m(int)) candidates — they're not
        // overrides of m(String), they're sibling overloads. Without
        // the param-type gate, the repair pass would link them all
        // together.
        Set<MethodKey> stringRelated = index.relatedMethods(parentMString);
        assertEquals(Set.of(parentMString), stringRelated,
                "Parent.m(String) must not be hierarchy-related to m(int) overloads, got: "
                        + stringRelated);

        // Sanity: the int-overload side is correctly linked.
        Set<MethodKey> intRelated = index.relatedMethods(parentMInt);
        assertTrue(intRelated.contains(parentMInt),
                "Parent.m(int) must be self-related: " + intRelated);
        assertTrue(intRelated.contains(childMInt),
                "Parent.m(int) must be related to Child.m(int) override: " + intRelated);
        assertEquals(2, intRelated.size(),
                "Parent.m(int) and Child.m(int) only — no other siblings: " + intRelated);
    }

    @Test
    void tracingMStringDoesNotSurfaceCallersOfMInt(@TempDir Path tmp) throws IOException {
        // Same fixture as above; the BFS-level check that the bug
        // would actually mislead a real user. Tracing Parent.m(String)
        // should return only the inherited caller (CallerB), not
        // CallerA (which calls the int-overload through Child).
        Path srcDir = Files.createDirectories(tmp.resolve("src/main/java/com/example"));
        Files.writeString(srcDir.resolve("Parent.java"), """
                package com.example;
                public class Parent {
                    public void m(String s) {}
                    public void m(int x) {}
                }
                """);
        Files.writeString(srcDir.resolve("Child.java"), """
                package com.example;
                public class Child extends Parent {
                    @Override
                    public void m(int x) {}
                }
                """);
        Files.writeString(srcDir.resolve("CallerA.java"), """
                package com.example;
                public class CallerA {
                    public void go() { new Child().m(42); }
                }
                """);
        Files.writeString(srcDir.resolve("CallerB.java"), """
                package com.example;
                public class CallerB {
                    public void go() { new Child().m("hi"); }
                }
                """);

        ProjectIndex index = new JdtIndexer().build(
                List.of(
                        srcDir.resolve("Parent.java"),
                        srcDir.resolve("Child.java"),
                        srcDir.resolve("CallerA.java"),
                        srcDir.resolve("CallerB.java")),
                List.of(),
                List.of(srcDir.getParent().getParent().toString()),
                tmp);

        MethodKey parentMString = new MethodKey("com.example.Parent", "m", 1, List.of("java.lang.String"));
        List<MethodKey> callers = index.callersOf(parentMString);
        // Direct callers of Parent.m(String) via the calls map: only CallerB
        // (JDT binds new Child().m("hi") to Parent.m(String) because Child
        // doesn't redeclare it). CallerA binds to Child.m(int) (or
        // Parent.m(int) depending on JDT's resolution), not m(String).
        assertTrue(callers.stream().anyMatch(k -> k.declaringClass.equals("com.example.CallerB")),
                "CallerB must be a direct caller of Parent.m(String), got: " + callers);
        assertFalse(callers.stream().anyMatch(k -> k.declaringClass.equals("com.example.CallerA")),
                "CallerA calls Child.m(int) — must not surface under Parent.m(String), got: "
                        + callers);
    }
}
