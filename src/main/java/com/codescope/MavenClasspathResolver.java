package com.codescope;

import org.apache.maven.model.Dependency;
import org.apache.maven.model.Model;
import org.apache.maven.model.io.xpp3.MavenXpp3Reader;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Stream;

/**
 * Builds a classpath from a Maven project's pom.xml files.
 *
 * <p>Supports:
 * <ul>
 *   <li>Multi-module projects: every pom.xml under the project tree is read.</li>
 *   <li>Custom local repository: read from {@code ~/.m2/settings.xml} first.</li>
 *   <li>Transitive dependencies: walks child POMs from the local repository.</li>
 *   <li>Skips {@code test}/{@code provided} scopes and {@code optional} deps.</li>
 * </ul>
 */
public final class MavenClasspathResolver {

    private static final int MAX_POM_DEPTH = 10;
    /** Cap on the directory walk for pom discovery. */
    private static final int POM_WALK_DEPTH = 12;
    private static final int MAX_DIRECTORIES_VISITED = 5_000;

    private final Path localRepo;

    public MavenClasspathResolver() {
        this(MavenSettings.readLocalRepository() != null
                ? MavenSettings.readLocalRepository()
                : defaultLocalRepo());
    }

    public MavenClasspathResolver(Path localRepo) {
        this.localRepo = localRepo;
    }

    /**
     * @param projectRoot root of the Maven project (may contain a multi-module tree)
     * @return classpath of jar + class dir paths aggregated from every pom in the tree
     */
    public List<String> resolve(Path projectRoot) throws IOException {
        List<Path> poms = findPoms(projectRoot);
        if (poms.isEmpty()) {
            throw new IOException("No pom.xml found under " + projectRoot
                    + " (only Maven pom.xml resolution is supported)");
        }

        // Per-pom walk is I/O bound (reads ~/.m2/repository for each dep).
        // Java 21 virtual threads: spawn one per top-level pom. The recursive
        // dep walk uses ConcurrentHashMap-backed sets so concurrent writers
        // remain correct. The pool is closed by try-with-resources once all
        // walks complete.
        Set<String> jars = ConcurrentHashMap.newKeySet();
        Set<String> seenPoms = ConcurrentHashMap.newKeySet();
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> futures = new ArrayList<>(poms.size());
            for (Path pom : poms) {
                futures.add(pool.submit(() -> {
                    try {
                        walk(pom, jars, seenPoms, 0);
                    } catch (IOException e) {
                        // skip individual pom failures rather than aborting the whole resolution
                    }
                }));
            }
            for (Future<?> f : futures) {
                try {
                    f.get();
                } catch (ExecutionException | InterruptedException e) {
                    // best-effort: a single bad walk shouldn't break the rest
                }
            }
        }

        return new ArrayList<>(jars);
    }

    /** Every pom.xml under {@code projectRoot}, excluding target/ build outputs. */
    static List<Path> findPoms(Path projectRoot) throws IOException {
        List<Path> out = new ArrayList<>();
        if (!Files.isDirectory(projectRoot)) return out;
        try (Stream<Path> s = Files.walk(projectRoot, POM_WALK_DEPTH)) {
            s.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().equals("pom.xml"))
                    .filter(p -> !isUnderBuildDir(p, projectRoot))
                    .limit(MAX_DIRECTORIES_VISITED)
                    .forEach(out::add);
        }
        return out;
    }

    private static boolean isUnderBuildDir(Path p, Path root) {
        Path rel = root.relativize(p);
        for (Path part : rel) {
            String name = part.toString();
            if (name.equals("target") || name.equals("build") || name.equals("node_modules")) {
                return true;
            }
        }
        return false;
    }

    private void walk(Path pom, Set<String> out, Set<String> seenPoms, int depth) throws IOException {
        if (depth > MAX_POM_DEPTH) return;
        String key = pom.toAbsolutePath().toString();
        if (!seenPoms.add(key)) return;

        Model model;
        try (InputStream in = new FileInputStream(pom.toFile())) {
            model = new MavenXpp3Reader().read(in);
        } catch (Exception e) {
            // skip malformed poms rather than aborting the whole resolution
            return;
        }

        if (model.getDependencies() != null) {
            for (Dependency dep : model.getDependencies()) {
                if (dep.isOptional()) continue;
                if (!"jar".equalsIgnoreCase(dep.getType() != null ? dep.getType() : "jar")) continue;
                if (dep.getScope() != null && ("test".equalsIgnoreCase(dep.getScope())
                        || "provided".equalsIgnoreCase(dep.getScope()))) {
                    continue;
                }
                Path jar = findJar(dep);
                if (jar != null && out.add(jar.toString())) {
                    Path depPom = pomFor(dep);
                    if (depPom != null) walk(depPom, out, seenPoms, depth + 1);
                }
            }
        }
    }

    private Path findJar(Dependency dep) {
        String groupPath = dep.getGroupId().replace('.', '/');
        String artifactId = dep.getArtifactId();
        String version = dep.getVersion();
        if (version == null) return null;

        Path base = localRepo.resolve(groupPath).resolve(artifactId).resolve(version);
        if (!Files.isDirectory(base)) return null;

        String exact = artifactId + "-" + version + ".jar";
        Path exactPath = base.resolve(exact);
        if (Files.isRegularFile(exactPath)) return exactPath;

        // Fallback: when the conventional name is missing (a version with a
        // classifier but no plain jar, or a manually-published artifact),
        // we have to pick the best available. Filter out sources/javadoc/
        // tests jars, then prefer the shortest filename — the main jar
        // conventionally has no extra suffix.
        try (Stream<Path> s = Files.list(base)) {
            return s
                    .filter(p -> p.getFileName().toString().endsWith(".jar"))
                    .filter(p -> {
                        String fn = p.getFileName().toString();
                        return !fn.endsWith("-sources.jar")
                                && !fn.endsWith("-javadoc.jar")
                                && !fn.endsWith("-tests.jar");
                    })
                    .min((a, b) -> {
                        // Shorter filename first. If tied, fall back to
                        // lexicographic order so the result is deterministic.
                        int byLen = Integer.compare(
                                a.getFileName().toString().length(),
                                b.getFileName().toString().length());
                        return byLen != 0 ? byLen
                                : a.getFileName().toString().compareTo(b.getFileName().toString());
                    })
                    .orElse(null);
        } catch (IOException e) {
            return null;
        }
    }

    private Path pomFor(Dependency dep) {
        String groupPath = dep.getGroupId().replace('.', '/');
        Path pom = localRepo.resolve(groupPath).resolve(dep.getArtifactId())
                .resolve(dep.getVersion())
                .resolve(dep.getArtifactId() + "-" + dep.getVersion() + ".pom");
        return Files.isRegularFile(pom) ? pom : null;
    }

    private static Path defaultLocalRepo() {
        String home = System.getProperty("user.home");
        return Path.of(home, ".m2", "repository");
    }
}
