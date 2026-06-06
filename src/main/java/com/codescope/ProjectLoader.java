package com.codescope;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/** Locates Java source files, JRE jars, and a Maven classpath for a project root. */
public final class ProjectLoader {

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

    /** Every src/<...>/java directory under the project root, skipping test source roots. */
    public static List<String> collectSourceRoots(Path projectRoot) {
        return collectSourceRoots0(projectRoot).stream().map(Path::toString).toList();
    }

    private static List<Path> collectSourceRoots0(Path projectRoot) {
        List<Path> out = new ArrayList<>();
        if (!Files.isDirectory(projectRoot)) return out;
        try (Stream<Path> s = Files.walk(projectRoot)) {
            s.filter(Files::isDirectory)
                    // we want a directory whose name is "java"
                    .filter(p -> p.getFileName().toString().equals("java"))
                    // whose parent is "main" (e.g. src/main/java, core/src/main/java)
                    .filter(p -> {
                        Path parent = p.getParent();
                        return parent != null && parent.getFileName().toString().equals("main");
                    })
                    // and we don't want to descend into build outputs
                    .filter(p -> !relativeSegmentEquals(p, projectRoot, "target")
                            && !relativeSegmentEquals(p, projectRoot, "build")
                            && !relativeSegmentEquals(p, projectRoot, "node_modules"))
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
