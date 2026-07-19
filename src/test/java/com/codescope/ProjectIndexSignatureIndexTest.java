package com.codescope;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

import com.codescope.ProjectIndex.AmbiguousMethodException;

/**
 * Verifies the write-through (name, arity) signature index on
 * {@link ProjectIndex}. The index is populated by
 * {@link ProjectIndex#putDeclaration} and queried by
 * {@link ProjectIndex#methodsWithSignature} (all candidates with a
 * signature) and {@link ProjectIndex#methodInClassWithSignature}
 * (the candidate in a specific class). The per-class lookup lets
 * the post-build reverse-hierarchy repair pass find a single
 * declaration in O(1) instead of re-scanning the entire signature
 * bucket per subtype, which was the build-phase bottleneck for
 * large projects (issue #6).
 */
class ProjectIndexSignatureIndexTest {

    private static MethodKey mk(String cls, String name, int arity) {
        return new MethodKey(cls, name, arity, null);
    }

    @Test
    void methodsWithSignatureReturnsAllDeclarationsWithSameNameAndArity() {
        ProjectIndex idx = new ProjectIndex();
        MethodKey a = mk("com.example.A", "save", 1);
        MethodKey b = mk("com.example.B", "save", 1);
        MethodKey c = mk("com.example.C", "save", 1);
        idx.putDeclaration(a, new ProjectIndex.SourceLoc("A.java", 10));
        idx.putDeclaration(b, new ProjectIndex.SourceLoc("B.java", 20));
        idx.putDeclaration(c, new ProjectIndex.SourceLoc("C.java", 30));

        Set<MethodKey> matches = idx.methodsWithSignature("save", 1);
        assertEquals(3, matches.size());
        assertTrue(matches.contains(a));
        assertTrue(matches.contains(b));
        assertTrue(matches.contains(c));
    }

    @Test
    void methodsWithSignatureDistinguishesByArity() {
        ProjectIndex idx = new ProjectIndex();
        MethodKey zero = mk("com.example.A", "load", 0);
        MethodKey one = mk("com.example.A", "load", 1);
        idx.putDeclaration(zero, new ProjectIndex.SourceLoc("A.java", 10));
        idx.putDeclaration(one, new ProjectIndex.SourceLoc("A.java", 20));

        assertEquals(1, idx.methodsWithSignature("load", 0).size());
        assertEquals(1, idx.methodsWithSignature("load", 1).size());
    }

    @Test
    void methodsWithSignatureOnMissingNameReturnsEmpty() {
        ProjectIndex idx = new ProjectIndex();
        idx.putDeclaration(mk("com.example.A", "save", 1),
                new ProjectIndex.SourceLoc("A.java", 10));
        Set<MethodKey> matches = idx.methodsWithSignature("delete", 1);
        assertNotNull(matches, "must return empty set, not null");
        assertTrue(matches.isEmpty());
    }

    @Test
    void methodsWithSignatureOnMissingArityReturnsEmpty() {
        ProjectIndex idx = new ProjectIndex();
        idx.putDeclaration(mk("com.example.A", "save", 1),
                new ProjectIndex.SourceLoc("A.java", 10));
        assertTrue(idx.methodsWithSignature("save", 2).isEmpty());
    }

    @Test
    void methodsWithSignatureOnNullNameReturnsEmpty() {
        ProjectIndex idx = new ProjectIndex();
        assertNotNull(idx.methodsWithSignature(null, 1));
        assertTrue(idx.methodsWithSignature(null, 1).isEmpty());
    }

    @Test
    void indexIsVisibleImmediatelyAfterPutDeclaration() {
        // Write-through: no separate "build" step required.
        ProjectIndex idx = new ProjectIndex();
        assertTrue(idx.methodsWithSignature("save", 1).isEmpty());
        idx.putDeclaration(mk("com.example.A", "save", 1),
                new ProjectIndex.SourceLoc("A.java", 10));
        assertEquals(1, idx.methodsWithSignature("save", 1).size());
    }

