package com.codescope;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** End-to-end test using the fixture Maven project under src/test/resources. */
class CallChainAnalyzerTest {

    private ProjectIndex index;

    /** Convenience: find a target method or fail the test (hides the checked exception). */
    private static MethodKey mustResolve(ProjectIndex idx, String cls, String name) {
        return mustResolve(idx, cls, name, -1);
    }

    private static MethodKey mustResolve(ProjectIndex idx, String cls, String name, int arity) {
        try {
            return arity < 0
                    ? idx.resolveTarget(cls, name)
                    : idx.resolveTarget(cls, name, arity);
        } catch (ProjectIndex.AmbiguousMethodException e) {
            throw new AssertionError("unexpected ambiguity for " + cls + "#" + name + ": " + e.getMessage(), e);
        }
    }

    @BeforeEach
    void setUp() throws IOException {
        Path fixture = Path.of("src/test/resources/fixture-project");
        ProjectLoader.LoadResult load = new ProjectLoader().load(fixture);
        // sanity: there must be sources
        assertFalse(load.sources().isEmpty(), "fixture sources not found");
        index = new JdtIndexer().build(load.sources(), load.classpath(), load.sourcepath(), fixture);
    }

    @Test
    void findsTransitiveCallersOfLeaf() {
        MethodKey target = mustResolve(index, "com.example.Target", "leaf");
        assertNotNull(target, "Target.leaf should resolve");

        CallChainAnalyzer.Result r = new CallChainAnalyzer().traceCallers(index, target);
        assertTrue(r.found());

        Map<String, Object> json = r.root().toJson();
        // Target.leaf is called by Mid.callsLeaf
        List<Map<String, Object>> callers = (List<Map<String, Object>>) json.get("callers");
        assertNotNull(callers);
        assertEquals(1, callers.size(), "expected one direct caller of Target.leaf");

        Map<String, Object> mid = callers.get(0);
        assertEquals("com.example.Mid", mid.get("class"));
        assertEquals("callsLeaf", mid.get("method"));

        // Mid.callsLeaf is called by Top.entryPoint
        List<Map<String, Object>> midCallers = (List<Map<String, Object>>) mid.get("callers");
        assertNotNull(midCallers);
        assertEquals(1, midCallers.size());
        assertEquals("com.example.Top", midCallers.get(0).get("class"));
        assertEquals("entryPoint", midCallers.get(0).get("method"));

        // Top.entryPoint is called by SideBranch.branch
        List<Map<String, Object>> topCallers = (List<Map<String, Object>>) midCallers.get(0).get("callers");
        assertNotNull(topCallers);
        assertEquals(1, topCallers.size());
        assertEquals("com.example.SideBranch", topCallers.get(0).get("class"));
        assertEquals("branch", topCallers.get(0).get("method"));
    }

    @Test
    void distinguishesOverloadsByArity() {
        MethodKey zero = mustResolve(index, "com.example.Target", "leaf");
        MethodKey one = mustResolve(index, "com.example.Target", "leafWithArg");
        assertNotNull(zero);
        assertNotNull(one);
        assertEquals(0, zero.arity);
        assertEquals(1, one.arity);

        CallChainAnalyzer.Result r0 = new CallChainAnalyzer().traceCallers(index, zero);
        CallChainAnalyzer.Result r1 = new CallChainAnalyzer().traceCallers(index, one);

        // Each overload should have exactly one direct caller, and they should be different methods on Mid.
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> c0 = (List<Map<String, Object>>) r0.root().toJson().get("callers");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> c1 = (List<Map<String, Object>>) r1.root().toJson().get("callers");
        assertNotNull(c0);
        assertNotNull(c1);
        assertEquals(1, c0.size());
        assertEquals(1, c1.size());
        assertEquals("callsLeaf", c0.get(0).get("method"));
        assertEquals("callsLeafWithArg", c1.get(0).get("method"));
    }

