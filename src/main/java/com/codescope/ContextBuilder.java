package com.codescope;

import org.eclipse.jdt.core.dom.*;
import org.eclipse.jdt.core.Flags;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

public class ContextBuilder {

    public final ProjectModel model;

    public ContextBuilder(Path dir) throws IOException {
        this.model = new ProjectModel(dir);
        if (dir != null) {
            model.init();
            Files.list(dir)
                .filter(p -> p.toString().endsWith(".java"))
                .forEach(p -> {
                    try { model.addFile(p); } catch (IOException e) {}
                });
        }
    }

    public String build(Path file, String query) {
        CompilationUnit cu = model.getAst(file);
        if (cu == null) return "File not found: " + file;

        CallGraph cg = new CallGraph(file, model);
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