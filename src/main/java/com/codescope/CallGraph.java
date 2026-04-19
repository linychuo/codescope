package com.codescope;

import org.eclipse.jdt.core.dom.*;

import java.nio.file.*;
import java.util.*;

public class CallGraph {

    private final Path file;
    private final ProjectModel model;
    public final Map<String, Set<CallSite>> callSites = new HashMap<>();
    private final Map<String, Set<CallSite>> callers = new HashMap<>();

    public CallGraph(Path file, ProjectModel model) {
        this.file = file;
        this.model = model;
        analyze();
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
                    String methodKey = type.getName().getIdentifier() + "." + method.getName().getIdentifier();
                    result.addAll(callSites.getOrDefault(methodKey, Collections.emptySet()));
                }
            }
        }
        return result;
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

    public Set<CallSite> getCallersByName(String methodName) {
        return callers.getOrDefault(methodName, Collections.emptySet());
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

                        for (Object fieldAssign : findFieldAssignments(stmt)) {
                            var fa = (org.eclipse.jdt.core.dom.FieldAccess) fieldAssign;
                            IVariableBinding fb = fa.resolveFieldBinding();
                            String fieldRef = fb != null ? 
                                fb.getDeclaringClass().getName() + "." + fb.getName() : 
                                fa.getName().getIdentifier();
                            callers.computeIfAbsent(fa.getName().getIdentifier(), k -> new TreeSet<>())
                                   .add(new CallSite(typeName + "." + method.getName().getIdentifier(), fa.getStartPosition(), "FieldAccess:" + fieldRef));
                        }
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
            return Integer.compare(this.line, o.line);
        }

        @Override
        public String toString() {
            return method + (resolved.isEmpty() ? "" : " -> " + resolved) + " (line " + line + ")";
        }
    }
}