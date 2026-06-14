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
    /**
     * Cap on the number of pom.xml files returned. The variable used to be
     * named MAX_DIRECTORIES_VISITED and was applied via {@code .limit()} to
     * the filtered stream of *files* (pom.xml), not directories — the
     * directory walk itself is bounded by {@link #POM_WALK_DEPTH}. Renamed
     * to reflect what it actually caps; the old name was misleading.
     */
    private static final int MAX_POMS = 5_000;

    private final Path localRepo;

    public MavenClasspathResolver() {
        this(MavenClasspathResolver.firstNonNull(
                MavenSettings.readLocalRepository(), defaultLocalRepo()));
    }

    private static <T> T firstNonNull(T a, T b) {
        return a != null ? a : b;
    }

    public MavenClasspathResolver(Path localRepo) {
        this.localRepo = localRepo;
    }

    /**
     * One failed pom resolution, recorded so callers can surface it. We
     * don't want to abort resolution because of a single broken pom, but
     * silent skips were hiding real problems (malformed pom, missing
     * local-repo jar, unparseable dep) — users couldn't tell whether the
     * missing jar in their classpath was a codescope bug or their pom.
     *
     * @param pom the pom that failed
     * @param phase {@code "read"} for parse failures, {@code "walk"} for
     *              failures during dep walking
     * @param cause the exception (message is what matters; we keep the
     *              throwable for callers that want to log with stack)
     */
    public record PomError(Path pom, String phase, Throwable cause) {
        public String message() {
            return pom + " [" + phase + "]: " + cause.getClass().getSimpleName()
                    + (cause.getMessage() != null ? ": " + cause.getMessage() : "");
        }
    }

    /** Result of a classpath resolution, including any poms that failed to walk. */
    public record ResolveResult(List<String> jars, List<PomError> errors) {}

    /**
     * @param projectRoot root of the Maven project (may contain a multi-module tree)
     * @return classpath of jar + class dir paths aggregated from every pom in the tree,
     *         plus a list of per-pom errors that were skipped rather than aborting.
     */
    public ResolveResult resolveDetailed(Path projectRoot) throws IOException {
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
        List<PomError> errors = java.util.Collections.synchronizedList(new ArrayList<>());
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> futures = new ArrayList<>(poms.size());
            for (Path pom : poms) {
                futures.add(pool.submit(() -> {
                    try {
                        walk(pom, jars, seenPoms, 0, errors);
                    } catch (IOException e) {
                        errors.add(new PomError(pom, "walk", e));
                    }
                }));
            }
            for (Future<?> f : futures) {
                try {
                    f.get();
                } catch (ExecutionException e) {
                    // walk() already records its own errors before re-throwing;
                    // this branch fires only for executor-level failures.
                    errors.add(new PomError(null, "executor",
                            e.getCause() != null ? e.getCause() : e));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }

        return new ResolveResult(new ArrayList<>(jars), new ArrayList<>(errors));
    }

    /**
     * Back-compat wrapper: returns just the jar list. Use {@link #resolveDetailed}
     * if you need to know which poms failed.
     */
    public List<String> resolve(Path projectRoot) throws IOException {
        return resolveDetailed(projectRoot).jars();
    }

    /**
     * Pretty-print the resolution errors for inclusion in a log line or
     * a tool error response. Returns {@code null} if there were no errors.
     */
    public static String formatErrors(List<PomError> errors) {
        if (errors == null || errors.isEmpty()) return null;
        StringBuilder sb = new StringBuilder();
        sb.append(errors.size()).append(" pom(s) failed to resolve:");
        for (PomError e : errors) {
            sb.append("\n  - ").append(e.message());
        }
        return sb.toString();
    }

    /** Every pom.xml under {@code projectRoot}, excluding target/ build outputs. */
    static List<Path> findPoms(Path projectRoot) throws IOException {
        List<Path> out = new ArrayList<>();
        if (!Files.isDirectory(projectRoot)) return out;
        try (Stream<Path> s = Files.walk(projectRoot, POM_WALK_DEPTH)) {
            s.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().equals("pom.xml"))
                    .filter(p -> !isUnderBuildDir(p, projectRoot))
                    .limit(MAX_POMS)
                    .forEach(out::add);
        }
        return out;
    }

    private static boolean isUnderBuildDir(Path p, Path root) {
        // Path#relativize throws IllegalArgumentException when `p` is not
        // under `root` (different roots on Windows, or a malformed entry
        // slipped through). findPoms walks from `root`, so this should be
        // unreachable in practice — but a defensive guard here turns a
        // potential walk-aborting IAE into a silent skip, which is what
        // every other branch of findPoms already does on failure.
        if (root == null) return false;
        Path rel;
        try {
            rel = root.relativize(p);
        } catch (IllegalArgumentException e) {
            return false;
        }
        for (Path part : rel) {
            String name = part.toString();
            if (name.equals("target") || name.equals("build") || name.equals("node_modules")) {
                return true;
            }
        }
        return false;
    }

    private void walk(Path pom, Set<String> out, Set<String> seenPoms,
                     int depth, List<PomError> errors) throws IOException {
        if (depth > MAX_POM_DEPTH) return;
        String key = pom.toAbsolutePath().toString();
        if (!seenPoms.add(key)) return;

        Model model;
        try (InputStream in = new FileInputStream(pom.toFile())) {
            model = new MavenXpp3Reader().read(in);
        } catch (Exception e) {
            // Malformed pom — record and skip rather than aborting the
            // whole resolution. The user can see this in the tool error
            // response (McpServerTest / CliTest print it).
            errors.add(new PomError(pom, "read", e));
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
                    if (depPom != null) walk(depPom, out, seenPoms, depth + 1, errors);
                }
            }
        }
    }

    private Path findJar(Dependency dep) {
        // Maven schema requires all three, but malformed poms in the wild
        // omit fields or leave them unresolved as ${property} references
        // (which MavenXpp3Reader returns as the literal string, not null —
        // those misses fall through silently in findJar). A NULL on any
        // required field used to NPE in the .replace call below; the NPE
        // escaped walk() and aborted the per-pom Future, silently dropping
        // every dep after this one in the same pom.
        String groupId = dep.getGroupId();
        String artifactId = dep.getArtifactId();
        String version = dep.getVersion();
        if (groupId == null || artifactId == null || version == null) return null;

        String groupPath = groupId.replace('.', '/');
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
        // Symmetric null-safety to findJar(): a malformed dep with a null
        // groupId/artifactId/version used to NPE here too, escaping walk()
        // and aborting the per-pom Future (which silently dropped every
        // transitive dep in that pom).
        String groupId = dep.getGroupId();
        String artifactId = dep.getArtifactId();
        String version = dep.getVersion();
        if (groupId == null || artifactId == null || version == null) return null;
        String groupPath = groupId.replace('.', '/');
        Path pom = localRepo.resolve(groupPath).resolve(artifactId)
                .resolve(version)
                .resolve(artifactId + "-" + version + ".pom");
        return Files.isRegularFile(pom) ? pom : null;
    }

    private static Path defaultLocalRepo() {
        String home = System.getProperty("user.home");
        return Path.of(home, ".m2", "repository");
    }
}
