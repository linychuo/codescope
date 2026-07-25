package com.codescope;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies the ancestor-walk fallback in
 * {@link ProjectIndex#findInvokedKeys} runs the FQN-suffix lenient
 * pass per-ancestor, not gated on a global "found anything yet"
 * check. The strict match on one ancestor must not short-circuit
 * the suffix match on a different ancestor.
 *
 * <p>Uses a linear type hierarchy (C ↔ A1 ↔ A2) so the walk order
 * is deterministic — A1 is always processed before A2, which is
 * the order in which the bug manifests.
 */
class ProjectIndexFindInvokedKeysTest {

    @Test
    void ancestorSuffixMatchIsNotSkippedWhenAnotherAncestorHadStrictMatch() {
        ProjectIndex idx = new ProjectIndex();
        // A1#m matches user's [java.lang.String] exactly (strict).
        MethodKey a1m = new MethodKey("com.example.A1", "m", 1, List.of("java.lang.String"));
        // A2#m has a different param type whose simple-name tail is "String"
        // so the user's [java.lang.String] would match by suffix if run.
        MethodKey a2m = new MethodKey("com.example.A2", "m", 1,
                List.of("com.example.String"));
        idx.recordInvocation(new MethodKey("com.example.Caller1", "go", 0, List.of()), a1m);
        idx.recordInvocation(new MethodKey("com.example.Caller2", "go", 0, List.of()), a2m);
        // Linear hierarchy: C extends A1, A1 extends A2. Walk from C
        // visits A1 first, then A2 (deterministic because C's parent
        // set has only A1).
        idx.recordTypeHierarchy("com.example.C", "com.example.A1");
        idx.recordTypeHierarchy("com.example.A1", "com.example.A2");

        List<MethodKey> out = idx.findInvokedKeys("com.example.C", "m", 1,
                List.of("java.lang.String"));
        assertTrue(out.contains(a1m),
                "strict hit on A1 must be present, got: " + out);
        assertTrue(out.contains(a2m),
                "suffix hit on A2 must also be present — the per-ancestor "
                        + "suffix pass must not be skipped because A1's strict already added an entry. "
                        + "Got: " + out);
    }
}
