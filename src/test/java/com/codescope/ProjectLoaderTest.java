package com.codescope;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies {@link ProjectLoader#collectSources} discovers Java sources
 * across the path shapes a real Maven/Gradle project produces — not just
 * {@code src/main/java}. The class-under-test is static, no fixture
 * resource needed; each test builds its own tree under a temp dir.
 */
class ProjectLoaderTest {

    /** Writes a minimal {@code pom.xml} so {@code collectSources} runs cleanly. */
    private static void writePom(Path root) throws IOException {
        Files.writeString(root.resolve("pom.xml"),
                "<?xml version=\"1.0\"?><project xmlns=\"http://maven.apache.org/POM/4.0.0\">"
                        + "<modelVersion>4.0.0</modelVersion>"
                        + "<groupId>x</groupId><artifactId>x</artifactId><version>1</version>"
                        + "</project>");
    }

    private static String writeJava(Path file, String pkg, String className) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, "package " + pkg + ";\nclass " + className + " {}\n");
        return pkg + "." + className;
    }

    @Test
    void includesPlainSrcMainJava(@TempDir Path root) throws IOException {
        writePom(root);
        writeJava(root.resolve("src/main/java/com/x/A.java"), "com.x", "A");
        writeJava(root.resolve("src/main/java/com/x/B.java"), "com.x", "B");

        List<Path> sources = ProjectLoader.collectSources(root);
        // both files collected
        assertEquals(2, sources.size(), "expected both src/main/java files, got " + sources);
    }

    @Test
    void includesGeneratedSourcesUnderTarget(@TempDir Path root) throws IOException {
        // Simulates MapStruct / JAXB / Spring Data processor output:
        //   target/generated-sources/main/java/...
        // Without the fix, the grandparent-must-be-"src" filter rejects this.
        writePom(root);
        writeJava(root.resolve("src/main/java/com/x/Entity.java"), "com.x", "Entity");
        writeJava(root.resolve("target/generated-sources/main/java/com/x/EntityImpl.java"),
                "com.x", "EntityImpl");

        List<Path> sources = ProjectLoader.collectSources(root);
        assertTrue(sources.stream().anyMatch(p -> p.toString().endsWith("EntityImpl.java")),
                "expected EntityImpl.java (target/generated-sources) to be indexed, got: "
                        + sources);
    }

    @Test
    void includesGeneratedSourcesUnderAnnotationsSubdir(@TempDir Path root) throws IOException {
        // The realistic MapStruct / Spring Data path: processor writes under
        //   target/generated-sources/annotations/main/java/...
        // (one more level of nesting than the previous test).
        writePom(root);
        writeJava(root.resolve("src/main/java/com/x/Entity.java"), "com.x", "Entity");
        writeJava(root.resolve("target/generated-sources/annotations/main/java/com/x/EntityMapperImpl.java"),
                "com.x", "EntityMapperImpl");

        List<Path> sources = ProjectLoader.collectSources(root);
        assertTrue(sources.stream().anyMatch(p -> p.toString().endsWith("EntityMapperImpl.java")),
                "expected EntityMapperImpl.java (target/generated-sources/annotations/main/java) "
                        + "to be indexed, got: " + sources);
    }

    @Test
    void excludesTargetClassesAndBuildClasses(@TempDir Path root) throws IOException {
        // target/classes and build/classes typically hold .class files only,
        // not .java — but make sure a stray .java there doesn't sneak through
        // and corrupt the index with compiled bytecode-shaped junk.
        writePom(root);
        writeJava(root.resolve("src/main/java/com/x/A.java"), "com.x", "A");
        writeJava(root.resolve("target/classes/com/x/A.java"), "com.x", "AClassShadow");
        writeJava(root.resolve("build/classes/com/x/A.java"), "com.x", "ABuildShadow");

        List<Path> sources = ProjectLoader.collectSources(root);
        for (Path p : sources) {
            String s = p.toString();
            assertFalse(s.contains("/target/classes/"),
                    "target/classes should not be a source root: " + s);
            assertFalse(s.contains("/build/classes/"),
                    "build/classes should not be a source root: " + s);
        }
    }

    @Test
    void includesModuleSrcMainJavaInMultiModuleProject(@TempDir Path root) throws IOException {
        // Aggregator pom at root, modules as siblings with their own src/main/java.
        writePom(root);
        Files.writeString(root.resolve("pom.xml"),
                "<?xml version=\"1.0\"?><project xmlns=\"http://maven.apache.org/POM/4.0.0\">"
                        + "<modelVersion>4.0.0</modelVersion>"
                        + "<groupId>x</groupId><artifactId>parent</artifactId><version>1</version>"
                        + "<packaging>pom</packaging><modules><module>core</module><module>app</module></modules>"
                        + "</project>");
        writeJava(root.resolve("core/src/main/java/com/x/core/Helper.java"),
                "com.x.core", "Helper");
        writeJava(root.resolve("app/src/main/java/com/x/app/Entry.java"),
                "com.x.app", "Entry");

        List<Path> sources = ProjectLoader.collectSources(root);
        assertEquals(2, sources.size(), "expected both modules' sources, got " + sources);
    }

    @Test
    void absoluteRelativePathIsRejected(@TempDir Path projectRoot) throws IOException {
        // A hostile pom with <relativePath>/absolute/path/pom.xml</relativePath>
        // would, without hardening, let discoverEffectiveRoot jump out of
        // the project tree. Path.resolve() treats absolute paths as
        // replacement, not append, so an attacker can point at any file
        // on the filesystem. We seed a real decoy pom at a separate
        // location so the Files.exists() gate doesn't trivially reject
        // the attack — the absolute-path branch itself must be skipped.
        //
        // The pom is written with explicit line breaks because
        // hasParentSection does a line-based substring check; a single-
        // line XML would never trigger the parent resolution path at all.
        Path decoy = Files.createTempDirectory("codescope-decoy-");
        try {
            Files.writeString(decoy.resolve("pom.xml"),
                    "<?xml version=\"1.0\"?>\n"
                            + "<project xmlns=\"http://maven.apache.org/POM/4.0.0\">\n"
                            + "  <modelVersion>4.0.0</modelVersion>\n"
                            + "  <groupId>decoy</groupId>\n"
                            + "  <artifactId>decoy</artifactId>\n"
                            + "  <version>1</version>\n"
                            + "</project>\n");
            String absDecoy = decoy.resolve("pom.xml").toAbsolutePath().toString();
            Files.writeString(projectRoot.resolve("pom.xml"),
                    "<?xml version=\"1.0\"?>\n"
                            + "<project xmlns=\"http://maven.apache.org/POM/4.0.0\">\n"
                            + "  <modelVersion>4.0.0</modelVersion>\n"
                            + "  <groupId>x</groupId>\n"
                            + "  <artifactId>evil</artifactId>\n"
                            + "  <version>1</version>\n"
                            + "  <parent>\n"
                            + "    <groupId>x</groupId>\n"
                            + "    <artifactId>parent</artifactId>\n"
                            + "    <version>1</version>\n"
                            + "    <relativePath>" + absDecoy + "</relativePath>\n"
                            + "  </parent>\n"
                            + "</project>\n");

            Path effective = ProjectLoader.discoverEffectiveRoot(projectRoot);
            Path normalized = effective.toAbsolutePath().normalize();
            assertFalse(normalized.startsWith(decoy.toAbsolutePath().normalize()),
                    "absolute <relativePath> must not pull discoverEffectiveRoot out of "
                            + "the project, got effective=" + effective);
            assertEquals(projectRoot.toAbsolutePath().normalize(), normalized,
                    "effective root should stay at projectRoot when relativePath is rejected, "
                            + "got: " + effective);
        } finally {
            try (var s = Files.walk(decoy)) {
                s.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                    try { Files.deleteIfExists(p); } catch (java.io.IOException ignored) {}
                });
            }
        }
    }
}
