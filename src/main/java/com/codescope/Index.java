package com.codescope;

import org.eclipse.jdt.core.dom.*;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Full-project index for method lookup across multiple modules.
 * Builds method index and supports cross-module queries.
 */
public class Index {

    private final List<ProjectModel> models = new ArrayList<>();
    private final Map<String, List<MethodInfo>> methods = new ConcurrentHashMap<>();
    private final Map<String, Set<String>> callersCache = new ConcurrentHashMap<>();
    private final Map<String, Set<Path>> methodToFiles = new ConcurrentHashMap<>();
    private final ExecutorService executor;

    public static void main(String[] args) throws IOException {
        if (args.length < 1) {
            System.err.println("Usage: Index <source-dir1> [source-dir2] ... [method-name]");
            System.exit(1);
        }

        long start = System.currentTimeMillis();

        List<Path> dirs = new ArrayList<>();
        String query = null;

        for (int i = 0; i < args.length; i++) {
            if (i == args.length - 1 && !args[i].startsWith("/")) {
                query = args[i];
                break;
            }
            Path p = Paths.get(args[i]).toAbsolutePath();
            if (Files.exists(p) && Files.isDirectory(p)) {
                dirs.add(p);
            }
        }

        if (dirs.isEmpty()) {
            System.err.println("Error: No valid directories found");
            System.exit(1);
        }

        String[] cp = parseMavenClasspath(dirs.get(0));
        Index index = new Index(dirs, cp);
        index.build();

        if (query != null) {
            System.out.println(index.findMethod(query));
        } else {
            System.out.println(index.summary());
        }
        System.err.println("Indexed in " + (System.currentTimeMillis() - start) + "ms");
    }

    public Index(List<Path> dirs) throws IOException {
        this.executor = Executors.newVirtualThreadPerTaskExecutor();
        for (Path dir : dirs) {
            if (Files.isRegularFile(dir)) {
                dir = dir.getParent();
            }
            Set<Path> javaRoots = findJavaRoots(dir);
            if (javaRoots.isEmpty() && dir.toString().endsWith("java")) {
                javaRoots.add(dir);
            }
            for (Path javaRoot : javaRoots) {
                ProjectModel model = new ProjectModel(javaRoot);
                model.init();
                models.add(model);
            }
        }
        build();
    }

    private Set<Path> findJavaRoots(Path root) throws IOException {
        Set<Path> javaRoots = new HashSet<>();
        Files.walk(root)
            .filter(Files::isDirectory)
            .filter(p -> p.endsWith("java"))
            .filter(p -> !p.toString().contains("/target/"))
            .forEach(javaRoots::add);
        return javaRoots;
    }

