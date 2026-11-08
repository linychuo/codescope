package com.codescope.loader;

import static org.junit.jupiter.api.Assertions.*;
import com.codescope.analysis.CallChainAnalyzer;
import com.codescope.index.JdtIndexer;
import com.codescope.index.ProjectIndex;
import com.codescope.index.ProjectIndexCache;
import com.codescope.model.MethodKey;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Verifies multi-module Maven support and pom-finding behavior. */
class MultiModuleTest {

    @Test
    void indexerResolvesBindingsAcrossModules() throws IOException {
        Path root = Path.of("src/test/resources/multi-module-fixture");
        ProjectLoader.LoadResult load = new ProjectLoader().load(root, false);
        // sources from both modules
        if (load.sources().size() < 2) {
            throw new AssertionError("expected sources from both modules; got "
                    + load.sources().size() + ": " + load.sources());
        }
        ProjectIndex index = new JdtIndexer().build(
                load.sources(), load.classpath(), load.sourcepath(), root);

        // Helper.coreMethod declared in core, called from app.Entry.run
        MethodKey core;
        try {
            core = index.resolveTarget("com.example.core.Helper", "coreMethod");
        } catch (ProjectIndex.AmbiguousMethodException e) {
            throw new AssertionError(e);
        }
        assertNotNull(core, "Helper.coreMethod should resolve");

        CallChainAnalyzer.Result r = new CallChainAnalyzer().traceCallers(index, core);
        assertTrue(r.found());

        @SuppressWarnings("unchecked")
        List<java.util.Map<String, Object>> callers =
                (List<java.util.Map<String, Object>>) r.root().toJson().get("callers");
        assertNotNull(callers);
        assertEquals(1, callers.size());
        assertEquals("com.example.app.Entry#run/0", callers.get(0).get("signature"));
    }

    @Test
    void subProjectRootDiscoversAggregator() throws IOException {
        // When --project points to a sub-module (not the aggregator), the
        // loader should still see all sibling modules — by walking up to
        // the parent pom that has <modules>...</modules>. Otherwise, a
        // user pointing at `app/` loses `core/`'s declarations, and any
        // cross-module call edge (Helper.coreMethod ← Entry.run) is
        // missing from the chain.
        Path subModule = Path.of("src/test/resources/multi-module-fixture/app");
        ProjectLoader.LoadResult load = new ProjectLoader().load(subModule, false);
        // Sources from BOTH modules must be reachable
        boolean hasAppSource = load.sources().stream()
                .anyMatch(p -> p.toString().contains("/app/src/main/java"));
        boolean hasCoreSource = load.sources().stream()
                .anyMatch(p -> p.toString().contains("/core/src/main/java"));
        assertTrue(hasAppSource, "expected app/ source, got: " + load.sources());
        assertTrue(hasCoreSource,
                "expected core/ source discovered via aggregator walk-up, got: " + load.sources());

        ProjectIndex index = new JdtIndexer().build(
                load.sources(), load.classpath(), load.sourcepath(), subModule);
        MethodKey core;
        try {
            core = index.resolveTarget("com.example.core.Helper", "coreMethod");
        } catch (ProjectIndex.AmbiguousMethodException e) {
            throw new AssertionError(e);
        }
        assertNotNull(core);

        CallChainAnalyzer.Result r = new CallChainAnalyzer().traceCallers(index, core);
        assertTrue(r.found());
        @SuppressWarnings("unchecked")
        List<java.util.Map<String, Object>> callers =
                (List<java.util.Map<String, Object>>) r.root().toJson().get("callers");
        assertNotNull(callers, "expected app.Entry.run to call core.Helper.coreMethod even when --project=app/");
        assertEquals(1, callers.size());
        assertEquals("com.example.app.Entry#run/0", callers.get(0).get("signature"));
    }

    @Test
    void cacheRelativizesSubModuleAgainstEffectiveRoot() throws Exception {
        // Pointing --project at a sub-module walks up to the aggregator
        // for sources; the index must relativize against that aggregator
        // too. Relativizing against the sub-module escapes the project
        // root with ".." segments in every sibling-module file path.
        Path subModule = Path.of("src/test/resources/multi-module-fixture/app");
        ProjectIndex index = new ProjectIndexCache().loadOrRebuild(subModule, false, false);
        assertFalse(index.knownMethods().isEmpty());
        for (MethodKey k : index.knownMethods()) {
            ProjectIndex.SourceLoc loc = index.declarationOf(k);
            if (loc != null) {
                assertFalse(loc.file().contains(".."),
                        "file path must be relative to the effective root, got: " + loc.file());
            }
        }
    }

    @Test
    void aggregatorAsProjectRootIsUnchanged() throws IOException {
        // Regression: pointing --project at the aggregator directly must
        // keep working (no double-walk-up). The fixture's root is
        // <packaging>pom</packaging> with <modules>...</modules>.
        Path root = Path.of("src/test/resources/multi-module-fixture");
        Path effective = ProjectLoader.discoverEffectiveRoot(root);
        assertEquals(root, effective,
                "aggregator should be its own effective root (no walk-up). Got: " + effective);
    }

    @Test
    void jreClasspathReturnsAtLeastOneJar() {
        // Sanity check: on the current JVM (Java 21), ProjectLoader.jreClasspath
        // must yield at least one entry — the jrt-fs.jar path. A typo in
        // the path string would silently return an empty list and starve
        // JDT of binding sources.
        List<String> cp = ProjectLoader.jreClasspath();
        assertFalse(cp.isEmpty(), "jreClasspath should return at least one jar, got: " + cp);
    }
}
