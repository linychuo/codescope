package com.codescope;

import org.eclipse.jdt.core.dom.*;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.*;
import java.util.concurrent.ConcurrentHashMap;

public class Index {

    private final List<ProjectModel> models = new ArrayList<>();
    private final Map<String, List<MethodInfo>> methods = new ConcurrentHashMap<>();
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

        Index index = new Index(dirs);
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
                    }
                }
            }
        }
    }

    public void shutdown() {
        executor.shutdown();
    }

    public String findMethod(String name) {
        StringBuilder sb = new StringBuilder();
        sb.append("# Method: ").append(name).append("\n\n");

        List<MethodInfo> found = methods.getOrDefault(name, Collections.emptyList());
        sb.append("## Definitions (").append(found.size()).append(")\n");
        for (var m : found) {
            sb.append("- ").append(m.type).append(" at ").append(m.file.getFileName()).append("\n");
        }

        sb.append("\n## Callers\n");
        for (ProjectModel model : models) {
            for (Path file : model.getFiles()) {
                CompilationUnit cu = model.getAst(file);
                if (cu == null) continue;

                for (Object obj : cu.types()) {
                    if (!(obj instanceof TypeDeclaration type)) continue;
                    for (Object member : type.bodyDeclarations()) {
                        if (!(member instanceof MethodDeclaration method)) continue;
                        if (method.getBody() == null) continue;

                        for (Object stmtObj : method.getBody().statements()) {
                            ASTNode stmt = (ASTNode) stmtObj;
                            for (MethodInvocation call : findMethodCalls(stmt)) {
                                if (call.getName().getIdentifier().equals(name)) {
                                    sb.append("- ").append(type.getName().getIdentifier()).append(".")
                                     .append(method.getName().getIdentifier())
                                     .append(" at ").append(file.getFileName()).append("\n");
                                }
                            }
                        }
                    }
                }
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
                }
            }
        }
        for (var t : typeNames) {
            sb.append("- ").append(t).append("\n");
        }
        return sb.toString();
    }

    public record MethodInfo(Path file, String type, MethodDeclaration method) {}
}