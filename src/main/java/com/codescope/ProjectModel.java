package com.codescope;

import org.eclipse.jdt.core.dom.*;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages AST cache and Java source file parsing for a single module.
 * Uses Virtual Threads for parallel parsing.
 */
public class ProjectModel {

    private final Map<Path, CompilationUnit> astCache = new ConcurrentHashMap<>();
    private final Map<Path, Long> lastModified = new ConcurrentHashMap<>();
    private final Path rootDir;
    private final ExecutorService executor;
    private CacheManager cacheManager;
    private String[] classpath;

    public ProjectModel(Path rootDir) {
        this(rootDir, null);
    }

    public ProjectModel(Path rootDir, String[] classpath) {
        this.rootDir = rootDir;
        this.classpath = classpath;
        this.executor = Executors.newVirtualThreadPerTaskExecutor();
        
        if (rootDir != null) {
            this.cacheManager = new CacheManager(rootDir);
        }
    }

    public String[] getClasspath() {
        return classpath;
    }

    public void init() throws IOException {
        if (cacheManager != null && astCache.isEmpty()) {
            loadFromCache();
        }
    }

    private void loadFromCache() throws IOException {
        Map<Path, Long> cachedTimestamps = cacheManager.load();
        if (cachedTimestamps.isEmpty()) return;
        
        boolean valid = cacheManager.isValid(cachedTimestamps);
        if (valid) {
            Set<Path> currentFiles = new HashSet<>();
            Files.walk(rootDir)
                .filter(p -> p.toString().endsWith(".java"))
                .filter(p -> !p.toString().contains("/target/"))
                .forEach(currentFiles::add);

            for (Map.Entry<Path, Long> entry : cachedTimestamps.entrySet()) {
                Path file = entry.getKey();
                if (currentFiles.contains(file)) {
                    String source = Files.readString(file);
                    CompilationUnit cu = parse(source);
                    astCache.put(file, cu);
                    lastModified.put(file, entry.getValue());
                }
            }
        }
    }

    public void saveToCache() throws IOException {
        if (cacheManager != null) {
            cacheManager.save(lastModified);
        }
    }

    public void addFile(Path file) throws IOException {
        addFile(file, true);
    }

    public void addFile(Path file, boolean checkChanged) throws IOException {
        if (!Files.exists(file)) return;

        long modified = Files.getLastModifiedTime(file).toMillis();

        if (checkChanged && isCached(file) && !hasChanged(file)) {
            return;
        }

        String source = Files.readString(file);
        CompilationUnit cu = parse(source);
        astCache.put(file, cu);
        lastModified.put(file, modified);
    }

    public CompilationUnit getAstLazy(Path file) {
        if (!astCache.containsKey(file)) {
            try {
                addFile(file, false);
            } catch (IOException e) {
                return null;
            }
        }
        return astCache.get(file);
    }

    public void addFilesParallel(List<Path> files) throws InterruptedException {
        List<Callable<Void>> tasks = new ArrayList<>();
        for (Path file : files) {
            tasks.add(() -> { addFile(file, true); return null; });
        }
        executor.invokeAll(tasks);
    }

    public void shutdown() {
        executor.shutdown();
    }

    public CompilationUnit getAst(Path file) {
        return astCache.get(file);
    }

    public boolean isCached(Path file) {
        return astCache.containsKey(file);
    }

    public boolean hasChanged(Path file) {
        if (!Files.exists(file)) return true;
        long currentModified = 0;
        try {
            currentModified = Files.getLastModifiedTime(file).toMillis();
        } catch (IOException e) {
            return true;
        }
        Long cached = lastModified.get(file);
        return cached == null || currentModified > cached;
    }

    public void removeFile(Path file) {
        astCache.remove(file);
        lastModified.remove(file);
    }

    public Set<Path> getFiles() {
        return astCache.keySet();
    }

    public Collection<CompilationUnit> getAsts() {
        return astCache.values();
    }

    public void refresh() throws IOException {
        if (rootDir == null) return;

        Set<Path> currentFiles = new HashSet<>();
        Files.walk(rootDir)
            .filter(p -> p.toString().endsWith(".java"))
            .filter(p -> !p.toString().contains("/target/"))
            .forEach(currentFiles::add);

        Set<Path> cachedFiles = astCache.keySet();

        for (Path p : cachedFiles) {
            if (!currentFiles.contains(p)) {
                removeFile(p);
            }
        }

        for (Path p : currentFiles) {
            addFile(p, true);
        }
        
        if (!lastModified.isEmpty()) {
            saveToCache();
        }
    }

    public void refreshParallel() throws InterruptedException, IOException {
        if (rootDir == null) return;

        Set<Path> currentFiles = new HashSet<>();
        Files.walk(rootDir)
            .filter(p -> p.toString().endsWith(".java"))
            .filter(p -> !p.toString().contains("/target/"))
            .forEach(currentFiles::add);

        Set<Path> cachedFiles = astCache.keySet();

        for (Path p : cachedFiles) {
            if (!currentFiles.contains(p)) {
                removeFile(p);
            }
        }

        addFilesParallel(new ArrayList<>(currentFiles));
    }

    private CompilationUnit parse(String source) {
        ASTParser parser = ASTParser.newParser(ASTParser.K_COMPILATION_UNIT);
        parser.setSource(source.toCharArray());
        parser.setResolveBindings(true);
        parser.setBindingsRecovery(true);
        parser.setStatementsRecovery(true);
        return (CompilationUnit) parser.createAST(null);
    }
}