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
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/** Builds a classpath from a Maven project's pom.xml. */
public final class MavenClasspathResolver {

    private final Path localRepo;

    public MavenClasspathResolver() {
        this(defaultLocalRepo());
    }

    public MavenClasspathResolver(Path localRepo) {
        this.localRepo = localRepo;
    }

    /** Returns a classpath (jar + class dir paths) for the given Maven project root. */
    public List<String> resolve(Path projectRoot) throws IOException {
        Path pom = projectRoot.resolve("pom.xml");
        if (!Files.isRegularFile(pom)) {
            throw new IOException("No pom.xml at " + projectRoot
                    + " (only Maven pom.xml resolution is supported)");
        }

        Set<String> jars = new HashSet<>();
        walk(pom, jars, new HashSet<>(), 0);

        List<String> cp = new ArrayList<>();
        for (String j : jars) cp.add(j);
        return cp;
    }

    private void walk(Path pom, Set<String> out, Set<String> seenPoms, int depth) throws IOException {
        if (depth > 10) return;                 // safety: cap transitive depth
        String key = pom.toAbsolutePath().toString();
        if (!seenPoms.add(key)) return;

        Model model;
        try (InputStream in = new FileInputStream(pom.toFile())) {
            model = new MavenXpp3Reader().read(in);
        } catch (Exception e) {
            throw new IOException("Failed to parse " + pom + ": " + e.getMessage(), e);
        }

        if (model.getDependencies() != null) {
            for (Dependency dep : model.getDependencies()) {
                if (!"jar".equalsIgnoreCase(dep.getType() != null ? dep.getType() : "jar")) continue;
                if (dep.getScope() != null && ("test".equalsIgnoreCase(dep.getScope())
                        || "provided".equalsIgnoreCase(dep.getScope()))) {
                    continue;
                }
                Path jar = findJar(dep);
                if (jar != null && out.add(jar.toString())) {
                    // recurse into transitive deps
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

        // prefer the exact artifact jar (e.g. foo-1.2.3.jar); fall back to any .jar
        String exact = artifactId + "-" + version + ".jar";
        Path exactPath = base.resolve(exact);
        if (Files.isRegularFile(exactPath)) return exactPath;

        try (Stream<Path> s = Files.list(base)) {
            return s.filter(p -> p.getFileName().toString().endsWith(".jar")).findFirst().orElse(null);
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
