package com.codescope;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
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
        // If --project points to a sub-module, walk up to the aggregator
        // so sibling modules' sources and dependencies are included.
        // See discoverEffectiveRoot.
        Path effective = discoverEffectiveRoot(projectRoot);
        List<Path> sources = collectSources(effective);
        List<String> classpath = new MavenClasspathResolver().resolve(effective);
        classpath.addAll(jreClasspath());
        List<String> sourcepath = collectSourceRoots(effective);
        return new LoadResult(sources, classpath, sourcepath);
    }

    /**
     * Returns the directory that should be used as the effective project
     * root for indexing. If {@code projectRoot} contains a pom.xml that
     * has a {@code <parent>} reference (i.e. it is a sub-module), walk
     * up the chain to the aggregator — the nearest ancestor whose pom
     * has {@code <modules>...</modules>}. Otherwise, return
     * {@code projectRoot} unchanged.
     *
     * <p>This makes {@code --project} forgiving: pointing at any sub-module
     * gives the same view as pointing at the aggregator, so cross-module
     * call edges (e.g. {@code Helper.coreMethod} called by
     * {@code app.Entry.run}) are visible regardless of which directory the
     * user started from.
     *
     * <p>Bounded depth to defend against pathological
     * {@code relativePath} values; the loop is also self-terminating
     * because we only walk up, not sideways.
     */
    public static Path discoverEffectiveRoot(Path projectRoot) {
        if (projectRoot == null || !Files.isDirectory(projectRoot)) {
            return projectRoot;
        }
        Path current = projectRoot;
        for (int depth = 0; depth < MAX_PARENT_WALK_DEPTH; depth++) {
            Path pom = current.resolve("pom.xml");
            if (!Files.isRegularFile(pom)) {
                // No pom here — give up walking up.
                return current;
            }
            if (!hasParentSection(pom)) {
                // No <parent> in this pom — it's the aggregator (or a
                // standalone project). Stop here.
                return current;
            }
            // This pom has a <parent>. Try to find the parent directory.
            Path parentDir = resolveParentDirectory(current, pom);
            if (parentDir == null) {
                // Can't resolve parent — stop here so we don't guess.
                return current;
            }
            current = parentDir;
        }
        return current;
    }

    /**
     * Cap on the parent-pom walk to defend against cycles (e.g. a
     * {@code relativePath} that points at a child, or a circular
     * symbolic-link setup). 8 levels is more than enough for any real
     * Maven layout.
     */
    private static final int MAX_PARENT_WALK_DEPTH = 8;

    /** Lightweight check: does this pom declare a {@code <parent>}? */
    private static boolean hasParentSection(Path pom) {
        try (Stream<String> lines = Files.lines(pom)) {
            return lines.anyMatch(line -> {
                String trimmed = line.trim();
                // Match an opening <parent> tag (with or without attrs).
                return trimmed.startsWith("<parent>")
                        || trimmed.startsWith("<parent ");
            });
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Resolve the parent directory for a child pom. Returns null if the
     * parent's location can't be determined — caller should stop the
     * walk-up rather than guess.
     *
     * <p>Maven's {@code <parent><relativePath>} default is
     * {@code ../pom.xml}, so we look one directory up first. If a
     * {@code relativePath} is specified, we follow it; if it's an
     * absolute path or doesn't exist, we fall back to a sibling
     * search (a pom in any ancestor directory).
     */
    private static Path resolveParentDirectory(Path current, Path pom) {
        // Default Maven relativePath: ../pom.xml
        Path defaultParent = current.getParent();
        if (defaultParent != null
                && Files.isRegularFile(defaultParent.resolve("pom.xml"))) {
            return defaultParent;
        }
        // Read <relativePath> from the pom
        String rel = readRelativePath(pom);
        if (rel != null && !rel.isEmpty()) {
            // relativePath is a path to the parent pom.xml (or its
            // directory). Maven treats values ending in "pom.xml" as
            // a file and others as a directory.
            Path resolved = current.resolve(rel).toAbsolutePath().normalize();
            if (Files.exists(resolved)) {
                Path dir = Files.isDirectory(resolved) ? resolved : resolved.getParent();
                if (dir != null) return dir;
            }
        }
        // Last resort: walk up to the nearest directory that has a
        // pom.xml without a <parent> (the aggregator). This handles
        // the case where relativePath is missing/wrong.
        Path ancestor = current.getParent();
        while (ancestor != null) {
            Path ancestorPom = ancestor.resolve("pom.xml");
            if (Files.isRegularFile(ancestorPom) && !hasParentSection(ancestorPom)) {
                return ancestor;
            }
            ancestor = ancestor.getParent();
        }
        return null;
    }

    /**
     * Extract the {@code <relativePath>} value from a child pom, or
     * null if not specified. Best-effort line scan — we don't need a
     * full XML parser for this.
     */
    private static String readRelativePath(Path pom) {
        try (Stream<String> lines = Files.lines(pom)) {
            Iterator<String> it = lines.iterator();
            while (it.hasNext()) {
                String line = it.next().trim();
                if (!line.startsWith("<relativePath>") && !line.startsWith("<relativePath ")) {
                    continue;
                }
                int open = line.indexOf('>');
                if (open < 0 || line.startsWith("</")) continue;
                int close = line.indexOf("</relativePath>", open);
                if (close > open) {
                    return line.substring(open + 1, close).trim();
                }
            }
        } catch (IOException e) {
            // ignore
        }
        return null;
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
