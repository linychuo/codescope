package com.codescope;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies the write-through (name, arity) signature index on
 * {@link ProjectIndex}. The index is populated by
 * {@link ProjectIndex#putDeclaration} and queried by
 * {@link ProjectIndex#methodsWithSignature}, so the post-build
 * reverse-hierarchy repair pass can find same-named, same-arity
 * candidates in O(1) per signature lookup instead of an O(N)
 * full-scan of {@link ProjectIndex#knownMethods()} per subtype
 * (issue #6).
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
}
