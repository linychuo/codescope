package com.codescope;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Direct tests for {@link MavenClasspathResolver}: pom finding, jar resolution
 * (with the -sources/-javadoc/-tests exclusion), transitive deps, and
 * malformed-pom tolerance.
 */
class MavenClasspathResolverTest {

    @Test
    void throwsWhenNoPomFound(@TempDir Path tmp) {
        MavenClasspathResolver r = new MavenClasspathResolver(tmp);
        IOException e = assertThrows(IOException.class, () -> r.resolve(tmp));
        assertTrue(e.getMessage().contains("No pom.xml"), "got: " + e.getMessage());
    }

    @Test
    void emptyProjectDirYieldsNoPoms(@TempDir Path tmp) throws Exception {
        // Files.walk on a non-directory short-circuits to empty.
        Path nonExistent = tmp.resolve("nope");
        List<Path> poms = MavenClasspathResolver.findPoms(nonExistent);
        assertTrue(poms.isEmpty());
    }

    @Test
    void malformedPomIsSkippedNotFatal(@TempDir Path tmp) throws Exception {
        Files.writeString(tmp.resolve("pom.xml"), "<project><dependencies>");  // unterminated
        MavenClasspathResolver r = new MavenClasspathResolver(tmp);
        // No exception — we just get no classpath entries.
        List<String> cp = r.resolve(tmp);
        assertTrue(cp.isEmpty(), "expected no jars from malformed pom, got: " + cp);
    }

    @Test
    void skipsTestAndProvidedAndOptionalScopes(@TempDir Path tmp) throws Exception {
        // Fake local repo with four jars
        Path repo = tmp.resolve("repo");
        Path g = repo.resolve("com/example");
        for (String art : new String[]{"compile-dep", "test-dep", "provided-dep", "optional-dep"}) {
            Path dir = g.resolve(art).resolve("1.0.0");
            Files.createDirectories(dir);
            Files.writeString(dir.resolve(art + "-1.0.0.jar"), "fake");
        }

        Files.writeString(tmp.resolve("pom.xml"), """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>g</groupId><artifactId>a</artifactId><version>1.0.0</version>
                  <dependencies>
                    <dependency>
                      <groupId>com.example</groupId><artifactId>compile-dep</artifactId><version>1.0.0</version>
                    </dependency>
                    <dependency>
                      <groupId>com.example</groupId><artifactId>test-dep</artifactId><version>1.0.0</version>
                      <scope>test</scope>
                    </dependency>
                    <dependency>
                      <groupId>com.example</groupId><artifactId>provided-dep</artifactId><version>1.0.0</version>
                      <scope>provided</scope>
                    </dependency>
                    <dependency>
                      <groupId>com.example</groupId><artifactId>optional-dep</artifactId><version>1.0.0</version>
                      <optional>true</optional>
                    </dependency>
                  </dependencies>
                </project>
                """);

        MavenClasspathResolver r = new MavenClasspathResolver(repo);
        List<String> cp = r.resolve(tmp);
        assertEquals(1, cp.size(), "expected only the compile dep, got: " + cp);
        assertTrue(cp.get(0).endsWith("compile-dep-1.0.0.jar"),
                "expected compile dep jar, got: " + cp.get(0));
    }

    @Test
    void prefersExactNamedJarOverFallback(@TempDir Path tmp) throws Exception {
        // Both exact and -sources jars exist. We must pick the exact one.
        Path repo = tmp.resolve("repo");
        Path dir = repo.resolve("com/example/lib/1.0.0");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("lib-1.0.0.jar"), "real");
        Files.writeString(dir.resolve("lib-1.0.0-sources.jar"), "src");
        Files.writeString(dir.resolve("lib-1.0.0-javadoc.jar"), "jdoc");
        Files.writeString(dir.resolve("lib-1.0.0-tests.jar"), "tests");

