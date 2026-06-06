package com.codescope;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Verifies multi-module Maven support and pom-finding behavior. */
class MultiModuleTest {

    @Test
    void findsPomsInAllModules() throws IOException {
        Path root = Path.of("src/test/resources/multi-module-fixture");
        List<Path> poms = MavenClasspathResolver.findPoms(root);
        assertEquals(3, poms.size(), "expected root + core + app, got " + poms);
    }

    @Test
    void indexerResolvesBindingsAcrossModules() throws IOException {
        Path root = Path.of("src/test/resources/multi-module-fixture");
        ProjectLoader.LoadResult load = new ProjectLoader().load(root);
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
    void skipsTargetDirectoriesWhenLookingForPoms(@TempDir Path root) throws IOException {
        // Even if there's a stale target/.../pom.xml (e.g. resolved-pom copy), ignore it.
        Files.createDirectories(root.resolve("src/main/java"));
        Files.writeString(root.resolve("pom.xml"), "<project/>");
        // Drop a stray pom.xml inside target/ to verify it's ignored
        Files.createDirectories(root.resolve("target"));
        Files.writeString(root.resolve("target/pom.xml"), "<project/>");

        List<Path> poms = MavenClasspathResolver.findPoms(root);
        assertEquals(1, poms.size(), "expected only the top-level pom, got " + poms);
        assertTrue(poms.get(0).getFileName().toString().equals("pom.xml"));
    }

    @Test
    void settingsParserExtractsLocalRepository(@TempDir Path tmp) throws IOException {
        Path settings = tmp.resolve("settings.xml");
        Files.writeString(settings, """
                <settings>
                  <localRepository>/var/maven/custom-repo</localRepository>
                </settings>
                """);
        Path repo = MavenSettings.readLocalRepository(settings);
        assertNotNull(repo);
        assertEquals("/var/maven/custom-repo", repo.toString());

        // missing file -> null
        assertNull(MavenSettings.readLocalRepository(tmp.resolve("nope.xml")));

        // malformed xml -> null (don't crash)
        Files.writeString(settings, "<not-xml");
        assertNull(MavenSettings.readLocalRepository(settings));
    }
}
