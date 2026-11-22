package com.codescope.dependency;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The classpath disk cache behind the CLI's warm runs (issue #9 follow-up):
 * a hit avoids the whole {@code mvn dependency:build-classpath} call, which
 * dominated a large project's warm time.
 */
class ClasspathCacheTest {

    private static void writePom(Path root, String dependency) throws Exception {
        Files.writeString(root.resolve("pom.xml"), """
                <?xml version="1.0"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>x</groupId><artifactId>x</artifactId><version>1</version>
                  <dependencies>
                    <dependency>
                      <groupId>%s</groupId><artifactId>%s</artifactId><version>1</version>
                    </dependency>
                  </dependencies>
                </project>
                """.formatted(dependency, dependency));
    }

    @Test
    void roundTripsThroughDisk(@TempDir Path tmp) throws Exception {
        writePom(tmp, "a");
        String fingerprint = ClasspathCache.fingerprint(tmp, List.of(), "");
        assertNotNull(fingerprint);
        ClasspathCache.save(fingerprint, List.of("/repo/a.jar", "/repo/b.jar"));
        assertEquals(List.of("/repo/a.jar", "/repo/b.jar"), ClasspathCache.load(fingerprint));
    }

    @Test
    void pomChangeInvalidates(@TempDir Path tmp) throws Exception {
        writePom(tmp, "a");
        String before = ClasspathCache.fingerprint(tmp, List.of(), "");
        ClasspathCache.save(before, List.of("/repo/a.jar"));
        assertEquals(List.of("/repo/a.jar"), ClasspathCache.load(before));

        writePom(tmp, "b");
        String after = ClasspathCache.fingerprint(tmp, List.of(), "");
        assertNotEquals(before, after, "a dependency change must change the key");
        assertNull(ClasspathCache.load(after), "the new key has no cached classpath yet");
    }

    @Test
    void extraArgsArePartOfTheKey(@TempDir Path tmp) throws Exception {
        writePom(tmp, "a");
        String plain = ClasspathCache.fingerprint(tmp, List.of(), "");
        String withSettings = ClasspathCache.fingerprint(tmp, List.of("-gs", "/tmp/s.xml"), "");
        assertNotEquals(plain, withSettings,
                "different Maven args can resolve to a different classpath");
    }

    @Test
    void unknownKeyMisses(@TempDir Path tmp) throws Exception {
        assertNull(ClasspathCache.load("no-such-fingerprint"));
    }
}
