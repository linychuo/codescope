package com.codescope;

import org.eclipse.jdt.core.dom.*;

import java.nio.file.*;
import java.util.*;

/**
 * Default implementation of CallGraphBuilder.
 * Analyzes method call relationships within a single Java file.
 */
public class DefaultCallGraphBuilder implements CallGraphBuilder {

    private final Path file;
    private final ProjectModel model;
    final Map<String, Set<CallSite>> callSites = new HashMap<>();
    private final Map<String, Set<CallSite>> callers = new HashMap<>();

    public DefaultCallGraphBuilder(Path file, ProjectModel model) {
        this.file = file;
        this.model = model;
        analyze();
    }

    @Override
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
                    String methodKey = type.getName().getIdentifier() + "." + method.getName().getIdentifier();
                    result.addAll(callSites.getOrDefault(methodKey, Collections.emptySet()));
                }
            }
        }
        return result;
    }

    @Override
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

    @Override
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

    @Override
    public Set<CallSite> getCallersByName(String methodName) {
        return new TreeSet<>(callers.getOrDefault(methodName, Collections.emptySet()));
    }

    @Override
    public Set<CallSite> getCallersForMethod(String className, String methodName) {
        Set<CallSite> result = new TreeSet<>();
        String targetKey = className + "." + methodName;
        result.addAll(callers.getOrDefault(targetKey, Collections.emptySet()));
        if (result.isEmpty()) {
            result.addAll(callers.getOrDefault(methodName, Collections.emptySet()));
        }
        return result;
    }

    @Override
    public Map<String, Set<CallSite>> getAllCallers() {
        return Collections.unmodifiableMap(callers);
    }

    @Override
    public Map<String, Set<CallSite>> getCallSites() {
        return Collections.unmodifiableMap(callSites);
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
                        String callerKey;
                        if (binding != null && binding.getDeclaringClass() != null) {
                            resolved = binding.getDeclaringClass().getName() + "." + binding.getName();
                            callerKey = resolved;
                        } else {
                            callerKey = call.getName().getIdentifier();
                        }
                        int line = cu.getLineNumber(call.getStartPosition());
                        callSites.computeIfAbsent(methodKey, k -> new TreeSet<>())
                               .add(new CallSite(call.getName().getIdentifier(), line, resolved));
                        callers.computeIfAbsent(callerKey, k -> new TreeSet<>())
                               .add(new CallSite(typeName + "." + method.getName().getIdentifier(), line, resolved));
                    }

                    for (Object fieldAssign : findFieldAssignments(stmt)) {
                        var fa = (org.eclipse.jdt.core.dom.FieldAccess) fieldAssign;
                        IVariableBinding fb = fa.resolveFieldBinding();
                        String fieldRef = fb != null ?
                            fb.getDeclaringClass().getName() + "." + fb.getName() :
                            fa.getName().getIdentifier();
                        int fieldLine = cu.getLineNumber(fa.getStartPosition());
                        callers.computeIfAbsent(fa.getName().getIdentifier(), k -> new TreeSet<>())
                               .add(new CallSite(typeName + "." + method.getName().getIdentifier(), fieldLine, "FieldAccess:" + fieldRef));
                    }
                }
            }
        }
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

    public static String escapeDot(String s) {
        return s.replace("\"", "\\\"");
    }

    /**
     * Represents a method call site with line number and resolved type.
     */
    public static class CallSite implements Comparable<CallSite> {
        public final String method;
        public final int line;
        public final String resolved;

        public CallSite(String method, int line, String resolved) {
            this.method = method;
            this.line = line;
            this.resolved = resolved;
        }

        public static CallSite create(String method, int startPosition, String resolved, CompilationUnit cu) {
            int line = 1;
            if (cu != null && startPosition > 0) {
                line = cu.getLineNumber(startPosition);
            }
            return new CallSite(method, line, resolved);
        }

        @Override
        public int compareTo(CallSite o) {
            int c = method.compareTo(o.method);
            return c != 0 ? c : Integer.compare(line, o.line);
        }

        @Override
        public String toString() {
            return method + (resolved.isEmpty() ? "" : " -> " + resolved) + " (line " + line + ")";
        }

        public String toFullString() {
            return resolved.isEmpty() ? method + " at line " + line : resolved + " at line " + line;
        }
    }
}