    @Test
    void reportsAmbiguousOverloadsWhenNoArityGiven() {
        // Target has two `process` methods with arity 1 but different param types.
        // Without arity, we can't pick one — must throw AmbiguousMethodException.
        ProjectIndex.AmbiguousMethodException ex = assertThrows(
                ProjectIndex.AmbiguousMethodException.class,
                () -> index.resolveTarget("com.example.Target", "process"));
        assertTrue(ex.getMessage().contains("process"),
                "error should name the method: " + ex.getMessage());

        // findOverloads lists both, so callers can disambiguate
        assertEquals(2, index.findOverloads("com.example.Target", "process").size());

        // Arity alone is still ambiguous here (both are arity 1); need paramTypes to pick one.
        assertThrows(ProjectIndex.AmbiguousMethodException.class,
                () -> index.resolveTarget("com.example.Target", "process", 1));

        // With paramTypes, we can pick exactly one.
        MethodKey mInt;
        MethodKey mStr;
        try {
            mInt = index.resolveTarget("com.example.Target", "process", 1, List.of("int"));
            mStr = index.resolveTarget("com.example.Target", "process", 1, List.of("java.lang.String"));
        } catch (ProjectIndex.AmbiguousMethodException e) {
            throw new AssertionError(e);
        }
        assertNotNull(mInt);
        assertNotNull(mStr);
        assertNotEquals(mInt, mStr, "the two overloads should be different MethodKeys");
        assertEquals(List.of("int"), mInt.parameterTypes);
        assertEquals(List.of("java.lang.String"), mStr.parameterTypes);
    }

    @Test
    void stopsAtUncalledMethod() {
        MethodKey uncalled = mustResolve(index, "com.example.Mid", "unrelated");
        assertNotNull(uncalled);

        CallChainAnalyzer.Result r = new CallChainAnalyzer().traceCallers(index, uncalled);
        assertTrue(r.found());
        // nothing calls unrelated()
        assertNull(r.root().toJson().get("callers"));
    }

    @Test
    void unknownMethodReturnsEmptyChain() {
        // A target with no callers — whether because the class doesn't exist
        // or because no project code calls it — is a valid answer with an
        // empty chain, not an error. The diagnostic message names the
        // possible reasons.
        MethodKey ghost = new MethodKey("com.example.NoSuch", "missing", 0);
        CallChainAnalyzer.Result r = new CallChainAnalyzer().traceCallers(index, ghost);
        assertTrue(r.found(), "no-callers is a valid result, not an error");
        assertTrue(r.message().contains("No callers"), r.message());
    }

    @Test
    void detectsCycle() {
        MethodKey a = mustResolve(index, "com.example.Cycle", "a");
        assertNotNull(a);
        CallChainAnalyzer.Result r = new CallChainAnalyzer().traceCallers(index, a);
        assertTrue(r.found());

        // a -> b -> a (cycle)
        Map<String, Object> json = r.root().toJson();
        List<Map<String, Object>> callers = (List<Map<String, Object>>) json.get("callers");
        assertNotNull(callers);
        assertEquals(1, callers.size());
        assertEquals("b", callers.get(0).get("method"));

        List<Map<String, Object>> bCallers = (List<Map<String, Object>>) callers.get(0).get("callers");
        assertNotNull(bCallers);
        assertEquals(1, bCallers.size());
        // a is now a cycle marker (already visited)
        assertEquals(Boolean.TRUE, bCallers.get(0).get("cycle"));
    }

    @Test
    void excludesTestSources() {
        // TargetTest (under src/test/java) calls Target.leaf, but we should NOT see it
        // as a caller because test sources are excluded from the index.
        MethodKey leaf = mustResolve(index, "com.example.Target", "leaf");
        assertNotNull(leaf);
        CallChainAnalyzer.Result r = new CallChainAnalyzer().traceCallers(index, leaf);
        assertTrue(r.found());

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> callers = (List<Map<String, Object>>) r.root().toJson().get("callers");
        assertNotNull(callers);
        for (Map<String, Object> c : callers) {
            assertFalse(c.get("signature").toString().contains("TargetTest"),
                    "unexpected test-source caller: " + c.get("signature"));
        }

        // Sanity: the test class itself is not in the declarations index.
        for (MethodKey k : index.knownMethods()) {
            assertFalse(k.declaringClass.contains("TargetTest"),
                    "test class leaked into declarations: " + k);
        }
    }

    @Test
    void indexesMethodsInEnum() {
        MethodKey label = mustResolve(index, "com.example.Kind", "label");
        assertNotNull(label, "Kind.label should be discoverable (enum methods are declared inside enums)");
        assertEquals(0, label.arity);
    }

    @Test
    void indexesRecordFileWithoutCrashing() {
        // JDT 3.45 with bindings enabled wraps top-level records in an
        // ImplicitTypeDeclaration whose body only carries the canonical
        // constructor — explicit record methods are not reachable through the
        // AST. We don't expect distanceFromOrigin to be indexed, but the file
        // must not abort the build. Trigger an indexer pass on a record file.
        try {
            index.resolveTarget("com.example.Point", "distanceFromOrigin");
        } catch (ProjectIndex.AmbiguousMethodException e) {
            // not expected, but not a crash
        }
        // The fixture sources must still all be present in the index's known methods
        // (i.e. the other types like Target, Mid, etc. survived the parse pass).
        assertFalse(index.knownMethods().isEmpty());
        // And the non-record enum fixture is still tracked (regression guard for #8).
        assertDoesNotThrow(() -> mustResolve(index, "com.example.Kind", "label"));
    }

