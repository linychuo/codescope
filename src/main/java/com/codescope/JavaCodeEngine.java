package com.codescope;

import org.eclipse.jdt.core.dom.*;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.*;

/**
 * Public API for programmatic access to CodeScope.
 * Provides high-level interface for semantic analysis.
 */
public class JavaCodeEngine {

    private final AnalysisEngine engine;
    private final ProjectModel model;
    private final Index index;
    private static final Logger logger = Logger.getLogger("JavaCodeEngine");

    /**
     * Creates a new engine for the given source directory.
     *
     * @param sourceDir directory containing Java source files
     * @throws IOException if directory access fails
     */
    public JavaCodeEngine(Path sourceDir) throws IOException {
        this.engine = new AnalysisEngine(sourceDir);
        this.model = engine.getModels().isEmpty() ? null : engine.getModels().get(0);
        this.index = new Index(List.of(sourceDir));
    }

    public AnalysisEngine getEngine() {
        return engine;
    }

    public ProjectModel getProjectModel() {
        return model;
    }

    public Index getIndex() {
        return index;
    }

    /**
     * Closes the engine and saves cache to disk.
     */
    public void close() {
        try {
            model.saveToCache();
        } catch (IOException e) {
            logger.warning("Failed to save cache", e);
        }
        model.shutdown();
    }

    /**
     * Container for semantic information about a Java class.
     */
    public static class SemanticContext {
        private final Path file;
        private final String className;
        private final String packageName;
        private final List<String> imports;
        private final List<MethodInfo> methods;
        private final List<FieldInfo> fields;
        private final List<ConstructorInfo> constructors;
        private final String superclass;
        private final List<String> interfaces;

        public SemanticContext(Path file, String className, String packageName, List<String> imports,
                          List<MethodInfo> methods, List<FieldInfo> fields,
                          List<ConstructorInfo> constructors, String superclass, List<String> interfaces) {
            this.file = file;
            this.className = className;
            this.packageName = packageName;
            this.imports = imports;
            this.methods = methods;
            this.fields = fields;
            this.constructors = constructors;
            this.superclass = superclass;
            this.interfaces = interfaces;
        }

        public Path getFile() { return file; }
        public String getClassName() { return className; }
        public String getPackageName() { return packageName; }
        public List<String> getImports() { return imports; }
        public List<MethodInfo> getMethods() { return methods; }
        public List<FieldInfo> getFields() { return fields; }
        public List<ConstructorInfo> getConstructors() { return constructors; }
        public String getSuperclass() { return superclass; }
        public List<String> getInterfaces() { return interfaces; }
    }

    public static class MethodInfo {
        private final String name;
        private final String returnType;
        private final String modifiers;
        private final List<String> parameters;
        private final String body;

        public MethodInfo(String name, String returnType, String modifiers,
                        List<String> parameters, String body) {
            this.name = name;
            this.returnType = returnType;
            this.modifiers = modifiers;
            this.parameters = parameters;
            this.body = body;
        }

        public String getName() { return name; }
        public String getReturnType() { return returnType; }
        public String getModifiers() { return modifiers; }
        public List<String> getParameters() { return parameters; }
        public String getBody() { return body; }
    }

    public static class FieldInfo {
        private final String name;
        private final String type;
        private final String modifiers;

        public FieldInfo(String name, String type, String modifiers) {
            this.name = name;
            this.type = type;
            this.modifiers = modifiers;
        }

        public String getName() { return name; }
        public String getType() { return type; }
        public String getModifiers() { return modifiers; }
    }

    public static class ConstructorInfo {
        private final List<String> parameters;
        private final String modifiers;

        public ConstructorInfo(List<String> parameters, String modifiers) {
            this.parameters = parameters;
            this.modifiers = modifiers;
        }

        public List<String> getParameters() { return parameters; }
        public String getModifiers() { return modifiers; }
    }

    public static class CallInfo {
        private final String method;
        private final int line;
        private final String resolved;

        public CallInfo(String method, int line, String resolved) {
            this.method = method;
            this.line = line;
            this.resolved = resolved;
        }

