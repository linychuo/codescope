package com.codescope;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies codescope's bundled-fallback classpath behavior for JSR-330 /
 * JEE APIs that are often used but rarely declared in a project pom.
 * Without this, JDT silently drops the call edge for {@code @Inject
 * Provider<T>.get()} in a project whose pom doesn't list {@code
 * javax.inject} as a dep — trace_callers stops one level above the
 * dropped call, which is exactly the user-reported "1-level chain" bug.
 */
class WellKnownApiJarTest {

    @Test
    void injectProviderCallsResolveWhenJarNotInPom(@TempDir Path root) throws IOException {
        // Build a project that uses javax.inject.Provider but does NOT
        // declare javax.inject as a dep. The CDI / Spring transitive-dep
        // case: javax.inject is on the runtime classpath via some other
        // module, but MavenClasspathResolver only sees this module's
        // pom and produces an incomplete classpath. Without the bundled
        // fallback, the chain stops at the layer that does
        // `repoProvider.get().findById()`.
        Files.writeString(root.resolve("pom.xml"),
                "<?xml version=\"1.0\"?><project xmlns=\"http://maven.apache.org/POM/4.0.0\">"
                        + "<modelVersion>4.0.0</modelVersion>"
                        + "<groupId>x</groupId><artifactId>x</artifactId><version>1</version>"
                        + "</project>");

        writeJava(root.resolve("src/main/java/x/Repository.java"),
                "package x; public interface Repository { void findById(); }");
        writeJava(root.resolve("src/main/java/x/RepositoryImpl.java"),
                "package x; public class RepositoryImpl implements Repository {"
                        + " private void sqlExec() {}"
                        + " public void findById() { sqlExec(); } }");
        writeJava(root.resolve("src/main/java/x/DomainService.java"),
                "package x; public interface DomainService { void process(); }");
        writeJava(root.resolve("src/main/java/x/DomainServiceImpl.java"),
                "package x; import javax.inject.Inject; import javax.inject.Provider;"
                        + " public class DomainServiceImpl implements DomainService {"
                        + " @Inject private Provider<Repository> repoProvider;"
                        + " public void process() { repoProvider.get().findById(); } }");
        writeJava(root.resolve("src/main/java/x/Controller.java"),
                "package x; public class Controller {"
                        + " private final DomainService svc;"
                        + " public Controller(DomainService svc) { this.svc = svc; }"
                        + " public void run() { svc.process(); } }");

        ProjectLoader.LoadResult load = new ProjectLoader().load(root);
        // The bundled fallback must have added javax.inject to the classpath.
        boolean hasInject = load.classpath().stream()
                .anyMatch(p -> p.endsWith("javax.inject-1.jar"));
        assertTrue(hasInject,
                "expected bundled javax.inject-1.jar in classpath, got: " + load.classpath());

        // And the trace must reach the Controller — that was the failure
        // mode without the fallback.
        ProjectIndex idx = new JdtIndexer().build(
                load.sources(), load.classpath(), load.sourcepath(), root);
        MethodKey sqlExec;
        try {
            sqlExec = idx.resolveTarget("x.RepositoryImpl", "sqlExec");
        } catch (ProjectIndex.AmbiguousMethodException e) {
            throw new AssertionError(e);
        }
        assertNotNull(sqlExec);
        CallChainAnalyzer.Result r = new CallChainAnalyzer().traceCallers(idx, sqlExec);
        assertTrue(r.found());

        @SuppressWarnings("unchecked")
        java.util.List<java.util.Map<String, Object>> callers =
                (java.util.List<java.util.Map<String, Object>>) r.root().toJson().get("callers");
        assertNotNull(callers, "expected RepositoryImpl.findById as a caller");
        assertEquals("x.RepositoryImpl", callers.get(0).get("class"));
        assertEquals("findById", callers.get(0).get("method"));

        @SuppressWarnings("unchecked")
        java.util.List<java.util.Map<String, Object>> implCallers =
                (java.util.List<java.util.Map<String, Object>>) callers.get(0).get("callers");
        assertNotNull(implCallers,
                "expected DomainServiceImpl.process to call findById; the chain "
                        + "stops here if javax.inject isn't on the classpath");
        assertEquals("x.DomainServiceImpl", implCallers.get(0).get("class"));
        assertEquals("process", implCallers.get(0).get("method"));

        @SuppressWarnings("unchecked")
        java.util.List<java.util.Map<String, Object>> ctlCallers =
                (java.util.List<java.util.Map<String, Object>>) implCallers.get(0).get("callers");
        assertNotNull(ctlCallers, "expected Controller.run to call process");
        assertEquals("x.Controller", ctlCallers.get(0).get("class"));
        assertEquals("run", ctlCallers.get(0).get("method"));
    }

