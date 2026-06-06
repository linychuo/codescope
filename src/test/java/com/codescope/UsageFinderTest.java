package com.codescope;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the value classes and simple data plumbing of UsageFinder.
 *
 * JDT-dependent integration tests are skipped here because the surefire classpath
 * inherits Eclipse JDT's mixed-signer classpath (production works because the
 * shaded JAR strips signatures, but the raw test classpath does not). The
 * end-to-end find-usages behavior is verified by the smoke tests in
 * `src/test/resources/find-usages-smoke.sh`, which invoke the shaded JAR.
 */
class UsageFinderTest {

    @Test
    void symbolKeyForClass() {
        UsageFinder.Symbol s = UsageFinder.Symbol.ofClass("com.example.Foo");
        assertEquals("com.example.Foo", s.key());
        assertEquals(UsageFinder.SymbolKind.CLASS, s.kind);
    }

    @Test
    void symbolKeyForMethod() {
        UsageFinder.Symbol s = UsageFinder.Symbol.ofMethod("com.example.Foo", "bar");
        assertEquals("com.example.Foo#bar", s.key());
        assertEquals(UsageFinder.SymbolKind.METHOD, s.kind);
    }

    @Test
    void symbolKeyForField() {
        UsageFinder.Symbol s = UsageFinder.Symbol.ofField("com.example.Foo", "x");
        assertEquals("com.example.Foo#x", s.key());
        assertEquals(UsageFinder.SymbolKind.FIELD, s.kind);
    }

    @Test
    void usageLocationIsSortable() {
        UsageFinder.UsageLocation a = new UsageFinder.UsageLocation(
            java.nio.file.Path.of("A.java"), 10, 5, UsageFinder.RefKind.CALL, "foo()", "A.foo");
        UsageFinder.UsageLocation b = new UsageFinder.UsageLocation(
            java.nio.file.Path.of("A.java"), 20, 1, UsageFinder.RefKind.CALL, "bar()", "A.bar");
        UsageFinder.UsageLocation c = new UsageFinder.UsageLocation(
            java.nio.file.Path.of("B.java"), 1, 1, UsageFinder.RefKind.CALL, "baz()", "B.baz");

        List<UsageFinder.UsageLocation> sorted = new java.util.ArrayList<>(List.of(c, a, b));
        java.util.Collections.sort(sorted);
        assertEquals(List.of(a, b, c), sorted);
    }

    @Test
    void toLineProducesGrepFriendlyFormat() {
        UsageFinder.UsageLocation loc = new UsageFinder.UsageLocation(
            java.nio.file.Path.of("src/main/java/com/example/Foo.java"),
            42, 5, UsageFinder.RefKind.CALL, "foo()", "Caller.invoke");
        String line = loc.toLine();
        assertTrue(line.contains("Foo.java:42:5"), "should contain file:line:col: " + line);
        assertTrue(line.contains("CALL"), "should contain kind: " + line);
        assertTrue(line.contains("foo()"), "should contain snippet: " + line);
    }

    @Test
    void allRefKindsAreDefined() {
        // Sanity check that the enum covers the kinds we documented in the plan
        assertNotNull(UsageFinder.RefKind.valueOf("CALL"));
        assertNotNull(UsageFinder.RefKind.valueOf("NEW"));
        assertNotNull(UsageFinder.RefKind.valueOf("FIELD_READ"));
        assertNotNull(UsageFinder.RefKind.valueOf("FIELD_WRITE"));
        assertNotNull(UsageFinder.RefKind.valueOf("EXTENDS"));
        assertNotNull(UsageFinder.RefKind.valueOf("IMPLEMENTS"));
        assertNotNull(UsageFinder.RefKind.valueOf("IMPORT"));
        assertNotNull(UsageFinder.RefKind.valueOf("CAST"));
        assertNotNull(UsageFinder.RefKind.valueOf("INSTANCEOF"));
        assertNotNull(UsageFinder.RefKind.valueOf("THROW"));
        assertNotNull(UsageFinder.RefKind.valueOf("CATCH"));
        assertNotNull(UsageFinder.RefKind.valueOf("TYPE_REF"));
    }
}