    @Test
    void indexHandlesRedundantPutDeclaration() {
        // putDeclaration uses putIfAbsent; bySignature add is idempotent
        // (Set semantics). Calling putDeclaration twice with the same key
        // must not produce duplicates in the indexed set.
        ProjectIndex idx = new ProjectIndex();
        MethodKey m = mk("com.example.A", "save", 1);
        idx.putDeclaration(m, new ProjectIndex.SourceLoc("A.java", 10));
        idx.putDeclaration(m, new ProjectIndex.SourceLoc("A.java", 99));
        assertEquals(1, idx.methodsWithSignature("save", 1).size());
    }

    @Test
    void methodInClassWithSignatureReturnsTheDeclarationInThatClass() throws Exception {
        ProjectIndex idx = new ProjectIndex();
        MethodKey a = mk("com.example.A", "save", 1);
        MethodKey b = mk("com.example.B", "save", 1);
        idx.putDeclaration(a, new ProjectIndex.SourceLoc("A.java", 10));
        idx.putDeclaration(b, new ProjectIndex.SourceLoc("B.java", 20));

        assertSame(a, idx.methodInClassWithSignature("com.example.A", "save", 1));
        assertSame(b, idx.methodInClassWithSignature("com.example.B", "save", 1));
    }

    @Test
    void methodInClassWithSignatureReturnsNullWhenClassHasNoSuchDeclaration() throws Exception {
        ProjectIndex idx = new ProjectIndex();
        idx.putDeclaration(mk("com.example.A", "save", 1),
                new ProjectIndex.SourceLoc("A.java", 10));
        assertNull(idx.methodInClassWithSignature("com.example.B", "save", 1),
                "B has no declaration with this signature; must be null, not a cross-class match");
    }

    @Test
    void methodInClassWithSignatureReturnsNullWhenArityMismatch() throws Exception {
        ProjectIndex idx = new ProjectIndex();
        idx.putDeclaration(mk("com.example.A", "save", 1),
                new ProjectIndex.SourceLoc("A.java", 10));
        assertNull(idx.methodInClassWithSignature("com.example.A", "save", 2));
    }

    @Test
    void methodInClassWithSignatureReturnsNullOnNullArgs() throws Exception {
        ProjectIndex idx = new ProjectIndex();
        idx.putDeclaration(mk("com.example.A", "save", 1),
                new ProjectIndex.SourceLoc("A.java", 10));
        assertNull(idx.methodInClassWithSignature(null, "save", 1));
        assertNull(idx.methodInClassWithSignature("com.example.A", null, 1));
    }

    @Test
    void methodInClassWithSignatureThrowsOnOverloadAmbiguity() {
        // When the target class declares two overloads sharing (name, arity)
        // but differing parameter types, the single-key lookup has no
        // meaningful answer. Throwing AmbiguousMethodException forces the
        // caller to disambiguate via parameterTypes or to switch to
        // methodsInClassWithSignature for the multi-overload case.
        ProjectIndex idx = new ProjectIndex();
        MethodKey saveString = new MethodKey("com.example.A", "save", 1,
                List.of("java.lang.String"));
        MethodKey saveInt = new MethodKey("com.example.A", "save", 1,
                List.of("int"));
        idx.putDeclaration(saveString, new ProjectIndex.SourceLoc("A.java", 10));
        idx.putDeclaration(saveInt, new ProjectIndex.SourceLoc("A.java", 20));

        AmbiguousMethodException thrown = assertThrows(AmbiguousMethodException.class,
                () -> idx.methodInClassWithSignature("com.example.A", "save", 1));
        assertTrue(thrown.getMessage().contains("save"),
                "exception message should reference the ambiguous method name");
    }

    @Test
    void methodsWithSignatureDistinguishesOverloadsByParameterTypes() {
        // Two methods in the same declaring class with the same name and
        // arity but different parameter types (e.g. save(String) and
        // save(int)) are distinct Java overloads and must not collapse
        // to one signature-index entry. The post-build reverse-hierarchy
        // repair pass walks subtypes via the signature index and would
        // otherwise link an override on only one of the two overloads,
        // silently missing edges for the other.
        ProjectIndex idx = new ProjectIndex();
        MethodKey saveString = new MethodKey("com.example.A", "save", 1,
                List.of("java.lang.String"));
        MethodKey saveInt = new MethodKey("com.example.A", "save", 1,
                List.of("int"));
        idx.putDeclaration(saveString, new ProjectIndex.SourceLoc("A.java", 10));
        idx.putDeclaration(saveInt, new ProjectIndex.SourceLoc("A.java", 20));

        Set<MethodKey> matches = idx.methodsWithSignature("save", 1);
        assertEquals(2, matches.size(),
                "same-class overloads sharing (name, arity) must both be indexed; "
                        + "collapsing them loses override candidates for the repair pass");
        assertTrue(matches.contains(saveString));
        assertTrue(matches.contains(saveInt));
    }

