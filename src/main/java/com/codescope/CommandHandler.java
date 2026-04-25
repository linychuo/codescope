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
        ContextBuilder cb = new ContextBuilder(dir);
        
        return switch (command) {
            case "context" -> cb.build(sourceFile, query);
            case "calls" -> buildCalls(cb, sourceFile, query);
            case "callers" -> buildCallers(cb, sourceFile, query);
            case "dot" -> cb.buildDot(noJdk, cycles, heatmap);
            case "classpath" -> buildClasspath(sourceFile);
            case "impact" -> buildImpact(sourceFile, query);
            case "impact-dot" -> buildImpactDot(sourceFile, query);
            case "ast" -> buildAst(cb, sourceFile);
            case "index" -> buildIndex(sourceFile, query);
            default -> "Unknown command: " + command;
        };
    }

    static String buildCalls(ContextBuilder cb, Path sourceFile, String methodName) {
        StringBuilder sb = new StringBuilder();
        sb.append("# Call Graph for: ").append(sourceFile.getFileName()).append("\n\n");

        CompilationUnit cu = cb.getModels().get(0).getAst(sourceFile);
        if (cu == null) return "File not found: " + sourceFile;

        ContextBuilder.CallGraph cg = new ContextBuilder.CallGraph(sourceFile, cb.getModels().get(0));
        
        if (methodName == null) {
            sb.append("(use: calls <file> <methodName>)\n");
            return sb.toString();
        }
        
        Set<ContextBuilder.CallGraph.CallSite> calls = cg.getCallees(sourceFile, methodName);
        
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

    static String buildCallers(ContextBuilder cb, Path sourceFile, String methodName) {
        StringBuilder sb = new StringBuilder();
        sb.append("# Callers of: ").append(methodName != null ? methodName : sourceFile.getFileName()).append("\n\n");

        if (methodName == null) {
            sb.append("(use: callers <file> <methodName>)\n");
            return sb.toString();
        }

        sb.append("## Callers of ").append(methodName).append("\n");
        ContextBuilder.CallGraph cg = new ContextBuilder.CallGraph(sourceFile, cb.getModels().get(0));
        Set<ContextBuilder.CallGraph.CallSite> callers = cg.getCallersByName(methodName);
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
            List<String> deps = parseMavenDeps(pom);
            for (String dep : deps) {
                sb.append("- ").append(dep).append("\n");
            }
        } else {
            sb.append("No pom.xml found\n");
        }

        return sb.toString();
    }

    public static List<String> parseMavenDeps(Path pom) throws Exception {
        List<String> deps = new ArrayList<>();
        String content = Files.readString(pom);
        
        String pattern = "<dependency>.*?<groupId>(.*?)</groupId>.*?<artifactId>(.*?)</artifactId>.*?<version>(.*?)</version>.*?</dependency>";
        java.util.regex.Pattern p = java.util.regex.Pattern.compile(pattern, java.util.regex.Pattern.DOTALL);
        java.util.regex.Matcher m = p.matcher(content);
        
        while (m.find()) {
            String groupId = m.group(1).trim();
            String artifactId = m.group(2).trim();
            String version = m.group(3) != null ? m.group(3).trim() : "unknown";
            deps.add(groupId + ":" + artifactId + ":" + version);
        }
        
        return deps;
    }

    static String buildImpact(Path sourceFile, String methodQuery) throws Exception {
        StringBuilder sb = new StringBuilder();
        sb.append("# Impact Analysis\n\n");

        Path dir = sourceFile;
        if (Files.isRegularFile(sourceFile)) {
            dir = sourceFile.getParent();
        }

        sb.append("Scanning: ").append(dir).append("\n\n");

        ContextBuilder cb = new ContextBuilder(dir);

        if (methodQuery != null) {
            String className = sourceFile.getFileName().toString().replace(".java", "");

            CompilationUnit sourceCu = cb.getModels().get(0).getAst(sourceFile);
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

            sb.append("## Method: ").append(className).append(".").append(methodQuery).append("\n\n");

            int impactCount = 0;

            for (Path f : cb.getFiles()) {
                ContextBuilder.CallGraph cg = new ContextBuilder.CallGraph(f, cb.getModels().get(0));
                Set<ContextBuilder.CallGraph.CallSite> callers = cg.getCallersForMethod(className, methodQuery);
                if (!callers.isEmpty()) {
                    sb.append("### ").append(f.getFileName()).append("\n");
                    for (var caller : callers) {
                        sb.append("- line ").append(caller.line).append(": ")
                          .append(caller.method).append("\n");
                        impactCount++;
                    }
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

        ContextBuilder cb = new ContextBuilder(dir);

        Set<String> knownMethods = new HashSet<>();
        Map<String, Set<ContextBuilder.CallGraph.CallSite>> calleeToCallers = new HashMap<>();
        for (Path f : cb.getFiles()) {
            ContextBuilder.CallGraph cg = new ContextBuilder.CallGraph(f, cb.getModels().get(0));
            for (Map.Entry<String, Set<ContextBuilder.CallGraph.CallSite>> entry : cg.getAllCallers().entrySet()) {
                String calleeMethod = entry.getKey();
                for (var caller : entry.getValue()) {
                    if (calleeMethod.contains(".")) {
                        calleeToCallers.computeIfAbsent(calleeMethod, k -> new TreeSet<>())
                            .add(new ContextBuilder.CallGraph.CallSite(caller.method, caller.line, calleeMethod));
                    }
                }
            }
        }

        for (Path f : cb.getFiles()) {
            CompilationUnit cu = cb.getModels().get(0).getAst(f);
            if (cu != null) {
                for (Object obj : cu.types()) {
                    if (obj instanceof org.eclipse.jdt.core.dom.TypeDeclaration type) {
                        String typeName = type.getName().getIdentifier();
                        for (Object member : type.bodyDeclarations()) {
                            if (member instanceof org.eclipse.jdt.core.dom.MethodDeclaration method) {
                                knownMethods.add(typeName + "." + method.getName().getIdentifier());
                            }
                        }
                    }
                }
            }
        }

        Set<String> visitedNodes = new HashSet<>();
        Set<String> edges = new TreeSet<>();
        collectCallChain(targetMethod, calleeToCallers, visitedNodes, edges, knownMethods, className);

        for (String edge : edges) {
            sb.append("  ").append(edge).append("\n");
        }

        sb.append("}\n");
        return sb.toString();
    }

    private static void collectCallChain(String targetMethod,
            Map<String, Set<ContextBuilder.CallGraph.CallSite>> calleeToCallers,
            Set<String> visited, Set<String> edges, Set<String> knownMethods, String targetClass) {
        if (visited.contains(targetMethod)) return;
        visited.add(targetMethod);

        Set<ContextBuilder.CallGraph.CallSite> callers = findCallers(calleeToCallers, targetMethod, knownMethods, targetClass);
        if (callers == null || callers.isEmpty()) return;

        for (var caller : callers) {
            edges.add("\"" + escapeDot(caller.method) + "\" -> \"" + escapeDot(targetMethod) + "\" [color=blue]");
            collectCallChain(caller.method, calleeToCallers, visited, edges, knownMethods, targetClass);
        }
    }

    private static Set<ContextBuilder.CallGraph.CallSite> findCallers(
            Map<String, Set<ContextBuilder.CallGraph.CallSite>> calleeToCallers, String target,
            Set<String> knownMethods, String targetClass) {
        Set<ContextBuilder.CallGraph.CallSite> result = new TreeSet<>();
        result.addAll(calleeToCallers.getOrDefault(target, Collections.emptySet()));
        return result;
    }

    private static String escapeDot(String s) {
        return s.replace("\"", "\\\"");
    }

    static String buildAst(ContextBuilder cb, Path sourceFile) {
        StringBuilder sb = new StringBuilder();
        sb.append("# AST for: ").append(sourceFile.getFileName()).append("\n\n");

        CompilationUnit cu = cb.getModels().get(0).getAst(sourceFile);
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