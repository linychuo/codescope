package com.codescope;

import org.eclipse.jdt.core.dom.*;
import org.eclipse.jdt.core.Flags;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

public class ContextBuilder {

    private final List<ProjectModel> models = new ArrayList<>();

    public ContextBuilder(Path dir) throws IOException {
        if (dir == null) return;

        if (Files.isRegularFile(dir)) {
            dir = dir.getParent();
        }
        Set<Path> javaRoots = findJavaRoots(dir);
        if (javaRoots.isEmpty()) {
            javaRoots.add(dir);
        }
        for (Path javaRoot : javaRoots) {
            ProjectModel model = new ProjectModel(javaRoot);
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

    public Set<Path> getFiles() {
        Set<Path> files = new HashSet<>();
        for (ProjectModel model : models) {
            files.addAll(model.getFiles());
        }
        return files;
    }

    public List<ProjectModel> getModels() {
        return models;
    }

    public String buildDot() {
        return buildDot(false, false, false);
    }

    public String buildDot(boolean noJdk, boolean cycles, boolean heatmap) {
        final boolean filterJdk = noJdk;
        final boolean checkCycles = cycles;
        final boolean showHeatmap = heatmap;

        Map<String, Integer> edgeCounts = new HashMap<>();
        Set<String> jdkNodes = new HashSet<>();

        for (ProjectModel model : models) {
            for (Path file : model.getFiles()) {
                CompilationUnit cu = model.getAst(file);
                if (cu == null) continue;

                CallGraph cg = new CallGraph(file, model);
                for (Map.Entry<String, Set<CallGraph.CallSite>> entry : cg.callSites.entrySet()) {
                    String caller = entry.getKey();
                    for (CallGraph.CallSite cs : entry.getValue()) {
                        String callee = cs.resolved.isEmpty() ? caller.substring(0, caller.lastIndexOf('.')) + "." + cs.method : cs.resolved;

                        if (filterJdk && (callee.startsWith("java.") || callee.contains(".java."))) {
                            jdkNodes.add(caller);
                            continue;
                        }

                        String edge = caller + "->" + callee;
                        edgeCounts.merge(edge, 1, Integer::sum);
                    }
                }
            }
        }

        Set<String> filteredEdges = new HashSet<>();
        if (filterJdk) {
            for (String edge : edgeCounts.keySet()) {
                String caller = edge.substring(0, edge.indexOf("->"));
                if (!jdkNodes.contains(caller)) {
                    filteredEdges.add(edge);
                }
            }
        } else {
            filteredEdges = edgeCounts.keySet();
        }

        Set<String> allNodes = new HashSet<>();
        Set<String> allEdges = new TreeSet<>();
        for (String edge : filteredEdges) {
            String[] parts = edge.split("->");
            allNodes.add(parts[0]);
            allNodes.add(parts[1]);
            allEdges.add(edge);
        }

        StringBuilder sb = new StringBuilder();
        sb.append("digraph callgraph {\n");
        sb.append("  rankdir=LR;\n");

        if (showHeatmap) {
            sb.append("  node [shape=box, style=filled];\n");
            int maxCount = edgeCounts.values().stream().max(Integer::compareTo).orElse(1);
            for (Map.Entry<String, Integer> e : edgeCounts.entrySet()) {
                String[] parts = e.getKey().split("->");
                double ratio = (double) e.getValue() / maxCount;
                String color = getHeatColor(ratio);
                sb.append("  \"" + escapeDot(parts[0]) + "\" [fillcolor=" + color + ", color=black];\n");
            }
        } else {
            sb.append("  node [shape=box];\n");
        }
        sb.append("\n");

        for (String node : allNodes) {
            sb.append("  \"" + escapeDot(node) + "\";\n");
        }
        sb.append("\n");

        Set<String> cycleEdges = checkCycles ? detectCycles(allNodes, allEdges) : Collections.emptySet();
        for (String edge : allEdges) {
            String[] parts = edge.split("->");
            String style = cycleEdges.contains(edge) ? " [style=bold, color=red]" : "";
            sb.append("  \"" + escapeDot(parts[0]) + "\" -> \"" + escapeDot(parts[1]) + "\"" + style + ";\n");
        }

        if (checkCycles && !cycleEdges.isEmpty()) {
            sb.append("\n  // Cycles detected:\n");
            for (String ce : cycleEdges) {
                sb.append("  // " + ce.replace("->", " -> ") + "\n");
            }
        }

        if (showHeatmap) {
            sb.append("\n  // Call counts:\n");
            List<Map.Entry<String, Integer>> sorted = new ArrayList<>(edgeCounts.entrySet());
            sorted.sort((a, b) -> b.getValue().compareTo(a.getValue()));
            for (var e : sorted) {
                if (filteredEdges.contains(e.getKey())) {
                    sb.append("  // " + e.getKey().replace("->", " -> ") + ": " + e.getValue() + "\n");
                }
            }
        }

        sb.append("}\n");
        return sb.toString();
    }

    private String getHeatColor(double ratio) {
        int r = (int) (255 * ratio);
        int b = (int) (255 * (1 - ratio));
        return "\"#" + String.format("%02x%02x%02x", r, 0, b) + "\"";
    }

    private Set<String> detectCycles(Set<String> nodes, Set<String> edges) {
        Set<String> cycleEdges = new HashSet<>();
        Map<String, List<String>> adj = new HashMap<>();

        for (String edge : edges) {
            String[] parts = edge.split("->");
            adj.computeIfAbsent(parts[0], k -> new ArrayList<>()).add(parts[1]);
        }

        Set<String> visited = new HashSet<>();
        Set<String> recursion = new HashSet<>();
        Deque<String> path = new ArrayDeque<>();

        for (String node : nodes) {
            if (detectCycle(node, adj, visited, recursion, path, cycleEdges)) {
                break;
            }
        }
        return cycleEdges;
    }

    private boolean detectCycle(String node, Map<String, List<String>> adj, Set<String> visited, Set<String> recursion, Deque<String> path, Set<String> cycleEdges) {
        visited.add(node);
        recursion.add(node);
        path.push(node);

        for (String next : adj.getOrDefault(node, Collections.emptyList())) {
            if (!visited.contains(next)) {
                if (detectCycle(next, adj, visited, recursion, path, cycleEdges)) {
                    return true;
                }
            } else if (recursion.contains(next)) {
                boolean started = false;
                for (String p : path) {
                    if (p.equals(next)) started = true;
                    if (started) {
                        cycleEdges.add(p + "->" + (path.descendingIterator().hasNext() ? path.descendingIterator().next() : next));
                    }
                }
                return true;
            }
        }

        path.pop();
        recursion.remove(node);
        return false;
    }

    private String escapeDot(String s) {
        return s.replace("\"", "\\\"");
    }

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
                            }
                            callSites.computeIfAbsent(methodKey, k -> new TreeSet<>())
                                   .add(new CallSite(call.getName().getIdentifier(), call.getStartPosition(), resolved));
                            callers.computeIfAbsent(call.getName().getIdentifier(), k -> new TreeSet<>())
                                   .add(new CallSite(typeName + "." + method.getName().getIdentifier(), call.getStartPosition(), ""));
                        }

                        for (Object fieldAssign : findFieldAssignments(stmt)) {
                            var fa = (org.eclipse.jdt.core.dom.FieldAccess) fieldAssign;
                            IVariableBinding binding = fa.resolveFieldBinding();
                            String fieldRef = binding != null ? 
                                binding.getDeclaringClass().getName() + "." + binding.getName() : 
                                fa.getName().getIdentifier();
                            callers.computeIfAbsent(fa.getName().getIdentifier(), k -> new TreeSet<>())
                                   .add(new CallSite(typeName + "." + method.getName().getIdentifier(), fa.getStartPosition(), "FieldAccess:" + fieldRef));
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