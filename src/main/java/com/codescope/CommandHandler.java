package com.codescope;

import org.eclipse.jdt.core.dom.*;

import java.nio.file.*;
import java.util.*;

/**
 * Handles all CLI commands.
 * Routes commands to appropriate builders and formatters.
 */
public class CommandHandler {

    public static String handle(String command, Path sourceFile, String query,
            boolean noJdk, boolean cycles, boolean heatmap) throws Exception {
        Path dir = sourceFile.getParent();
        AnalysisEngine engine = new AnalysisEngine(dir);

        return switch (command) {
            case "context" -> engine.buildContext(sourceFile, query);
            case "calls" -> buildCalls(engine, sourceFile, query);
            case "callers" -> buildCallers(engine, sourceFile, query);
            case "dot" -> engine.buildDot(noJdk, cycles, heatmap);
            case "classpath" -> buildClasspath(sourceFile);
            case "impact" -> buildImpact(sourceFile, query);
            case "impact-dot" -> buildImpactDot(sourceFile, query);
            case "ast" -> buildAst(engine, sourceFile);
            case "index" -> buildIndex(sourceFile, query);
            default -> "Unknown command: " + command;
        };
    }

    static String buildCalls(AnalysisEngine engine, Path sourceFile, String methodName) {
        StringBuilder sb = new StringBuilder();
        sb.append("# Call Graph for: ").append(sourceFile.getFileName()).append("\n\n");

        ProjectModel model = engine.findModel(sourceFile);
        if (model == null) return "File not found: " + sourceFile;

        CompilationUnit cu = model.getAst(sourceFile);
        if (cu == null) return "File not found: " + sourceFile;

        CallGraphBuilder cg = new DefaultCallGraphBuilder(sourceFile, model);

        if (methodName == null) {
            sb.append("(use: calls <file> <methodName>)\n");
            return sb.toString();
        }

        Set<CallGraphBuilder.CallSite> calls = cg.getCallees(sourceFile, methodName);

        sb.append("## Callees of ").append(methodName).append("\n");
        if (calls.isEmpty()) {
            sb.append("(no calls found)\n");
        } else {
            for (var call : calls) {
                sb.append("- ").append(call).append("\n");
            }
        }

        return sb.toString();
    }

    static String buildCallers(AnalysisEngine engine, Path sourceFile, String methodName) {
        StringBuilder sb = new StringBuilder();
        sb.append("# Callers of: ").append(methodName != null ? methodName : sourceFile.getFileName()).append("\n\n");

        if (methodName == null) {
            sb.append("(use: callers <file> <methodName>)\n");
            return sb.toString();
        }

        ProjectModel model = engine.findModel(sourceFile);
        if (model == null) return "File not found: " + sourceFile;

        CallGraphBuilder cg = new DefaultCallGraphBuilder(sourceFile, model);

        sb.append("## Callers of ").append(methodName).append("\n");

        // Try simple name first, then qualified name
        Set<CallGraphBuilder.CallSite> callers = cg.getCallersByName(methodName);
        if (callers.isEmpty()) {
            callers = cg.getCallersForMethod(sourceFile.getFileName().toString().replace(".java", ""), methodName);
        }

        if (callers.isEmpty()) {
            sb.append("(no callers found)\n");
        } else {
            for (var caller : callers) {
                sb.append("- ").append(caller).append("\n");
            }
        }
        return sb.toString();
    }

    static String buildClasspath(Path dir) throws Exception {
        StringBuilder sb = new StringBuilder();
        sb.append("# Classpath for: ").append(dir).append("\n\n");

        Path pom = dir.resolve("pom.xml");
        if (!Files.exists(pom)) {
            pom = dir.getParent() != null ? dir.getParent().resolve("pom.xml") : null;
        }

        if (pom != null && Files.exists(pom)) {
            sb.append("Detected pom.xml\n\n");
            List<String> deps = new DefaultMavenParser().parseDependencies(pom);
            for (String dep : deps) {
                sb.append("- ").append(dep).append("\n");
            }
        } else {
            sb.append("No pom.xml found\n");
        }

        return sb.toString();
    }