    public void build() throws IOException {
        try {
            List<Callable<Void>> tasks = new ArrayList<>();
            for (ProjectModel model : models) {
                tasks.add(() -> { model.refresh(); return null; });
            }
            executor.invokeAll(tasks);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Index interrupted", e);
        }

        for (ProjectModel model : models) {
            for (Path file : model.getFiles()) {
                CompilationUnit cu = model.getAst(file);
                if (cu == null) continue;

                for (Object obj : cu.types()) {
                    if (!(obj instanceof TypeDeclaration type)) continue;
                    String typeName = type.getName().getIdentifier();

                    for (Object member : type.bodyDeclarations()) {
                        if (!(member instanceof MethodDeclaration method)) continue;
                        String methodName = method.getName().getIdentifier();
                        methods.computeIfAbsent(methodName, k -> Collections.synchronizedList(new ArrayList<>()))
                              .add(new MethodInfo(file, typeName, method));
                        methodToFiles.computeIfAbsent(methodName, k -> ConcurrentHashMap.newKeySet()).add(file);

                        if (method.getBody() != null) {
                            for (Object stmtObj : method.getBody().statements()) {
                                ASTNode stmt = (ASTNode) stmtObj;
                                for (MethodInvocation call : findMethodCalls(stmt)) {
                                    String calleeName = call.getName().getIdentifier();
                                    callersCache.computeIfAbsent(calleeName, k -> ConcurrentHashMap.newKeySet())
                                               .add(typeName + "." + method.getName().getIdentifier());
                                }
                            }
                        }
                    }
                }
                for (Object obj : cu.types()) {
                    if (!(obj instanceof RecordDeclaration record)) continue;
                    String typeName = record.getName().getIdentifier();

                    for (Object member : record.bodyDeclarations()) {
                        if (!(member instanceof MethodDeclaration method)) continue;
                        String methodName = method.getName().getIdentifier();
                        methods.computeIfAbsent(methodName, k -> Collections.synchronizedList(new ArrayList<>()))
                              .add(new MethodInfo(file, typeName, method));
                        methodToFiles.computeIfAbsent(methodName, k -> ConcurrentHashMap.newKeySet()).add(file);

                        if (method.getBody() != null) {
                            for (Object stmtObj : method.getBody().statements()) {
                                ASTNode stmt = (ASTNode) stmtObj;
                                for (MethodInvocation call : findMethodCalls(stmt)) {
                                    String calleeName = call.getName().getIdentifier();
                                    callersCache.computeIfAbsent(calleeName, k -> ConcurrentHashMap.newKeySet())
                                               .add(typeName + "." + method.getName().getIdentifier());
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    public void shutdown() {
        executor.shutdown();
    }

    private String[] classpath;

    public Index(List<Path> dirs, String[] classpath) throws IOException {
        this.executor = Executors.newVirtualThreadPerTaskExecutor();
        this.classpath = classpath;
        for (Path dir : dirs) {
            if (Files.isRegularFile(dir)) {
                dir = dir.getParent();
            }
            Set<Path> javaRoots = findJavaRoots(dir);
            if (javaRoots.isEmpty() && dir.toString().endsWith("java")) {
                javaRoots.add(dir);
            }
            for (Path javaRoot : javaRoots) {
                ProjectModel model = new ProjectModel(javaRoot, classpath);
                model.init();
                models.add(model);
            }
        }
        build();
    }

    public String findMethod(String name) {
        StringBuilder sb = new StringBuilder();
        sb.append("# Method: ").append(name).append("\n\n");

        List<MethodInfo> found = methods.getOrDefault(name, Collections.emptyList());
        sb.append("## Definitions (").append(found.size()).append(")\n");
        for (var m : found) {
            sb.append("- ").append(m.type).append(" at ").append(m.file.getFileName()).append("\n");
        }

        if (classpath != null && classpath.length > 0) {
            sb.append("\n## Maven Dependencies\n");
            for (String cp : classpath) {
                sb.append("- ").append(cp).append("\n");
            }
        }

        sb.append("\n## Callers\n");
        Set<String> callers = callersCache.get(name);
        if (callers != null) {
            for (String caller : callers) {
                sb.append("- ").append(caller).append("\n");
            }
        }

        sb.append("\n## Implementations\n");
        for (ProjectModel model : models) {
            for (Path file : model.getFiles()) {
                CompilationUnit cu = model.getAst(file);
                if (cu == null) continue;

                for (Object obj : cu.types()) {
                    if (!(obj instanceof TypeDeclaration type)) continue;
                    for (Object member : type.bodyDeclarations()) {
                        if (!(member instanceof MethodDeclaration method)) continue;
                        if (method.getName().getIdentifier().equals(name) && method.getBody() != null) {
                            sb.append("- ").append(type.getName().getIdentifier())
                              .append(" at ").append(file.getFileName()).append("\n");
                        }
                    }
                }
            }
        }
        return sb.toString();
    }

    private List<MethodInvocation> findMethodCalls(ASTNode node) {
        List<MethodInvocation> result = new ArrayList<>();
        node.accept(new ASTVisitor() {
            @Override
            public boolean visit(MethodInvocation n) {
                result.add(n);
                return super.visit(n);
            }
        });
        return result;
    }

    public String summary() {
        StringBuilder sb = new StringBuilder();
        sb.append("# Index Summary\n\n");

        int totalFiles = 0;
        for (ProjectModel model : models) {
            totalFiles += model.getFiles().size();
        }

        sb.append("Modules: ").append(models.size()).append("\n");
        sb.append("Files: ").append(totalFiles).append("\n");
        sb.append("Methods: ").append(methods.size()).append("\n\n");

        sb.append("## Modules\n");
        for (int i = 0; i < models.size(); i++) {
            sb.append("- Module ").append(i + 1).append(": ").append(models.get(i).getFiles().size()).append(" files\n");
        }

        sb.append("\n## Types\n");
        Set<String> typeNames = new TreeSet<>();
        for (ProjectModel model : models) {
            for (CompilationUnit cu : model.getAsts()) {
                for (Object obj : cu.types()) {
                    if (obj instanceof TypeDeclaration type) {
                        typeNames.add(type.getName().getIdentifier());
                    }
                    if (obj instanceof RecordDeclaration record) {
                        typeNames.add(record.getName().getIdentifier());
                    }
                }
            }
        }
        for (var t : typeNames) {
            sb.append("- ").append(t).append("\n");
        }
        return sb.toString();
    }

    public record MethodInfo(Path file, String type, MethodDeclaration method) {}

    public List<Path> findClass(String simpleName) {
        List<Path> result = new ArrayList<>();
        for (ProjectModel model : models) {
            for (Path file : model.getFiles()) {
                CompilationUnit cu = model.getAst(file);
                if (cu == null) continue;
                for (Object obj : cu.types()) {
                    if (obj instanceof TypeDeclaration type && type.getName().getIdentifier().equals(simpleName)) {
                        result.add(file);
                    }
                    if (obj instanceof RecordDeclaration record && record.getName().getIdentifier().equals(simpleName)) {
                        result.add(file);
                    }
                }
            }
        }

        if (result.isEmpty()) {
            for (ProjectModel model : models) {
                for (Path file : model.getFiles()) {
                    try {
                        String source = Files.readString(file);
                        int recordIdx = source.indexOf("record " + simpleName);
                        if (recordIdx >= 0) {
                            int startIdx = Math.max(0, recordIdx - 50);
                            int endIdx = Math.min(source.length(), recordIdx + simpleName.length() + 20);
                            String context = source.substring(startIdx, endIdx);
                            if (!context.contains("class " + simpleName) && !context.contains("interface " + simpleName)) {
                                result.add(file);
                            }
                        }
                    } catch (IOException e) {
                    }
                }
            }
        }

        return result;
    }

    private static String[] parseMavenClasspath(Path dir) {
        Path pom = dir.resolve("pom.xml");
        if (!Files.exists(pom)) {
            return null;
        }
        List<String> deps = new DefaultMavenParser().parseDependencies(pom);
        return deps.isEmpty() ? null : deps.toArray(new String[0]);
    }
}