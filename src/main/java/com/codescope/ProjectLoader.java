package com.codescope;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/** Locates Java source files, JRE jars, and a Maven classpath for a project root. */
public final class ProjectLoader {

    /**
     * Cap on directory-tree walks to prevent runaway scans in projects with
     * pathological depth (deeply-nested vendored deps, etc.).
     */
    private static final int MAX_DIRECTORY_DEPTH = 12;

    public record LoadResult(
            List<Path> sources,
            List<String> classpath,
            List<String> sourcepath) {}

    /**
     * Loads sources + classpath for a Maven project.
     *
     * Sources: every .java under any src/ directory.
     * Classpath: JRE modules + every dependency jar in pom.xml (transitive).
     * Sourcepath: every src/&lt;...&gt;/java directory under the project, so JDT can
     *             resolve bindings across our own .java files.
     */
    public LoadResult load(Path projectRoot) throws IOException {
        List<Path> sources = collectSources(projectRoot);
        List<String> classpath = new MavenClasspathResolver().resolve(projectRoot);
        classpath.addAll(jreClasspath());
        List<String> sourcepath = collectSourceRoots(projectRoot);
        return new LoadResult(sources, classpath, sourcepath);
    }

    /** Walks every src/ directory under the project (multi-module aware) and returns every .java file. */
    public static List<Path> collectSources(Path projectRoot) throws IOException {
        List<Path> out = new ArrayList<>();
        for (Path root : collectSourceRoots0(projectRoot)) {
            try (Stream<Path> s = Files.walk(root)) {
                s.filter(Files::isRegularFile)
                        .filter(p -> p.getFileName().toString().endsWith(".java"))
                        .forEach(out::add);
            }
        }
        return out;
    }

    /** Every src/&lt;...&gt;/main/java directory under the project root. */
    public static List<String> collectSourceRoots(Path projectRoot) {
        return collectSourceRoots0(projectRoot).stream().map(Path::toString).toList();
    }

    private static List<Path> collectSourceRoots0(Path projectRoot) {
        List<Path> out = new ArrayList<>();
        if (!Files.isDirectory(projectRoot)) return out;
        try (Stream<Path> s = Files.walk(projectRoot, MAX_DIRECTORY_DEPTH)) {
            s.filter(Files::isDirectory)
                    // we want a directory whose name is "java"
                    .filter(p -> p.getFileName().toString().equals("java"))
                    // whose parent is "main" (e.g. src/main/java, core/src/main/java,
                    // and annotation-processor outputs like
                    // target/generated-sources/annotations/main/java)
                    .filter(p -> {
                        Path parent = p.getParent();
                        return parent != null && parent.getFileName().toString().equals("main");
                    })
                    // node_modules is the only path segment we still refuse to
                    // descend into wholesale. target/ and build/ used to be
                    // blanket-excluded here, but that hides real source roots
                    // under target/generated-sources/... (MapStruct, JAXB,
                    // Spring Data, etc.). Compiled bytecode dirs like
                    // target/classes are filtered out naturally by the .java
                    // extension check in collectSources, so we don't need
                    // a directory-level blacklist for them.
                    .filter(p -> !relativeSegmentEquals(p, projectRoot, "node_modules"))
                    .forEach(out::add);
        } catch (IOException e) {
            // best-effort
        }
        return out;
    }

    private static boolean relativeSegmentEquals(Path p, Path root, String segment) {
        if (root == null) return false;
        Path rel = root.relativize(p);
        for (Path part : rel) {
            if (part.toString().equals(segment)) return true;
        }
        return false;
    }

    /**
     * Returns the JRE jars needed for binding resolution.
     * Java 9+: lib/jrt-fs.jar (the system module image).
     * Java 8: lib/rt.jar + a couple others.
     */
    public static List<String> jreClasspath() {
        String javaHome = System.getProperty("java.home");
        if (javaHome == null) return List.of();
        List<String> cp = new ArrayList<>();

        Path jrt = Path.of(javaHome, "lib", "jrt-fs.jar");
        if (Files.isRegularFile(jrt)) {
            cp.add(jrt.toString());
            return cp;
        }

        for (String name : new String[]{"rt.jar", "resources.jar", "jsse.jar"}) {
            Path p = Path.of(javaHome, "lib", name);
            if (Files.isRegularFile(p)) cp.add(p.toString());
        }
        return cp;
    }
}
