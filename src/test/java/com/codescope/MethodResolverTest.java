package com.codescope;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Behavior of {@link MethodResolver}: target resolution path,
 * overload-hint message, overload-union suffix.
 */
class MethodResolverTest {

    private static ProjectIndex newIndex() {
        return new ProjectIndex();
    }

    @Test
    void resolveReturnsProjectTargetWhenDeclared() throws Exception {
        ProjectIndex idx = newIndex();
        idx.putDeclaration(new MethodKey("com.x.A", "m", 1, List.of("java.lang.String")),
                new ProjectIndex.SourceLoc("A.java", 1));
        MethodResolver.Result r = MethodResolver.resolve(idx, "com.x.A", "m", null, null);
        assertInstanceOf(MethodResolver.Result.ProjectTarget.class, r);
        MethodKey target = ((MethodResolver.Result.ProjectTarget) r).target();
        assertEquals("com.x.A", target.declaringClass);
        assertEquals("m", target.methodName);
    }

    @Test
    void resolveReturnsLibrarySeedsWhenCallEdgesExist() throws Exception {
        // No declaration; record a call edge so findInvokedKeys has something.
        ProjectIndex idx = newIndex();
        MethodKey recorded = new MethodKey("java.io.PrintStream", "println", 1, List.of("java.lang.String"));
        idx.recordInvocation(new MethodKey("com.x.Caller", "go", 0, List.of()), recorded);
        MethodResolver.Result r = MethodResolver.resolve(idx, "java.io.PrintStream", "println",
                1, List.of("java.lang.String"));
        assertInstanceOf(MethodResolver.Result.LibrarySeeds.class, r);
        MethodResolver.Result.LibrarySeeds ls = (MethodResolver.Result.LibrarySeeds) r;
        assertEquals(List.of(recorded), ls.seeds());
        assertEquals(recorded, ls.display());
    }

    @Test
    void resolveSynthesizesDisplayWhenNoEdges() throws Exception {
        ProjectIndex idx = newIndex();
        MethodResolver.Result r = MethodResolver.resolve(idx, "java.io.PrintStream", "println",
                1, List.of("java.lang.String"));
        assertInstanceOf(MethodResolver.Result.LibrarySeeds.class, r);
        MethodResolver.Result.LibrarySeeds ls = (MethodResolver.Result.LibrarySeeds) r;
        assertEquals(1, ls.seeds().size());
        assertEquals("java.io.PrintStream", ls.seeds().get(0).declaringClass);
        assertEquals(ls.seeds().get(0), ls.display(),
                "synthesized display must equal the single synthesized seed");
    }

    @Test
    void overloadsHintListsProjectOverloadsWhenVisible() {
        ProjectIndex idx = newIndex();
        idx.putDeclaration(new MethodKey("com.x.A", "m", 1, List.of("int")), new ProjectIndex.SourceLoc("A.java", 1));
        idx.putDeclaration(new MethodKey("com.x.A", "m", 1, List.of("java.lang.String")), new ProjectIndex.SourceLoc("A.java", 2));
        ProjectIndex.AmbiguousMethodException fakeCause =
                new ProjectIndex.AmbiguousMethodException("com.x.A", "m", 1, List.of("int"));
        String hint = MethodResolver.overloadsHint(idx, "com.x.A", "m", fakeCause);
        assertTrue(hint.contains("available overloads:"),
                "hint must enumerate project overloads, got: " + hint);
        assertTrue(hint.contains("com.x.A#m/1(int)") || hint.contains("m/1(int)"),
                "hint must list at least one overload signature, got: " + hint);
    }

    @Test
    void overloadsHintAdvisesParamTypesWhenLibraryOnly() {
        ProjectIndex idx = newIndex();   // no declarations for com.x.A
        ProjectIndex.AmbiguousMethodException fakeCause =
                new ProjectIndex.AmbiguousMethodException("com.x.A", "m", 1, List.of("int"));
        String hint = MethodResolver.overloadsHint(idx, "com.x.A", "m", fakeCause);
        assertTrue(hint.contains("no overloads are visible"),
                "hint must explain library-only case, got: " + hint);
        assertTrue(hint.contains("paramTypes"),
                "hint must suggest paramTypes, got: " + hint);
    }

    @Test
    void overloadUnionSuffixEmptyForSingleSeed() {
        assertEquals("", MethodResolver.overloadUnionSuffix(List.of(
                new MethodKey("a.b.C", "m", 1, List.of("int")))));
        assertEquals("", MethodResolver.overloadUnionSuffix(List.of()));
    }

    @Test
    void overloadUnionSuffixListsSortedSignatures() {
        List<MethodKey> seeds = List.of(
                new MethodKey("a.b.C", "m", 1, List.of("int")),
                new MethodKey("a.b.C", "m", 1, List.of("java.lang.String")));
        String suffix = MethodResolver.overloadUnionSuffix(seeds);
        assertTrue(suffix.contains("2 library overloads"),
                "suffix must report overload count, got: " + suffix);
        assertTrue(suffix.contains("a.b.C#m/1(java.lang.String)"),
                "suffix must list String variant, got: " + suffix);
        assertTrue(suffix.contains("a.b.C#m/1(int)"),
                "suffix must list int variant, got: " + suffix);
    }
}