        Files.writeString(tmp.resolve("pom.xml"), """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>g</groupId><artifactId>a</artifactId><version>1.0.0</version>
                  <dependencies>
                    <dependency>
                      <groupId>com.example</groupId><artifactId>lib</artifactId><version>1.0.0</version>
                    </dependency>
                  </dependencies>
                </project>
                """);

        MavenClasspathResolver r = new MavenClasspathResolver(repo);
        List<String> cp = r.resolve(tmp);
        assertEquals(1, cp.size());
        assertTrue(cp.get(0).endsWith("lib-1.0.0.jar"));
    }

    @Test
    void fallbackJarExcludesClassifiers(@TempDir Path tmp) throws Exception {
        // No exact-named jar exists, only a -sources and a -javadoc. The
        // resolver should find nothing, not pick a sources jar.
        Path repo = tmp.resolve("repo");
        Path dir = repo.resolve("com/example/odd/1.0.0");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("odd-1.0.0-sources.jar"), "src");
        Files.writeString(dir.resolve("odd-1.0.0-javadoc.jar"), "jdoc");

        Files.writeString(tmp.resolve("pom.xml"), """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>g</groupId><artifactId>a</artifactId><version>1.0.0</version>
                  <dependencies>
                    <dependency>
                      <groupId>com.example</groupId><artifactId>odd</artifactId><version>1.0.0</version>
                    </dependency>
                  </dependencies>
                </project>
                """);

        MavenClasspathResolver r = new MavenClasspathResolver(repo);
        List<String> cp = r.resolve(tmp);
        assertTrue(cp.isEmpty(), "should not pick sources/javadoc, got: " + cp);
    }

    @Test
    void missingDependencyIsSilentlySkipped(@TempDir Path tmp) throws Exception {
        // Reference a dep that isn't in the local repo — resolver should
        // not throw, just produce an empty classpath.
        Files.writeString(tmp.resolve("pom.xml"), """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>g</groupId><artifactId>a</artifactId><version>1.0.0</version>
                  <dependencies>
                    <dependency>
                      <groupId>nope.nope</groupId><artifactId>missing</artifactId><version>9.9.9</version>
                    </dependency>
                  </dependencies>
                </project>
                """);

        MavenClasspathResolver r = new MavenClasspathResolver(tmp.resolve("repo"));
        List<String> cp = r.resolve(tmp);
        assertTrue(cp.isEmpty());
    }

    @Test
    void dependencyWithNoVersionIsSkipped(@TempDir Path tmp) throws Exception {
        Files.writeString(tmp.resolve("pom.xml"), """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>g</groupId><artifactId>a</artifactId><version>1.0.0</version>
                  <dependencies>
                    <dependency>
                      <groupId>com.example</groupId><artifactId>no-version</artifactId>
                    </dependency>
                  </dependencies>
                </project>
                """);

        MavenClasspathResolver r = new MavenClasspathResolver(tmp.resolve("repo"));
        List<String> cp = r.resolve(tmp);
        assertTrue(cp.isEmpty());
    }

    @Test
    void findPomsFindsMultiplePomsInTree(@TempDir Path tmp) throws Exception {
        Files.createDirectories(tmp.resolve("m1/src/main/java"));
        Files.createDirectories(tmp.resolve("m2/src/main/java"));
        Files.writeString(tmp.resolve("m1/pom.xml"), "<project/>");
        Files.writeString(tmp.resolve("m2/pom.xml"), "<project/>");

        List<Path> poms = MavenClasspathResolver.findPoms(tmp);
        assertEquals(2, poms.size());
    }

    @Test
    void findPomsIgnoresTargetBuildDir(@TempDir Path tmp) throws Exception {
        Files.createDirectories(tmp.resolve("target"));
        Files.writeString(tmp.resolve("target/pom.xml"), "<project/>");
        Files.writeString(tmp.resolve("pom.xml"), "<project/>");

        List<Path> poms = MavenClasspathResolver.findPoms(tmp);
        assertEquals(1, poms.size());
        assertEquals("pom.xml", poms.get(0).getFileName().toString());
    }
}