    @Test
    void walksIntoAnnotationDeclaration() {
        // No methods to index in @interface (annotation members are not MethodDeclarations),
        // but the indexer must not crash and the annotation's FQN must be reachable.
        // Use findOverloads to confirm the type is in the index.
        // (Marker has no regular methods, so we just confirm the type can be referenced.)
        MethodKey marker = mustResolve(index, "com.example.Marker", "value");
        // `value` is an annotation member, not a method declaration; it won't be in the index.
        // We're really asserting that the indexer survived the AnnotationTypeDeclaration visit.
        assertNull(marker, "annotation members are not MethodDeclarations; expected no entry");
        // And the indexer must not have crashed. Spot-check the index is still consistent:
        assertFalse(index.knownMethods().isEmpty());
    }

    @Test
    void tracesCallersIntoEnumMethod() {
        MethodKey label = mustResolve(index, "com.example.Kind", "label");
        CallChainAnalyzer.Result r = new CallChainAnalyzer().traceCallers(index, label);
        assertTrue(r.found());

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> callers = (List<Map<String, Object>>) r.root().toJson().get("callers");
        assertNotNull(callers, "expected SpecialCaller.useEnumMethod as a caller");
        assertEquals(1, callers.size());
        assertEquals("com.example.SpecialCaller", callers.get(0).get("class"));
        assertEquals("useEnumMethod", callers.get(0).get("method"));
    }

    @Test
    void crossesInterfaceBoundaryToFindDomainCaller() {
        // Mirrors the typical Spring + MyBatis + JPA layering:
        //   IfaceDao#findById   ←   IfaceRepositoryImpl#findById   (impl calls dao)
        //                                       ↑ implements
        //                              IfaceRepository#findById   ←   IfaceDomainImpl#findById
        //                                                                              ↑ implements
        //                                                                       IfaceDomain#findById
        //
        // IfaceDomainImpl's static type for `repository` is the IfaceRepository
        // interface, so JDT binding resolves its call to IfaceRepository#findById,
        // NOT IfaceRepositoryImpl#findById. With the current BFS that follows
        // MethodKey literally, the chain stops at IfaceRepositoryImpl.
        //
        // The fix: when BFS expands IfaceRepositoryImpl#findById, it should also
        // look at the callers of IfaceRepository#findById (interface-typed callsite).
        // That brings IfaceDomainImpl into the chain.

        MethodKey dao = mustResolve(index, "com.example.IfaceDao", "findById");
        assertNotNull(dao);

        CallChainAnalyzer.Result r = new CallChainAnalyzer().traceCallers(index, dao);
        assertTrue(r.found());

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> callers = (List<Map<String, Object>>) r.root().toJson().get("callers");
        assertNotNull(callers);
        assertEquals(1, callers.size(), "IfaceRepositoryImpl is the only direct caller of IfaceDao");
        assertEquals("com.example.IfaceRepositoryImpl", callers.get(0).get("class"));
        assertEquals("findById", callers.get(0).get("method"));

        // The chain must cross the interface boundary here: IfaceRepositoryImpl
        // implements IfaceRepository, and IfaceDomainImpl calls through the
        // interface. The fix should make IfaceDomainImpl appear as a caller of
        // IfaceRepositoryImpl#findById (via the interface).
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> implCallers =
                (List<Map<String, Object>>) callers.get(0).get("callers");
        assertNotNull(implCallers,
                "expected BFS to cross the interface boundary and find IfaceDomainImpl");
        assertEquals(1, implCallers.size());
        assertEquals("com.example.IfaceDomainImpl", implCallers.get(0).get("class"));
        assertEquals("findById", implCallers.get(0).get("method"));
    }

