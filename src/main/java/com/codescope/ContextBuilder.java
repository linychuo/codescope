package com.codescope;

import org.eclipse.jdt.core.dom.*;
import org.eclipse.jdt.core.Flags;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/**
 * Builds semantic context for LLM from Java source files.
 * Integrates multiple ProjectModels and provides unified API.
 */
public class ContextBuilder {

    /** List of project models (one per module) */
    private final List<ProjectModel> models = new ArrayList<>();

    /**
     * Creates a context builder for the given directory.
     * Auto-discovers Java roots and initializes all modules.
     * Resolves Maven dependencies from pom.xml if present.
     */
    public ContextBuilder(Path dir) throws IOException {
        this(dir, ClasspathResolver.resolveClasspath(dir));
    }

    /**
     * Creates a context builder for the given directory with explicit classpath.
     * @param dir directory to scan for Java files
     * @param classpath resolved classpath entries (JAR file paths), may be null
     */
    public ContextBuilder(Path dir, String[] classpath) throws IOException {
        if (dir == null) return;

        if (Files.isRegularFile(dir)) {
            dir = dir.getParent();
        }
        Set<Path> javaRoots = findJavaRoots(dir);
        if (javaRoots.isEmpty()) {
            javaRoots.add(dir);
        }
        for (Path javaRoot : javaRoots) {
            ProjectModel model = new ProjectModel(javaRoot, classpath);
            model.init();
            Files.walk(javaRoot)
                .filter(p -> p.toString().endsWith(".java"))
                .filter(p -> !p.toString().contains("/target/"))
                .forEach(p -> {
                    try { model.addFile(p); } catch (IOException e) {}
                });
            models.add(model);
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

    /**
     * Returns all files across all models.
     */
    public Set<Path> getFiles() {
        Set<Path> files = new HashSet<>();
        for (ProjectModel model : models) {
            files.addAll(model.getFiles());
        }
        return files;
    }

    /**
     * Returns all project models.
     */
    public List<ProjectModel> getModels() {
        return models;
    }

    /**
     * Generates call graph DOT output (default options).
     */
    public String buildDot() {
        return buildDot(false, false, false);
    }

    /**
     * Generates call graph DOT with options.
     * @param noJdk filter out JDK calls
     * @param cycles detect call cycles
     * @param heatmap show call frequency
     */
    public String buildDot(boolean noJdk, boolean cycles, boolean heatmap) {
        return DotGenerator.generate(models, noJdk, cycles, heatmap);
    }

    /**
     * Builds semantic context for a file.
     * @param file source file to analyze
     * @param query method name or line number (optional)
     */
    public String build(Path file, String query) {
        if (models.isEmpty()) return "No files found";
        
        ProjectModel targetModel = null;
        for (ProjectModel m : models) {
            if (m.getFiles().contains(file)) {
                targetModel = m;
                break;
            }
        }
        if (targetModel == null) {
            targetModel = models.get(0);
        }

        CompilationUnit cu = targetModel.getAst(file);
        if (cu == null) return "File not found: " + file;

        CallGraph cg = new CallGraph(file, targetModel);
        return buildContext(cu, query, file, cg);
    }

    private String buildContext(CompilationUnit cu, String query, Path file, CallGraph cg) {
        StringBuilder sb = new StringBuilder();
        sb.append("# Semantic Context for: ").append(query != null ? query : "full file").append("\n\n");

        String className = "Unknown";
        for (Object obj : cu.types()) {
            if (obj instanceof TypeDeclaration type) {
                className = type.getName().getIdentifier();
                
                // Package
                sb.append("## Class\n").append(className).append("\n");
                PackageDeclaration pkg = cu.getPackage();
                if (pkg != null) {
                    sb.append("**Package:** ").append(pkg.getName()).append("\n\n");
                }

                // Annotations
                sb.append("### Annotations\n");
                for (Object modifier : type.modifiers()) {
                    if (modifier instanceof org.eclipse.jdt.core.dom.Annotation) {
                        sb.append("- @").append(modifier.toString()).append("\n");
                    }
                }

                // Constructors
                sb.append("### Constructors\n");
                for (Object member : type.bodyDeclarations()) {
                    if (member instanceof MethodDeclaration m && m.isConstructor()) {
                        sb.append("- ").append(Flags.toString(m.getModifiers())).append(" ");
                        sb.append(className).append("(");
                        List<?> params = m.parameters();
                        for (int i = 0; i < params.size(); i++) {
                            if (i > 0) sb.append(", ");
                            sb.append(params.get(i));
                        }
                        sb.append(")\n");
                    }
                }

                // Imports
                sb.append("### Imports\n");
                for (Object imp : cu.imports()) {
                    if (imp instanceof ImportDeclaration id) {
                        sb.append("- ").append(id.getName().toString()).append("\n");
                    }
                }

                // Fields
                sb.append("### Fields\n");
                for (Object member : type.bodyDeclarations()) {
                    if (member instanceof FieldDeclaration f) {
                        sb.append("- ").append(Flags.toString(f.getModifiers())).append(" ");
                        sb.append(f.getType()).append(" ");
                        for (Object frag : f.fragments()) {
                            if (frag instanceof VariableDeclarationFragment v) {
                                sb.append(v.getName());
                            }
                        }
                        sb.append("\n");
                    }
                }

                ITypeBinding binding = type.resolveBinding();
                if (binding != null) {
                    ITypeBinding superclass = binding.getSuperclass();
                    if (superclass != null && !superclass.getName().equals("java.lang.Object")) {
                        sb.append("### Extends\n").append("- ").append(superclass.getName()).append("\n");
                    }
                    ITypeBinding[] interfaces = binding.getInterfaces();
                    if (interfaces.length > 0) {
                        sb.append("### Implements\n");
                        for (ITypeBinding iface : interfaces) {
                            sb.append("- ").append(iface.getName()).append("\n");
                        }
                    }
                }
                break;
            }
        }

        if (query != null) {
            MethodDeclaration method = findMethod(cu, query);
            if (method != null) {
                sb.append("## Method\n");
                sb.append("```java\n");
                sb.append(method.getReturnType2()).append(" ");
                sb.append(method.getName().getIdentifier());
                sb.append("(");
                List<?> params = method.parameters();
                for (int i = 0; i < params.size(); i++) {
                    if (i > 0) sb.append(", ");
                    sb.append(params.get(i));
                }
                sb.append(")\n");
                sb.append("```\n");

                sb.append("### Calls\n");
                for (var call : cg.getCallees(file, method.getName().getIdentifier())) {
                    sb.append("- ").append(call).append("\n");
                }

                sb.append("### Definition\n");
                sb.append("```java\n");
                sb.append(method.toString()).append("\n");
                sb.append("```\n");
            }
        } else {
            sb.append("## Methods\n");
            for (Object obj : cu.types()) {
                if (obj instanceof TypeDeclaration type) {
                    for (Object member : type.bodyDeclarations()) {
                        if (member instanceof MethodDeclaration m) {
                            sb.append("- ").append(Flags.toString(m.getModifiers())).append(" ");
                            sb.append(m.getReturnType2()).append(" ");
                            sb.append(m.getName().getIdentifier()).append("(");
                            List<?> params = m.parameters();
                            for (int i = 0; i < params.size(); i++) {
                                if (i > 0) sb.append(", ");
                                sb.append(params.get(i));
                            }
                            sb.append(")\n");
                        }
                    }
                }
            }
        }

        return sb.toString();
    }

    private MethodDeclaration findMethod(CompilationUnit cu, String query) {
        try {
            int line = Integer.parseInt(query);
            for (Object obj : cu.types()) {
                if (obj instanceof TypeDeclaration type) {
                    for (Object member : type.bodyDeclarations()) {
                        if (member instanceof MethodDeclaration m) {
                            if (m.getStartPosition() >= 0 && cu.getStartPosition() + line == m.getStartPosition()) {
                                return m;
                            }
                        }
                    }
                }
            }
        } catch (NumberFormatException e) {
            for (Object obj : cu.types()) {
                if (obj instanceof TypeDeclaration type) {
                    for (Object member : type.bodyDeclarations()) {
                        if (member instanceof MethodDeclaration m) {
                            if (m.getName().getIdentifier().equals(query)) {
                                return m;
                            }
                        }
                    }
                }
            }
        }
        return null;
    }

    public static class CallGraph {
        private final Path file;
        final ProjectModel model;
        private final Map<String, Set<CallSite>> callSites = new HashMap<>();
        private final Map<String, Set<CallSite>> callers = new HashMap<>();

        public CallGraph(Path file, ProjectModel model) {
            this.file = file;
            this.model = model;
            analyze();
        }

        public Set<CallSite> getCallers(Path f, String methodName) {
            Set<CallSite> result = new TreeSet<>();
            if (!f.equals(file)) return result;

            CompilationUnit cu = model.getAst(f);
            if (cu == null) return result;

            for (Object obj : cu.types()) {
                if (!(obj instanceof TypeDeclaration type)) continue;
                for (Object member : type.bodyDeclarations()) {
                    if (!(member instanceof MethodDeclaration method)) continue;
                    if (method.getBody() == null) continue;

                    String methodKey = type.getName().getIdentifier() + "." + method.getName().getIdentifier();
                    Set<CallSite> calls = callSites.get(methodKey);
                    if (calls == null) continue;

                    for (CallSite cs : calls) {
                        if (cs.method.equals(methodName) || cs.resolved.contains(methodName)) {
                            result.add(new CallSite(method.getName().getIdentifier(), cs.line, type.getName().getIdentifier() + "." + methodName));
                        }
                    }
                }
            }
            return result;
        }

        private void analyze() {
            CompilationUnit cu = model.getAst(file);
            if (cu == null) return;

            for (Object obj : cu.types()) {
                if (!(obj instanceof TypeDeclaration type)) continue;
                String typeName = type.getName().getIdentifier();

                for (Object member : type.bodyDeclarations()) {
                    if (!(member instanceof MethodDeclaration method)) continue;
                    String methodKey = typeName + "." + method.getName().getIdentifier();
                    if (method.getBody() == null) continue;

                for (Object stmtObj : method.getBody().statements()) {
                    ASTNode stmt = (ASTNode) stmtObj;
                    for (MethodInvocation call : findMethodCalls(stmt)) {
                        IMethodBinding binding = call.resolveMethodBinding();
                        String resolved = "";
                        if (binding != null) {
                            resolved = binding.getDeclaringClass().getName() + "." + binding.getName() + "()";
                            System.err.println("Resolved binding for call " + call.getName() + ": " + resolved);
                        } else {
                            System.err.println("Binding is null for call " + call.getName());
                        }
                        int line = cu.getLineNumber(call.getStartPosition());
                        callSites.computeIfAbsent(methodKey, k -> new TreeSet<>())
                                .add(new CallSite(call.getName().getIdentifier(), line, resolved));
                        callers.computeIfAbsent(call.getName().getIdentifier(), k -> new TreeSet<>())
                                .add(new CallSite(typeName + "." + method.getName().getIdentifier(), line, resolved));
                    }

                        for (Object fieldAssign : findFieldAssignments(stmt)) {
                            var fa = (org.eclipse.jdt.core.dom.FieldAccess) fieldAssign;
                            IVariableBinding binding = fa.resolveFieldBinding();
                            String fieldRef = binding != null ?
                                binding.getDeclaringClass().getName() + "." + binding.getName() :
                                fa.getName().getIdentifier();
                            int line = cu.getLineNumber(fa.getStartPosition());
                            callers.computeIfAbsent(fa.getName().getIdentifier(), k -> new TreeSet<>())
                                   .add(new CallSite(typeName + "." + method.getName().getIdentifier(), line, "FieldAccess:" + fieldRef));
                        }
                    }
                }
            }
        }

        public Set<CallSite> getCallersByName(String methodName) {
            return callers.getOrDefault(methodName, Collections.emptySet());
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

        private List<ASTNode> findFieldAssignments(ASTNode node) {
            List<ASTNode> result = new ArrayList<>();
            node.accept(new ASTVisitor() {
                @Override
                public boolean visit(FieldAccess n) {
                    if (n.getExpression() != null) {
                        result.add(n);
                    }
                    return super.visit(n);
                }
            });
            return result;
        }

        public Set<CallSite> getCallees(Path f, String methodName) {
            Set<CallSite> result = new TreeSet<>();
            if (!f.equals(file)) return result;

            CompilationUnit cu = model.getAst(f);
            if (cu == null) return result;

            for (Object obj : cu.types()) {
                if (!(obj instanceof TypeDeclaration type)) continue;
                for (Object member : type.bodyDeclarations()) {
                    if (!(member instanceof MethodDeclaration method)) continue;
                    if (method.getName().getIdentifier().equals(methodName)) {
                        String methodKey = type.getName().getIdentifier() + "." + methodName;
                        result.addAll(callSites.getOrDefault(methodKey, Collections.emptySet()));
                    }
                }
            }
            return result;
        }

        public Map<String, Set<CallSite>> getAllMethods(Path f) {
            Map<String, Set<CallSite>> result = new TreeMap<>();
            if (!f.equals(file)) return result;

            CompilationUnit cu = model.getAst(f);
            if (cu == null) return result;

            for (Object obj : cu.types()) {
                if (!(obj instanceof TypeDeclaration type)) continue;
                for (Object member : type.bodyDeclarations()) {
                    if (!(member instanceof MethodDeclaration method)) continue;
                    String methodKey = type.getName().getIdentifier() + "." + method.getName().getIdentifier();
                    result.put(method.getName().getIdentifier(), callSites.getOrDefault(methodKey, Collections.emptySet()));
                }
            }
            return result;
        }

        public String toDot() {
            StringBuilder sb = new StringBuilder();
            sb.append("digraph callgraph {\n");
            sb.append("  rankdir=LR;\n");
            sb.append("  node [shape=box];\n\n");

            Set<String> nodes = new TreeSet<>();
            Set<String> edges = new TreeSet<>();

            for (Map.Entry<String, Set<CallSite>> entry : callSites.entrySet()) {
                String caller = entry.getKey();
                nodes.add("  \"" + escapeDot(caller) + "\";");
                for (CallSite cs : entry.getValue()) {
                    String callee = cs.resolved.isEmpty() ? caller.substring(0, caller.lastIndexOf('.')) + "." + cs.method : cs.resolved;
                    nodes.add("  \"" + escapeDot(callee) + "\";");
                    edges.add("  \"" + escapeDot(caller) + "\" -> \"" + escapeDot(callee) + "\";");
                }
            }

            for (String node : nodes) {
                sb.append(node).append("\n");
            }
            sb.append("\n");
            for (String edge : edges) {
                sb.append(edge).append("\n");
            }
            sb.append("}\n");
            return sb.toString();
        }

        public static String escapeDot(String s) {
            return s.replace("\"", "\\\"");
        }

        public static class CallSite implements Comparable<CallSite> {
            public final String method;
            public final int line;
            public final String resolved;

            public CallSite(String method, int line, String resolved) {
                this.method = method;
                this.line = line;
                this.resolved = resolved;
            }

            @Override
            public int compareTo(CallSite o) {
                int c = method.compareTo(o.method);
                return c != 0 ? c : Integer.compare(line, o.line);
            }

            @Override
            public String toString() {
                if (!resolved.isEmpty() && !resolved.equals(method)) {
                    return method + " -> " + resolved + " (line " + line + ")";
                }
                return method + " (line " + line + ")";
            }
        }
    }
}