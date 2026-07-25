package com.codescope;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Targets the package-private {@code matchStrictThenSuffix} helper in
 * {@link ProjectIndex}. The public API (resolveTarget / findInvokedKeys)
 * is already covered by the service tests; this is the focused regression
 * net for the strict+suffix deduplication.
 */
class ProjectIndexStrictSuffixTest {

    private static MethodKey k(String cls, String name, int arity, String... params) {
        return new MethodKey(cls, name, arity, List.of(params));
    }

    @Test
    void strictPassReturnsMatches() {
        MethodKey a = k("com.x.A", "m", 1, "java.lang.String");
        MethodKey b = k("com.x.A", "m", 1, "int");
        List<MethodKey> out = ProjectIndex.matchStrictThenSuffix(
                List.of(a, b), "com.x.A", "m", 1, List.of("java.lang.String"));
        assertEquals(List.of(a), out);
    }

    @Test
    void suffixFallbackOnlyRunsWhenStrictPassIsEmpty() {
        // User asks for the exact FQN paramType list ["java.lang.String"].
        // The `strict` candidate matches strictly; the `suffix` candidate is
        // a separate FQN that the helper must NOT contribute when strict is
        // non-empty.
        MethodKey strict = k("com.x.A", "m", 1, "java.lang.String");
        MethodKey suffix = k("com.x.A", "m", 1, "com.example.String");
        List<MethodKey> out = ProjectIndex.matchStrictThenSuffix(
                List.of(strict, suffix), "com.x.A", "m", 1, List.of("java.lang.String"));
        assertEquals(List.of(strict), out, "strict match wins; suffix candidate must NOT contribute");
    }

    @Test
    void suffixFallbackRunsWhenStrictPassIsEmpty() {
        MethodKey suffix = k("com.x.A", "m", 1, "com.example.String");
        List<MethodKey> out = ProjectIndex.matchStrictThenSuffix(
                List.of(suffix), "com.x.A", "m", null,
                List.of("String"));
        assertEquals(List.of(suffix), out);
    }

    @Test
    void noSuffixWhenParamTypesIsNull() {
        // Candidate has a non-matching methodName — strict pass rejects it by
        // methodName; with paramTypes=null the helper must NOT run the suffix
        // fallback, so the result is empty.
        MethodKey other = k("com.x.A", "other", 1, "com.example.String");
        assertTrue(ProjectIndex.matchStrictThenSuffix(
                List.of(other), "com.x.A", "m", null, null).isEmpty(),
                "paramTypes=null suppresses suffix fallback even when strict pass is empty");
    }

    @Test
    void suffixFallbackPerIteration() {
        // The bug fixed 2026-07-25: a strict hit on iteration 1 must not
        // suppress the suffix pass on iteration 2. Two separate calls
        // represent two iterations; the helper's per-iteration semantics
        // guarantee the second call's suffix pass still runs.
        MethodKey strictForA1 = k("com.x.A1", "m", 1, "java.lang.String");
        MethodKey suffixForA2 = k("com.x.A2", "m", 1, "com.example.String");
        List<MethodKey> iter1 = ProjectIndex.matchStrictThenSuffix(
                List.of(strictForA1), "com.x.A1", "m", null, List.of("String"));
        List<MethodKey> iter2 = ProjectIndex.matchStrictThenSuffix(
                List.of(suffixForA2), "com.x.A2", "m", null, List.of("String"));
        assertEquals(List.of(strictForA1), iter1);
        assertEquals(List.of(suffixForA2), iter2,
                "iter2 must run its own suffix pass; the helper has no global state");
    }

    @Test
    void returnsEmptyForEmptyCandidates() {
        assertTrue(ProjectIndex.matchStrictThenSuffix(
                List.of(), "com.x.A", "m", null, null).isEmpty());
    }
}
