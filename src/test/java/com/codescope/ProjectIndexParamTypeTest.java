package com.codescope;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression tests for issue #3: resolveTarget / findInvokedKeys were
 * too strict on paramTypes — callers (MCP clients, AI agents) frequently
 * pass short names ("DTO") or types from a different module, and the
 * exact-FQN {@code List.equals} check made resolveTarget miss them.
 *
 * The fix: when the user-supplied paramTypes don't strict-match any
 * declaration, fall back to a "FQN suffix" match — a candidate paramType
 * is considered a match if its last {@code .}-delimited segment equals
 * the user's last segment. (E.g. user passes {@code "DTO"}, declaration
 * is {@code "com.example.dto.DTO"}; both end in {@code "DTO"}.)
 */
class ProjectIndexParamTypeTest {

    private ProjectIndex index;

    @BeforeEach
    void setUp() throws IOException {
        Path fixture = Path.of("src/test/resources/fixture-project");
        ProjectLoader.LoadResult load = new ProjectLoader().load(fixture);
        index = new JdtIndexer().build(load.sources(), load.classpath(), load.sourcepath(), fixture);
    }

    @Test
    void resolveTargetMatchesByFqnSuffixWhenShortNameGiven() throws Exception {
        // Pick a target with a non-trivial parameter type. fixture has
        // com.example.GenericHost#doIt which takes java.lang.Object.
        // The user might pass the short name "Object" — which should
        // still match com.example.GenericHost#doIt(Object).
        MethodKey m = index.resolveTarget(
                "com.example.GenericHost", "doIt", 1, List.of("Object"));
        assertNotNull(m, "should resolve via FQN-suffix match for short name 'Object'");
        assertEquals(1, m.arity);
        assertEquals("doIt", m.methodName);
    }

    @Test
    void resolveTargetStillRequiresCorrectArity() throws Exception {
        // Arity 0 should NOT match arity 1 even if the (empty) param list
        // trivially suffix-matches the (single) declared param.
        MethodKey m = index.resolveTarget(
                "com.example.GenericHost", "doIt", 0, List.of());
        assertNull(m, "arity mismatch must still be rejected");
    }
}