    static String buildImpact(Path sourceFile, String methodQuery) throws Exception {
        if (methodQuery == null) {
            return "Error: Please specify a method name, e.g., impact SomeClass.java methodName";
        }
        
        StringBuilder sb = new StringBuilder();
        sb.append("# Impact Analysis\n\n");

        Path dir = sourceFile;
        if (Files.isRegularFile(sourceFile)) {
            dir = sourceFile.getParent();
        }

        sb.append("Scanning: ").append(dir).append("\n\n");

        AnalysisEngine engine = new AnalysisEngine(dir);

        if (methodQuery != null) {
            String className = sourceFile.getFileName().toString().replace(".java", "");

            ProjectModel model = engine.findModel(sourceFile);
            if (model != null) {
                CompilationUnit sourceCu = model.getAst(sourceFile);
                if (sourceCu != null) {
                    for (Object obj : sourceCu.types()) {
                        if (obj instanceof org.eclipse.jdt.core.dom.TypeDeclaration type) {
                            for (Object member : type.bodyDeclarations()) {
                                if (member instanceof org.eclipse.jdt.core.dom.MethodDeclaration method) {
                                    if (method.getName().getIdentifier().equals(methodQuery)) {
                                        className = type.getName().getIdentifier();
                                        break;
                                    }
                                }
                            }
                        }
                    }
                }
            }

            sb.append("## Method: ").append(className).append(".").append(methodQuery).append("\n\n");

            // Build unified caller map in one pass
            Map<String, Set<CallGraphBuilder.CallSite>> allCallers = new HashMap<>();
            for (Path f : engine.getFiles()) {
                ProjectModel m = engine.findModel(f);
                if (m == null) continue;

                CallGraphBuilder cg = new DefaultCallGraphBuilder(f, m);
                for (Map.Entry<String, Set<CallGraphBuilder.CallSite>> entry : cg.getAllCallers().entrySet()) {
                    String calleeMethod = entry.getKey();
                    for (CallGraphBuilder.CallSite caller : entry.getValue()) {
                        if (calleeMethod.contains(".") && calleeMethod.equals(caller.resolved)) {
                            allCallers.computeIfAbsent(calleeMethod, k -> new TreeSet<>())
                                .add(new CallGraphBuilder.CallSite(caller.method, caller.line, caller.resolved));
                        }
                    }
                }
            }

            // Query the unified map
            String targetMethod = className + "." + methodQuery;
            // Resolve to fully qualified name if not found directly
            if (methodQuery != null && !allCallers.containsKey(targetMethod)) {
                for (String key : allCallers.keySet()) {
                    if (key.endsWith("." + methodQuery)) {
                        targetMethod = key;
                        break;
                    }
                }
            }
            Set<CallGraphBuilder.CallSite> callers = allCallers.getOrDefault(targetMethod, Collections.emptySet());
            int impactCount = callers.size();
            if (!callers.isEmpty()) {
                sb.append("### Callers\n");
                for (var caller : callers) {
                    sb.append("- ").append(caller.method).append(" at line ").append(caller.line).append("\n");
                }
            }

            sb.append("\n## Impact Summary\n");
            sb.append("Total calls: ").append(impactCount).append("\n");
        } else {
            sb.append("Analyzing all file changes...\n");
            sb.append("\n## Usage\n");
            sb.append("java -jar codescope.jar impact /path/to/file.java methodName\n");
        }

        return sb.toString();
    }