        public String getMethod() { return method; }
        public int getLine() { return line; }
        public String getResolved() { return resolved; }

        @Override
        public String toString() {
            if (resolved != null && !resolved.isEmpty() && !resolved.equals(method)) {
                return method + " -> " + resolved + " (line " + line + ")";
            }
            return method + " (line " + line + ")";
        }
    }

    public SemanticContext getSemanticContext(Path file) {
        return getSemanticContext(file, null);
    }

    public SemanticContext getSemanticContext(Path file, String methodName) {
        CompilationUnit cu = model.getAst(file);
        if (cu == null) return null;

        String className = "Unknown";
        String packageName = null;
        List<String> imports = new java.util.ArrayList<>();
        List<MethodInfo> methods = new java.util.ArrayList<>();
        List<FieldInfo> fields = new java.util.ArrayList<>();
        List<ConstructorInfo> constructors = new java.util.ArrayList<>();
        String superclass = null;
        List<String> interfaces = new java.util.ArrayList<>();

        for (Object obj : cu.types()) {
            if (obj instanceof TypeDeclaration type) {
                className = type.getName().getIdentifier();

                var pkg = cu.getPackage();
                if (pkg != null) {
                    packageName = pkg.getName().toString();
                }

                for (Object imp : cu.imports()) {
                    if (imp instanceof org.eclipse.jdt.core.dom.ImportDeclaration id) {
                        imports.add(id.getName().toString());
                    }
                }

                for (Object member : type.bodyDeclarations()) {
                    if (member instanceof MethodDeclaration m) {
                        String name = m.getName().getIdentifier();
                        String retType = m.getReturnType2().toString();
                        String mods = org.eclipse.jdt.core.Flags.toString(m.getModifiers());
                        java.util.List<String> params = new java.util.ArrayList<>();
                        for (Object p : m.parameters()) {
                            params.add(p.toString());
                        }
                        String body = m.getBody() != null ? m.getBody().toString() : null;
                        methods.add(new MethodInfo(name, retType, mods, params, body));
                    } else if (member instanceof org.eclipse.jdt.core.dom.FieldDeclaration f) {
                        String typeStr = f.getType().toString();
                        String mods = org.eclipse.jdt.core.Flags.toString(f.getModifiers());
                        for (Object frag : f.fragments()) {
                            if (frag instanceof org.eclipse.jdt.core.dom.VariableDeclarationFragment v) {
                                fields.add(new FieldInfo(v.getName().getIdentifier(), typeStr, mods));
                            }
                        }
                    } else if (member instanceof MethodDeclaration m && m.isConstructor()) {
                        String mods = org.eclipse.jdt.core.Flags.toString(m.getModifiers());
                        java.util.List<String> params = new java.util.ArrayList<>();
                        for (Object p : m.parameters()) {
                            params.add(p.toString());
                        }
                        constructors.add(new ConstructorInfo(params, mods));
                    }
                }

                var binding = type.resolveBinding();
                if (binding != null) {
                    var superBinding = binding.getSuperclass();
                    if (superBinding != null && !superBinding.getName().equals("java.lang.Object")) {
                        superclass = superBinding.getName();
                    }
                    for (var iface : binding.getInterfaces()) {
                        interfaces.add(iface.getName());
                    }
                }
                break;
            }
        }

        return new SemanticContext(file, className, packageName, imports, methods, fields, constructors, superclass, interfaces);
    }

    public Set<CallInfo> getMethodCalls(Path file, String methodName) {
        return getCallees(file, methodName).stream()
            .map(cs -> new CallInfo(cs.method, cs.line, cs.resolved))
            .collect(Collectors.toSet());
    }

    public String getContextMarkdown(Path file) {
        return engine.buildContext(file, null);
    }

    public String getMethodMarkdown(Path file, String methodName) {
        return engine.buildContext(file, methodName);
    }

    public String getCallsMarkdown(Path file) {
        return engine.buildContext(file, "calls");
    }

    private Set<CallGraphBuilder.CallSite> getCallees(Path f, String methodName) {
        DefaultCallGraphBuilder cg = new DefaultCallGraphBuilder(f, model);
        return cg.getCallees(f, methodName);
    }
}