package com.codescope;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Direction semantics of the type-hierarchy index.
 *
 * <p>{@link ProjectIndex#recordTypeHierarchy} records a directed edge
 * (child &rarr; parent), but the index used to store it bidirectionally
 * in a single map. That made {@code subtypesOf} return supertypes too,
 * and made the ancestor walks in {@code resolveTarget} /
 * {@code findInvokedKeys} traverse downward into subclasses — so a
 * query for a method a class does not declare could resolve to a
 * subclass's declaration.
 */
class ProjectIndexTypeHierarchyDirectionTest {

    private static MethodKey k(String cls, String name, int arity, String... params) {
        return new MethodKey(cls, name, arity, List.of(params));
    }

    @Test
    void subtypesOfReturnsOnlyDirectSubtypes() {
        ProjectIndex idx = new ProjectIndex();
        idx.recordTypeHierarchy("com.example.Child", "com.example.Parent");
        assertTrue(idx.subtypesOf("com.example.Parent").contains("com.example.Child"),
                "a direct subtype must be returned");
        assertFalse(idx.subtypesOf("com.example.Child").contains("com.example.Parent"),
                "a supertype is not a subtype");
        assertTrue(idx.subtypesOf("com.example.Child").isEmpty(),
                "a leaf type has no subtypes");
    }

    @Test
    void supertypesOfReturnsOnlyDirectSupertypes() {
        ProjectIndex idx = new ProjectIndex();
        idx.recordTypeHierarchy("com.example.Child", "com.example.Parent");
        assertTrue(idx.supertypesOf("com.example.Child").contains("com.example.Parent"),
                "a direct supertype must be returned");
        assertFalse(idx.supertypesOf("com.example.Parent").contains("com.example.Child"),
                "a subtype is not a supertype");
    }

    @Test
    void resolveTargetDoesNotResolveToSubclassDeclaration() throws Exception {
        ProjectIndex idx = new ProjectIndex();
        // A does not declare m; B extends A and declares m.
        idx.putDeclaration(k("com.example.B", "m", 0), new ProjectIndex.SourceLoc("B.java", 1));
        idx.recordTypeHierarchy("com.example.B", "com.example.A");
        assertNull(idx.resolveTarget("com.example.A", "m", null, null),
                "A does not declare m; resolving upward must not fall through to B#m");
    }

    @Test
    void resolveTargetStillWalksUpToAncestorDeclaration() throws Exception {
        ProjectIndex idx = new ProjectIndex();
        // A declares m; B extends A and does not.
        idx.putDeclaration(k("com.example.A", "m", 0), new ProjectIndex.SourceLoc("A.java", 1));
        idx.recordTypeHierarchy("com.example.B", "com.example.A");
        MethodKey resolved = idx.resolveTarget("com.example.B", "m", null, null);
        assertNotNull(resolved, "B inherits m from A");
        assertEquals("com.example.A", resolved.declaringClass);
    }

    @Test
    void findInvokedKeysDoesNotResolveToSubclassCallEdges() {
        ProjectIndex idx = new ProjectIndex();
        MethodKey bM = k("com.example.B", "m", 0);
        idx.putDeclaration(bM, new ProjectIndex.SourceLoc("B.java", 1));
        idx.recordInvocation(k("com.example.Caller", "go", 0), bM);
        idx.recordTypeHierarchy("com.example.B", "com.example.A");
        assertTrue(idx.findInvokedKeys("com.example.A", "m", null, null).isEmpty(),
                "A has no call edges under it; B's edges must not leak upward");
    }
}
