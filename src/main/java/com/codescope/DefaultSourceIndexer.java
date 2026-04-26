package com.codescope;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/**
 * Default implementation of SourceIndexer.
 * Discovers Java source files and manages project models.
 */
public class DefaultSourceIndexer implements SourceIndexer {
    private final Path root;
    private final String[] classpath;
    private final List<ProjectModel> models;
    private final Set<Path> filesCache;

    private static final Logger logger = Logger.getLogger("SourceIndexer");

    public DefaultSourceIndexer(Path root) throws IOException {
        this(root, ClasspathResolver.resolveClasspath(root));
    }

    public DefaultSourceIndexer(Path root, String[] classpath) throws IOException {
        this.root = root;
        this.classpath = classpath;
        this.models = new ArrayList<>();
        this.filesCache = new HashSet<>();
        initialize();
    }

    private void initialize() throws IOException {
        Set<Path> javaRoots = findJavaRoots(root);

        if (javaRoots.isEmpty()) {
            // Try to find roots by walking up
            if (Files.isRegularFile(root)) {
                Path parent = root.getParent();
                if (parent != null) {
                    javaRoots = findJavaRoots(parent.getParent() != null ? parent.getParent() : parent);
                }
            }
            if (javaRoots.isEmpty()) {
                // Fallback: use root itself if it looks like a source dir
                if (root.toString().endsWith("java") || root.toString().contains("/src/")) {
                    javaRoots.add(root);
                }
            }
        }

        for (Path javaRoot : javaRoots) {
            ProjectModel model = new ProjectModel(javaRoot, classpath);
            model.init();
            // Explicitly scan and add files
            Files.walk(javaRoot)
                .filter(p -> p.toString().endsWith(".java"))
                .filter(p -> !p.toString().contains("/target/"))
                .forEach(p -> {
                    try { model.addFile(p); } catch (IOException e) {
                        logger.warning("Failed to add file: " + p, e);
                    }
                });
            models.add(model);
        }

        // Build file cache
        for (ProjectModel model : models) {
            filesCache.addAll(model.getFiles());
        }

        logger.debug("Indexed " + filesCache.size() + " files in " + models.size() + " modules");
    }

    private Set<Path> findJavaRoots(Path start) throws IOException {
        Set<Path> javaRoots = new HashSet<>();
        if (start == null || !Files.exists(start)) {
            return javaRoots;
        }

        Files.walk(start)
            .filter(Files::isDirectory)
            .filter(p -> p.endsWith("java"))
            .filter(p -> !p.toString().contains("/target/"))
            .forEach(javaRoots::add);

        return javaRoots;
    }

    @Override
    public Set<Path> getFiles() {
        return Collections.unmodifiableSet(filesCache);
    }

    @Override
    public Set<Path> getJavaRoots() {
        Set<Path> roots = new HashSet<>();
        for (ProjectModel model : models) {
            roots.add(model.getRoot());
        }
        return roots;
    }

    @Override
    public Path getRoot() {
        return root;
    }

    @Override
    public List<ProjectModel> getModels() {
        return Collections.unmodifiableList(models);
    }

    public String[] getClasspath() {
        return classpath;
    }
}
