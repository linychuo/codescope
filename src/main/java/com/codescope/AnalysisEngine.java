package com.codescope;

import org.eclipse.jdt.core.dom.*;
import org.eclipse.jdt.core.Flags;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/**
 * Analysis engine that coordinates semantic analysis components.
 * Provides high-level API for context building, call graph analysis, and impact analysis.
 */
public class AnalysisEngine {

    private final DefaultSourceIndexer indexer;
    private final List<ProjectModel> models;
    private Set<Path> filesCache;

    private static final Logger logger = Logger.getLogger("AnalysisEngine");

    public AnalysisEngine(Path dir) throws IOException {
        this(dir, ClasspathResolver.resolveClasspath(dir));
    }

    public AnalysisEngine(Path dir, String[] classpath) throws IOException {
        if (dir == null) {
            this.indexer = null;
            this.models = new ArrayList<>();
            return;
        }

        DefaultSourceIndexer idx = new DefaultSourceIndexer(dir, classpath);
        List<ProjectModel> mods = new ArrayList<>(idx.getModels());

        // If no models found, try harder to find source roots
        if (mods.isEmpty()) {
            Path scanRoot = findScanRoot(dir);
            idx = new DefaultSourceIndexer(scanRoot, classpath);
            mods = new ArrayList<>(idx.getModels());

            // If still empty and dir is a file, try using its parent
            if (mods.isEmpty() && Files.isRegularFile(dir)) {
                Path parent = dir.getParent();
                if (parent != null) {
                    idx = new DefaultSourceIndexer(parent, classpath);
                    mods = new ArrayList<>(idx.getModels());
                }
            }
        }

        this.indexer = idx;
        this.models = mods;
    }

    private Path findScanRoot(Path start) {
        Path current = start;
        while (current != null) {
            if (Files.exists(current.resolve("pom.xml")) ||
                Files.exists(current.resolve("build.gradle")) ||
                Files.exists(current.resolve("build.gradle.kts"))) {
                return current;
            }
            Path parent = current.getParent();
            if (parent != null && parent.equals(current)) {
                break;
            }
            current = parent;
        }
        return start;
    }

    /**
     * Returns all files across all models.
     */
    public Set<Path> getFiles() {
        if (filesCache == null) {
            filesCache = new HashSet<>();
            for (ProjectModel model : models) {
                filesCache.addAll(model.getFiles());
            }
        }
        return Collections.unmodifiableSet(filesCache);
    }

    /**
     * Returns all project models.
     */
    public List<ProjectModel> getModels() {
        return Collections.unmodifiableList(models);
    }

    /**
     * Finds the project model that contains the given file.
     */
    public ProjectModel findModel(Path file) {
        for (ProjectModel model : models) {
            if (model.getFiles().contains(file)) {
                return model;
            }
        }
        return models.isEmpty() ? null : models.get(0);
    }

    /**
     * Builds semantic context for a file.
     */
    public String buildContext(Path file) {
        return buildContext(file, null);
    }

    /**
     * Builds semantic context for a file with optional method query.
     */
    public String buildContext(Path file, String query) {
        if (models.isEmpty()) return "No files found";

        ProjectModel model = findModel(file);
        if (model == null) return "File not found: " + file;

        CompilationUnit cu = model.getAst(file);
        if (cu == null) return "File not found: " + file;

        CallGraphBuilder cg = new DefaultCallGraphBuilder(file, model);
        return buildContext(cu, query, file, cg);
    }

    /**
     * Builds call graph DOT output.
     */
    public String buildDot() {
        return buildDot(false, false, false);
    }

    /**
     * Builds call graph DOT with options.
     */
    public String buildDot(boolean noJdk, boolean cycles, boolean heatmap) {
        return DotGenerator.generate(models, noJdk, cycles, heatmap);
    }

    /**
     * Builds call graph for a specific file.
     */
    public CallGraphBuilder buildCallGraph(Path file) {
        ProjectModel model = findModel(file);
        if (model == null) return null;
        return new DefaultCallGraphBuilder(file, model);
    }

    /**
     * Builds unified caller map across all files for impact analysis.
     */
    public Map<String, Set<CallGraphBuilder.CallSite>> buildCallerMap() {
        Map<String, Set<CallGraphBuilder.CallSite>> callerMap = new HashMap<>();
        for (Path f : getFiles()) {
            ProjectModel model = findModel(f);
            if (model == null) continue;

            DefaultCallGraphBuilder cg = new DefaultCallGraphBuilder(f, model);
            for (Map.Entry<String, Set<CallGraphBuilder.CallSite>> entry : cg.getAllCallers().entrySet()) {
                String calleeMethod = entry.getKey();
                for (CallGraphBuilder.CallSite caller : entry.getValue()) {
                    if (calleeMethod.contains(".") && !caller.resolved.isEmpty()) {
                        callerMap.computeIfAbsent(calleeMethod, k -> new TreeSet<>())
                            .add(new CallGraphBuilder.CallSite(caller.method, caller.line, caller.resolved));
                    }
                }
            }
        }
        return callerMap;
    }

    private String buildContext(CompilationUnit cu, String query, Path file, CallGraphBuilder cg) {
        StringBuilder sb = new StringBuilder();
        sb.append("# Semantic Context for: ").append(query != null ? query : "full file").append("\n\n");

        String className = "Unknown";
        for (Object obj : cu.types()) {
            if (obj instanceof TypeDeclaration type) {
                className = type.getName().getIdentifier();

                sb.append("## Class\n").append(className).append("\n");
                PackageDeclaration pkg = cu.getPackage();
                if (pkg != null) {
                    sb.append("**Package:** ").append(pkg.getName()).append("\n\n");
                }

                sb.append("### Annotations\n");
                for (Object modifier : type.modifiers()) {
                    if (modifier instanceof org.eclipse.jdt.core.dom.Annotation) {
                        sb.append("- @").append(modifier.toString()).append("\n");
                    }
                }

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

                sb.append("### Imports\n");
                for (Object imp : cu.imports()) {
                    if (imp instanceof ImportDeclaration id) {
                        sb.append("- ").append(id.getName().toString()).append("\n");
                    }
                }

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
        if (cu == null || query == null) return null;

        try {
            int targetLine = Integer.parseInt(query);
            return findMethodByLine(cu, targetLine);
        } catch (NumberFormatException e) {
            return findMethodByName(cu, query);
        }
    }

    private MethodDeclaration findMethodByLine(CompilationUnit cu, int targetLine) {
        for (Object obj : cu.types()) {
            if (!(obj instanceof TypeDeclaration type)) continue;
            for (Object member : type.bodyDeclarations()) {
                if (!(member instanceof MethodDeclaration m)) continue;
                if (m.getStartPosition() >= 0 && cu.getStartPosition() + targetLine == m.getStartPosition()) {
                    return m;
                }
            }
        }
        return null;
    }

    private MethodDeclaration findMethodByName(CompilationUnit cu, String name) {
        for (Object obj : cu.types()) {
            if (!(obj instanceof TypeDeclaration type)) continue;
            for (Object member : type.bodyDeclarations()) {
                if (!(member instanceof MethodDeclaration m)) continue;
                if (m.getName().getIdentifier().equals(name)) {
                    return m;
                }
            }
        }
        return null;
    }
}