    static String buildImpactDot(Path sourceFile, String methodQuery) throws Exception {
        if (methodQuery == null) {
            return "Error: Please specify a method name, e.g., impact-dot SomeClass.java methodName";
        }
        
        StringBuilder sb = new StringBuilder();
        sb.append("digraph impact_").append(methodQuery).append(" {\n");
        sb.append("  rankdir=LR;\n");
        sb.append("  node [shape=box];\n");

        Path dir = sourceFile;
        if (Files.isRegularFile(sourceFile)) {
            dir = sourceFile.getParent();
        }

        String className = sourceFile.getFileName().toString().replace(".java", "");
        String targetMethod = className + "." + methodQuery;

        AnalysisEngine engine = new AnalysisEngine(dir);

        Map<String, Set<CallGraphBuilder.CallSite>> calleeToCallers = new HashMap<>();
        for (Path f : engine.getFiles()) {
            ProjectModel model = engine.findModel(f);
            if (model == null) continue;

            CallGraphBuilder cg = new DefaultCallGraphBuilder(f, model);
            for (Map.Entry<String, Set<CallGraphBuilder.CallSite>> entry : cg.getAllCallers().entrySet()) {
                String calleeMethod = entry.getKey();
                for (var caller : entry.getValue()) {
                    if (calleeMethod.contains(".")) {
                        calleeToCallers.computeIfAbsent(calleeMethod, k -> new TreeSet<>())
                            .add(new CallGraphBuilder.CallSite(caller.method, caller.line, calleeMethod));
                    }
                }
            }
        }

        // Resolve fully qualified method name if targetMethod doesn't match directly
        String resolvedTarget = targetMethod;
        if (methodQuery != null && !calleeToCallers.containsKey(targetMethod)) {
            for (String key : calleeToCallers.keySet()) {
                if (key.endsWith("." + methodQuery)) {
                    resolvedTarget = key;
                    break;
                }
            }
        }

        Set<String> visitedNodes = new HashSet<>();
        Set<String> edges = new TreeSet<>();
        collectCallChain(resolvedTarget, calleeToCallers, visitedNodes, edges);

        for (String edge : edges) {
            sb.append("  ").append(edge).append("\n");
        }

        sb.append("}\n");
        return sb.toString();
    }

    private static void collectCallChain(String targetMethod,
            Map<String, Set<CallGraphBuilder.CallSite>> calleeToCallers,
            Set<String> visited, Set<String> edges) {
        if (visited.contains(targetMethod)) return;
        visited.add(targetMethod);

        Set<CallGraphBuilder.CallSite> callers = calleeToCallers.getOrDefault(targetMethod, Collections.emptySet());
        if (callers == null || callers.isEmpty()) return;

        for (var caller : callers) {
            edges.add("\"" + escapeDot(caller.method) + "\" -> \"" + escapeDot(targetMethod) + "\" [color=blue]");
            if (!caller.resolved.isEmpty()) {
                collectCallChain(caller.resolved, calleeToCallers, visited, edges);
            }
        }
    }

    private static String escapeDot(String s) {
        return DotGenerator.escapeDot(s);
    }

    static String buildAst(AnalysisEngine engine, Path sourceFile) {
        StringBuilder sb = new StringBuilder();
        sb.append("# AST for: ").append(sourceFile.getFileName()).append("\n\n");

        ProjectModel model = engine.findModel(sourceFile);
        if (model == null) return "File not found: " + sourceFile;

        CompilationUnit cu = model.getAst(sourceFile);
        if (cu == null) return "File not found: " + sourceFile;

        sb.append("## Package\n");
        sb.append(cu.getPackage() != null ? cu.getPackage().getName() : "(default)").append("\n\n");

        sb.append("## Types\n");
        for (Object obj : cu.types()) {
            if (obj instanceof org.eclipse.jdt.core.dom.TypeDeclaration type) {
                sb.append("- ").append(type.getName().getIdentifier());
                if (type.isInterface()) {
                    sb.append(" (interface)");
                }
                sb.append("\n");
            }
        }

        return sb.toString();
    }

    private static String buildIndex(Path dir, String method) throws Exception {
        List<Path> dirs = new ArrayList<>();
        dirs.add(dir);

        Index index = new Index(dirs);
        index.build();

        if (method != null) {
            return index.findMethod(method);
        } else {
            return index.summary();
        }
    }
}
