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
        MethodKey target = index.resolveTarget("com.example.Target", "leaf");
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
        MethodKey zero = index.resolveTarget("com.example.Target", "leaf");
        MethodKey one = index.resolveTarget("com.example.Target", "leafWithArg");
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
    void stopsAtUncalledMethod() {
        MethodKey uncalled = index.resolveTarget("com.example.Mid", "unrelated");
        assertNotNull(uncalled);

        CallChainAnalyzer.Result r = new CallChainAnalyzer().traceCallers(index, uncalled);
        assertTrue(r.found());
        // nothing calls unrelated()
        assertNull(r.root().toJson().get("callers"));
    }

    @Test
    void returnsNotFoundForUnknownMethod() {
        MethodKey ghost = new MethodKey("com.example.NoSuch", "missing", 0);
        CallChainAnalyzer.Result r = new CallChainAnalyzer().traceCallers(index, ghost);
        assertFalse(r.found());
    }

    @Test
    void detectsCycle() {
        MethodKey a = index.resolveTarget("com.example.Cycle", "a");
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
}
