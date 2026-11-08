package com.codescope.dependency;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class MvnCliDependencyResolverTest {

    @Test
    void throwsWhenNoPomFound(@TempDir Path tmp) {
        MvnCliDependencyResolver r = new MvnCliDependencyResolver();
        IOException e = assertThrows(IOException.class, () -> r.resolve(tmp));
        assertTrue(e.getMessage().contains("pom.xml"), "got: " + e.getMessage());
    }

    @Test
    void parsesClasspathOutput(@TempDir Path tmp) throws Exception {
        // Simulate the content that mvn dependency:build-classpath writes:
        // a colon-separated list of absolute jar paths.
        Path outputFile = tmp.resolve("classpath.out");
        Files.writeString(outputFile,
                "/home/user/.m2/repository/com/google/guava/guava/32.1.3/guava-32.1.3.jar"
                + File.pathSeparator
                + "/home/user/.m2/repository/org/junit/jupiter/junit-jupiter/5.10.3/junit-jupiter-5.10.3.jar");

        MvnCliDependencyResolver r = new MvnCliDependencyResolver();
        List<String> cp = r.parseClasspathFile(outputFile);
        assertEquals(2, cp.size(), "got: " + cp);
        assertTrue(cp.get(0).endsWith("guava-32.1.3.jar"));
        assertTrue(cp.get(1).endsWith("junit-jupiter-5.10.3.jar"));
    }

    @Test
    void emptyOutputReturnsEmptyList(@TempDir Path tmp) throws Exception {
        Path outputFile = tmp.resolve("empty.cp");
        Files.writeString(outputFile, "");

        MvnCliDependencyResolver r = new MvnCliDependencyResolver();
        List<String> cp = r.parseClasspathFile(outputFile);
        assertTrue(cp.isEmpty(), "expected empty, got: " + cp);
    }

    @Test
    void resolveMvnCommandDefaultsToMvn(@TempDir Path tmp) throws Exception {
        // No wrapper, no MAVEN_HOME -> returns "mvn" (or "mvn.cmd" on Windows)
        String cmd = MvnCliDependencyResolver.resolveMvnCommand(tmp);
        if (System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT).contains("win")) {
            assertEquals("mvn.cmd", cmd);
        } else {
            assertEquals("mvn", cmd);
        }
    }

    @Test
    void resolveMvnCommandFindsMvnwWrapper(@TempDir Path tmp) throws Exception {
        // Create a fake mvnw wrapper in the project root
        Path wrapper = tmp.resolve("mvnw");
        Files.writeString(wrapper, "#!/bin/bash\necho fake mvnw");
        wrapper.toFile().setExecutable(true);

        String cmd = MvnCliDependencyResolver.resolveMvnCommand(tmp);
        assertTrue(cmd.endsWith("mvnw"), "expected mvnw wrapper, got: " + cmd);
    }

    @Test
    void resolveMvnCommandFindsMvnwCmdOnWindows(@TempDir Path tmp) throws Exception {
        // Both mvnw and mvnw.cmd exist in the project root.
        Files.writeString(tmp.resolve("mvnw"), "#!/bin/bash");
        tmp.resolve("mvnw").toFile().setExecutable(true);
        Files.writeString(tmp.resolve("mvnw.cmd"), "@echo fake");

        String cmd = MvnCliDependencyResolver.resolveMvnCommand(tmp);
        // On Linux: should prefer mvnw over mvnw.cmd
        // On Windows: should prefer mvnw.cmd over mvnw
        boolean isWin = System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT).contains("win");
        if (isWin) {
            assertTrue(cmd.endsWith("mvnw.cmd"), "expected mvnw.cmd on Windows, got: " + cmd);
        } else {
            assertTrue(cmd.endsWith("mvnw"), "expected mvnw on Linux, got: " + cmd);
        }
    }

    @Test
    void buildCommandDropsEmptyExtraArgs(@TempDir Path tmp) throws Exception {
        // A leading/trailing space in -Dcodescope.mvn.args makes
        // String.split("\\s+") emit an empty first/last element; passing
        // that empty string through to Maven is at best ignored and at
        // worst a malformed invocation.
        System.setProperty("codescope.mvn.args", "  -gs   /tmp/settings.xml  ");
        try {
            List<String> cmd = new MvnCliDependencyResolver()
                    .buildCommand(tmp, tmp.resolve("cp.out"));
            assertFalse(cmd.contains(""), "command must not contain empty args: " + cmd);
            assertTrue(cmd.contains("-gs"), "got: " + cmd);
            assertTrue(cmd.contains("/tmp/settings.xml"), "got: " + cmd);
        } finally {
            System.clearProperty("codescope.mvn.args");
        }
    }

    @Test
    void resolveTimesOutWhenMavenRunsTooLong() throws Exception {
        // A zero-second timeout forces the wait to expire immediately. The
        // fixture project's mvn invocation takes seconds, so this reliably
        // exercises the timeout path instead of a real build.
        System.setProperty("codescope.mvn.timeout", "0");
        try {
            MvnCliDependencyResolver r = new MvnCliDependencyResolver();
            IOException e = assertThrows(IOException.class, () -> r.resolve(
                    Path.of("src/test/resources/fixture-project")));
            assertTrue(e.getMessage().contains("timed out"), "got: " + e.getMessage());
        } finally {
            System.clearProperty("codescope.mvn.timeout");
        }
    }

    @Test
    void resolvesInFixtureProject() throws Exception {
        // Integration test: run against the fixture project (no deps, just
        // verify the mvn command succeeds and returns something).
        MvnCliDependencyResolver r = new MvnCliDependencyResolver();
        List<String> cp = r.resolve(Path.of("src/test/resources/fixture-project"));
        // The fixture project has no dependencies, so the classpath
        // should be empty or contain only the project's own output dir.
        assertNotNull(cp);
    }
}