    @Test
    void bundledJarNotDuplicatedWhenAlreadyInClasspath() throws Exception {
        // Sanity: if a classpath entry already provides javax.inject, the
        // bundled fallback must NOT be added on top — duplicate jars on
        // the classpath can confuse JDT's binding resolver. Use the real
        // javax.inject-1.jar from this machine's local Maven repo (test
        // fixture is run from a JVM with ~/.m2 available) so containsPackage
        // can actually inspect the jar's directory entries.
        java.nio.file.Path realInject = java.nio.file.Path.of(
                System.getProperty("user.home"), ".m2", "repository",
                "javax", "inject", "javax.inject", "1", "javax.inject-1.jar");
        org.junit.jupiter.api.Assumptions.assumeTrue(java.nio.file.Files.isRegularFile(realInject),
                "javax.inject-1.jar not in local Maven repo; skipping dedup test");
        java.util.List<String> out = invokeWellKnownApiJars(
                new java.util.ArrayList<>(java.util.List.of(realInject.toString())));
        long count = out.stream().filter(p -> p.endsWith("javax.inject-1.jar")).count();
        assertEquals(0, count,
                "wellKnownApiJars should skip javax.inject when it's already on the classpath, got: " + out);
    }

    /**
     * Reflectively call the private {@code wellKnownApiJars} helper. Public
     * visibility would be simpler, but the helper is an implementation
     * detail of {@link ProjectLoader#load} and shouldn't be part of the
     * API surface — reflection keeps it testable without enlarging the
     * public surface for a one-off test.
     */
    private static java.util.List<String> invokeWellKnownApiJars(
            java.util.List<String> projectClasspath) throws Exception {
        var m = ProjectLoader.class.getDeclaredMethod("wellKnownApiJars", java.util.List.class);
        m.setAccessible(true);
        @SuppressWarnings("unchecked")
        java.util.List<String> result = (java.util.List<String>) m.invoke(null, projectClasspath);
        return result;
    }

    @Test
    void bundledResourceIsExtractedOnlyOnce() throws Exception {
        // Each call to extractBundledResource used to write a fresh
        // temp file. Over a long-running MCP session that loaded many
        // distinct projects (each load goes through wellKnownApiJars),
        // the temp directory accumulated one javax.inject-1.jar per
        // project. The fix caches the extraction so every call returns
        // the same path. Asserting path-equality across two calls
        // pins the cache contract; the existence check confirms the
        // cached file is still on disk for JDT to read.
        String first = invokeWellKnownApiJars(new java.util.ArrayList<>()).stream()
                .filter(p -> p.endsWith("javax.inject-1.jar"))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "first invocation did not include javax.inject-1.jar"));
        String second = invokeWellKnownApiJars(new java.util.ArrayList<>()).stream()
                .filter(p -> p.endsWith("javax.inject-1.jar"))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "second invocation did not include javax.inject-1.jar"));
        assertEquals(first, second,
                "expected the same temp file path on repeat invocation "
                        + "(each call should reuse the cached extraction), got "
                        + first + " vs " + second);
        assertTrue(java.nio.file.Files.isRegularFile(java.nio.file.Path.of(first)),
                "cached bundled resource should still exist on disk: " + first);
    }

    private static void writeJava(Path file, String content) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }
}