    @Test
    void methodsInClassWithSignatureReturnsEveryOverloadInTheClass() {
        ProjectIndex idx = new ProjectIndex();
        MethodKey saveString = new MethodKey("com.example.A", "save", 1,
                List.of("java.lang.String"));
        MethodKey saveInt = new MethodKey("com.example.A", "save", 1,
                List.of("int"));
        idx.putDeclaration(saveString, new ProjectIndex.SourceLoc("A.java", 10));
        idx.putDeclaration(saveInt, new ProjectIndex.SourceLoc("A.java", 20));

        Set<MethodKey> matches = idx.methodsInClassWithSignature(
                "com.example.A", "save", 1);
        assertEquals(2, matches.size());
        assertTrue(matches.contains(saveString));
        assertTrue(matches.contains(saveInt));
    }

    @Test
    void methodsInClassWithSignatureReturnsSingletonSetWhenOnlyOneOverload() {
        ProjectIndex idx = new ProjectIndex();
        MethodKey only = mk("com.example.A", "save", 1);
        idx.putDeclaration(only, new ProjectIndex.SourceLoc("A.java", 10));

        Set<MethodKey> matches = idx.methodsInClassWithSignature(
                "com.example.A", "save", 1);
        assertEquals(1, matches.size());
        assertTrue(matches.contains(only));
    }

    @Test
    void methodsInClassWithSignatureReturnsEmptySetWhenNoMatch() {
        ProjectIndex idx = new ProjectIndex();
        idx.putDeclaration(mk("com.example.A", "save", 1),
                new ProjectIndex.SourceLoc("A.java", 10));
        assertNotNull(idx.methodsInClassWithSignature("com.example.B", "save", 1));
        assertTrue(idx.methodsInClassWithSignature("com.example.B", "save", 1).isEmpty());
    }

    @Test
    void methodsInClassWithSignatureReturnsEmptySetOnArityMismatch() {
        ProjectIndex idx = new ProjectIndex();
        idx.putDeclaration(mk("com.example.A", "save", 1),
                new ProjectIndex.SourceLoc("A.java", 10));
        assertTrue(idx.methodsInClassWithSignature("com.example.A", "save", 2).isEmpty());
    }

    @Test
    void methodsInClassWithSignatureReturnsEmptySetOnNullArgs() {
        ProjectIndex idx = new ProjectIndex();
        assertNotNull(idx.methodsInClassWithSignature(null, "save", 1));
        assertNotNull(idx.methodsInClassWithSignature("com.example.A", null, 1));
        assertTrue(idx.methodsInClassWithSignature(null, "save", 1).isEmpty());
        assertTrue(idx.methodsInClassWithSignature("com.example.A", null, 1).isEmpty());
    }

    @Test
    void methodsInClassWithSignatureReturnsDefensiveSnapshot() {
        // A concurrent putDeclaration after the lookup must not change
        // what the caller already saw — repair-pass iteration is safe
        // under ongoing indexer writes.
        ProjectIndex idx = new ProjectIndex();
        MethodKey first = new MethodKey("com.example.A", "save", 1,
                List.of("java.lang.String"));
        idx.putDeclaration(first, new ProjectIndex.SourceLoc("A.java", 10));

        Set<MethodKey> snapshot = idx.methodsInClassWithSignature(
                "com.example.A", "save", 1);
        assertEquals(1, snapshot.size());

        MethodKey second = new MethodKey("com.example.A", "save", 1,
                List.of("int"));
        idx.putDeclaration(second, new ProjectIndex.SourceLoc("A.java", 20));

        assertEquals(1, snapshot.size(),
                "snapshot must not be affected by subsequent putDeclaration");
        assertTrue(idx.methodsInClassWithSignature(
                "com.example.A", "save", 1).size() == 2,
                "live index must reflect both declarations");
    }
}