    @Test
    void constructorsChainThroughSuper() {
        // `super()` in CtorChild#CtorChild is recorded as a real call edge
        // to CtorParent#CtorParent (via SuperConstructorInvocation), not a
        // phantom from the hierarchy walk. Tracing CtorParent#CtorParent
        // must surface CtorChild#CtorChild as a direct caller — and beyond
        // that, CtorUser#makeDefault (which constructs CtorChild) shows up
        // at depth 2. This is the CORRECT chain: CtorUser creates
        // CtorChild, whose constructor calls super() which runs
        // CtorParent's body.

        MethodKey parentCtor = mustResolve(index, "com.example.CtorParent", "CtorParent", 0);
        assertNotNull(parentCtor);
        assertEquals(0, parentCtor.arity);

        CallChainAnalyzer.Result r = new CallChainAnalyzer().traceCallers(index, parentCtor);
        assertTrue(r.found());

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> callers =
                (List<Map<String, Object>>) r.root().toJson().get("callers");
        assertNotNull(callers, "CtorChild#CtorChild must call CtorParent#CtorParent via super()");
        assertEquals(1, callers.size());
        assertEquals("com.example.CtorChild", callers.get(0).get("class"));
        assertEquals("CtorChild", callers.get(0).get("method"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> grandCallers =
                (List<Map<String, Object>>) callers.get(0).get("callers");
        assertNotNull(grandCallers,
                "CtorUser#makeDefault creates CtorChild, which super()s to CtorParent");
        assertEquals(1, grandCallers.size());
        assertEquals("com.example.CtorUser", grandCallers.get(0).get("class"));
        assertEquals("makeDefault", grandCallers.get(0).get("method"));
    }

    @Test
    void varargsCallSitesAreIndexed() {
        // VarArgs#fmt has a `String, Object...` signature. VarArgs#use calls
        // it with `fmt("hi %s", "world")` and `fmt("hi %s %s", "a", "b")` —
        // both bind to the declared 2-arg overload (Object[] absorbed into
        // a single varargs parameter).
        //
        // Regression: previously the indexer stored declarations with
        // params=[String, Object] (varargs marker dropped at the AST
        // level) but call-site bindings with params=[String, Object[]]
        // (JDT's binding form). The keys never matched, so use() didn't
        // appear as a caller of fmt/2.
        MethodKey fmt = mustResolve(index, "com.example.VarArgs", "fmt", 2);
        assertNotNull(fmt, "VarArgs#fmt/2 should resolve (varargs declared arity is 2)");
        // The recorded param types should include the varargs `[]` suffix
        // so that callers (whose binding includes the `[]`) match the key.
        assertTrue(fmt.parameterTypes.contains("java.lang.Object[]"),
                "expected Object[] (with varargs brackets) in params, got: " + fmt.parameterTypes);

        CallChainAnalyzer.Result r = new CallChainAnalyzer().traceCallers(index, fmt);
        assertTrue(r.found());

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> callers =
                (List<Map<String, Object>>) r.root().toJson().get("callers");
        assertNotNull(callers, "VarArgs#use must be a caller of VarArgs#fmt/2");
        assertEquals(1, callers.size());
        assertEquals("com.example.VarArgs", callers.get(0).get("class"));
        assertEquals("use", callers.get(0).get("method"));
    }

    @Test
    void privateMethodsAreNotCrossClassHierarchy() {
        // The companion test to crossesInterfaceBoundaryToFindDomainCaller:
        // when M is *private*, two methods named M in related classes
        // are NOT virtual overrides — JDT doesn't dispatch through
        // them, and the bytecode doesn't either. Parent#inheritDoPrivate
        // and Child#inheritDoPrivate are independent methods; tracing
        // Child#inheritDoPrivate must NOT see Parent#inheritDoPublic as
        // a caller (its body's doPrivate() call resolves to
        // Parent#inheritDoPrivate, not Child's).
        //
        // Concrete layout:
        //   InheritParent.inheritDoPublic() calls Parent#inheritDoPrivate
        //   InheritChild.inheritDoPublic()  calls Child#inheritDoPrivate
        //                                (overrides InheritParent's)
        //   InheritTop.run()               calls Child#inheritDoPublic
        //
        // Expected: tracing Child#inheritDoPrivate, the only direct
        // caller is Child#inheritDoPublic (then InheritTop#run).
        // Parent#inheritDoPublic must NOT appear in the chain — its body
        // calls Parent#inheritDoPrivate, not Child's.

        MethodKey childPrivate = mustResolve(index, "com.example.InheritChild", "inheritDoPrivate");
        assertNotNull(childPrivate);

        CallChainAnalyzer.Result r = new CallChainAnalyzer().traceCallers(index, childPrivate);
        assertTrue(r.found());

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> directCallers =
                (List<Map<String, Object>>) r.root().toJson().get("callers");
        assertNotNull(directCallers);
        assertEquals(1, directCallers.size(),
                "only Child#inheritDoPublic calls Child#inheritDoPrivate; "
                        + "Parent#inheritDoPublic must not appear. Got: "
                        + directCallers.stream().map(m -> m.get("class") + "#" + m.get("method"))
                                .toList());
        assertEquals("com.example.InheritChild", directCallers.get(0).get("class"));
        assertEquals("inheritDoPublic", directCallers.get(0).get("method"));
    }
}
