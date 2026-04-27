package com.codescope;

import org.eclipse.jdt.core.dom.*;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Manages AST cache and Java source file parsing for a single module.
 * Uses Virtual Threads for parallel parsing.
 */
public class ProjectModel {

    private static final int MAX_CACHE_SIZE = 10000;
    private static final int MAX_CONCURRENT_TASKS = 100;
    private final Map<Path, CompilationUnit> astCache;
    private final Map<Path, Long> lastModified = new ConcurrentHashMap<>();
    private final Path rootDir;
    private final ExecutorService executor;
    private final Semaphore semaphore;
    private CacheManager cacheManager;
    private String[] classpath;
    private Set<Path> knownFiles = ConcurrentHashMap.newKeySet();
    private long lastScanTime = 0;
    private static final long MIN_SCAN_INTERVAL_MS = 1000;

    public ProjectModel(Path rootDir) {
        this(rootDir, null);
    }

    public ProjectModel(Path rootDir, String[] classpath) {
        this.rootDir = rootDir;
        this.classpath = classpath;
        this.astCache = Collections.synchronizedMap(new LinkedHashMap<>(256, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<Path, CompilationUnit> eldest) {
                return size() > MAX_CACHE_SIZE;
            }
        });
        this.semaphore = new Semaphore(MAX_CONCURRENT_TASKS);
        this.executor = Executors.newVirtualThreadPerTaskExecutor();

