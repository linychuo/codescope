package com.codescope;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Interaction between the per-frame fan-out cap and cycle markers.
 *
 * <p>A true back-edge is rendered as a cycle marker — cheap, no subtree
 * to expand — so it must still be recorded when the fan-out cap fires,
 * and it must not be counted as a hidden (omitted) caller. The cap
 * bounds the callers the BFS <em>expands</em>, not the markers it
 * emits.
 */
class CallChainFanoutCycleTest {

    @Test
    @SuppressWarnings("unchecked")
    void cycleMarkersAreRecordedEvenWhenFanOutCapFires() {
        ProjectIndex index = new ProjectIndex();
        MethodKey target = new MethodKey("com.example.Target", "leaf", 0);
        index.putDeclaration(target, new ProjectIndex.SourceLoc("Target.java", 1));
        // 600 ordinary callers, inserted first so the cap fires before
        // the self-cycle below is reached.
        for (int i = 1; i <= 600; i++) {
            MethodKey c = new MethodKey("com.example.C" + i, "call", 0);
            index.putDeclaration(c, new ProjectIndex.SourceLoc("C" + i + ".java", 1));
            index.recordInvocation(c, target);
        }
        index.recordInvocation(target, target);   // self-cycle, inserted last

        CallChainAnalyzer.Result r = new CallChainAnalyzer().traceCallers(index, target);
        List<Map<String, Object>> callers =
                (List<Map<String, Object>>) r.root().toJson().get("callers");

        boolean hasCycle = callers.stream()
                .anyMatch(m -> Boolean.TRUE.equals(m.get("cycle")));
        assertTrue(hasCycle,
                "the self-cycle must be recorded as a cycle marker even beyond the cap");

        Map<String, Object> fanout = callers.get(callers.size() - 1);
        assertTrue(fanout.containsKey("truncatedCallers"),
                "expected a fan-out marker, got: " + callers);
        assertEquals(100, ((Number) fanout.get("truncatedCallers")).intValue(),
                "hidden must count only omitted non-cycle callers");
    }
}
