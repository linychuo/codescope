package com.codescope;

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