        if (rootDir != null) {
            this.cacheManager = new CacheManager(rootDir);
        }
    }

    public String[] getClasspath() {
        return classpath;
    }

    public Path getRoot() {
        return rootDir;
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
                    CompilationUnit cu = parse(source, file);
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
        CompilationUnit cu = parse(source, file);
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
            tasks.add(() -> {
                semaphore.acquire();
                try {
                    addFile(file, true);
                } finally {
                    semaphore.release();
                }
                return null;
            });
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

        long now = System.currentTimeMillis();
        if (now - lastScanTime < MIN_SCAN_INTERVAL_MS && !knownFiles.isEmpty()) {
            Set<Path> currentCached = astCache.keySet();
            for (Path p : currentCached) {
                if (hasChanged(p)) {
                    addFile(p, true);
                }
            }
            return;
        }

        Set<Path> currentFiles = ConcurrentHashMap.newKeySet();
        Files.walk(rootDir)
            .filter(p -> p.toString().endsWith(".java"))
            .filter(p -> !p.toString().contains("/target/"))
            .forEach(currentFiles::add);

        lastScanTime = now;
        knownFiles.clear();
        knownFiles.addAll(currentFiles);

        for (Path p : astCache.keySet()) {
            if (!currentFiles.contains(p)) {
                removeFile(p);
            }
        }

        for (Path p : currentFiles) {
            if (!astCache.containsKey(p) || hasChanged(p)) {
                addFile(p, true);
            }
        }

        if (!lastModified.isEmpty()) {
            saveToCache();
        }
    }

    public void refreshParallel() throws InterruptedException, IOException {
        if (rootDir == null) return;

        long now = System.currentTimeMillis();
        if (now - lastScanTime < MIN_SCAN_INTERVAL_MS && !knownFiles.isEmpty()) {
            Set<Path> currentCached = astCache.keySet();
            List<Path> changed = new ArrayList<>();
            for (Path p : currentCached) {
                if (hasChanged(p)) {
                    changed.add(p);
                }
            }
            if (!changed.isEmpty()) {
                addFilesParallel(changed);
            }
            return;
        }

        Set<Path> currentFiles = ConcurrentHashMap.newKeySet();
        Files.walk(rootDir)
            .filter(p -> p.toString().endsWith(".java"))
            .filter(p -> !p.toString().contains("/target/"))
            .forEach(currentFiles::add);

        lastScanTime = now;
        knownFiles.clear();
        knownFiles.addAll(currentFiles);

        for (Path p : astCache.keySet()) {
            if (!currentFiles.contains(p)) {
                removeFile(p);
            }
        }

        List<Path> toProcess = new ArrayList<>();
        for (Path p : currentFiles) {
            if (!astCache.containsKey(p) || hasChanged(p)) {
                toProcess.add(p);
            }
        }
        addFilesParallel(toProcess);
    }

    private CompilationUnit parse(String source, Path file) {
        boolean hasRecordInSource = source.contains("record ");
        String workingSource = source;

        if (hasRecordInSource && source.contains("package ") && source.contains(";")) {
            int packageStart = source.indexOf("package ");
            int semiColon = source.indexOf(";", packageStart);
            if (semiColon > packageStart) {
                workingSource = source.substring(0, packageStart) + source.substring(semiColon + 1);
            }
        }

        ASTParser parser = ASTParser.newParser(AST.JLS21);
        parser.setSource(workingSource.toCharArray());

        if (classpath != null && classpath.length > 0) {
            String[] classpathEntries = classpath;
            String[] sourcePaths = new String[]{rootDir.toAbsolutePath().toString()};
            parser.setEnvironment(classpathEntries, sourcePaths, null, true);
            String unitName = file.toAbsolutePath().toString();
            parser.setUnitName(unitName);
            parser.setResolveBindings(true);
            parser.setBindingsRecovery(true);
        } else {
            parser.setResolveBindings(false);
            parser.setBindingsRecovery(false);
        }
        parser.setStatementsRecovery(true);

        CompilationUnit cu = (CompilationUnit) parser.createAST(null);

        if (hasRecordInSource && workingSource != source && cu.types().isEmpty()) {
            ASTParser recordParser = ASTParser.newParser(AST.JLS21);
            recordParser.setSource(source.toCharArray());
            if (classpath != null && classpath.length > 0) {
                String[] classpathEntries = classpath;
                String[] sourcePaths = new String[]{rootDir.toAbsolutePath().toString()};
                recordParser.setEnvironment(classpathEntries, sourcePaths, null, true);
                String unitName = file.toAbsolutePath().toString();
                recordParser.setUnitName(unitName);
                recordParser.setResolveBindings(true);
                recordParser.setBindingsRecovery(true);
            } else {
                recordParser.setResolveBindings(false);
                recordParser.setBindingsRecovery(false);
            }
            recordParser.setStatementsRecovery(true);
            CompilationUnit recordCu = (CompilationUnit) recordParser.createAST(null);

            if (!recordCu.types().isEmpty()) {
                try {
                    java.lang.reflect.Method typesMethod = CompilationUnit.class.getMethod("types");
                    Object nodeListObj = typesMethod.invoke(cu);
                    if (nodeListObj instanceof java.util.List) {
                        java.util.List<Object> nodeList = (java.util.List<Object>) nodeListObj;
                        for (Object type : recordCu.types()) {
                            if (type instanceof TypeDeclaration td) {
                                AST ast = cu.getAST();
                                TypeDeclaration newTd = ast.newTypeDeclaration();
                                newTd.setName(td.getName());
                                newTd.setModifiers(td.getModifiers());
                                for (Object bd : td.bodyDeclarations()) {
                                    newTd.bodyDeclarations().add(ASTNode.copySubtree(ast, (ASTNode) bd));
                                }
                                nodeList.add(newTd);
                            } else if (type instanceof RecordDeclaration rd) {
                                AST ast = cu.getAST();
                                RecordDeclaration newRd = ast.newRecordDeclaration();
                                newRd.setName(rd.getName());
                                newRd.setModifiers(rd.getModifiers());
                                for (Object bd : rd.bodyDeclarations()) {
                                    newRd.bodyDeclarations().add(ASTNode.copySubtree(ast, (ASTNode) bd));
                                }
                                nodeList.add(newRd);
                            }
                        }
                    }
                } catch (Exception e) {
                }
            }
        }

        return cu;
    }
}